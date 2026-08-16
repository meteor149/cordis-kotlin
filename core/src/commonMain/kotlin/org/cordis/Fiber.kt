package org.cordis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

fun interface Disposable {
    suspend fun dispose()
}

data class EffectMeta(
    val label: String,
    val children: MutableList<EffectMeta> = mutableListOf(),
)

class EffectScope internal constructor(
    private val collector: (Disposable) -> Unit,
    private val active: () -> Boolean,
) {
    val isActive: Boolean get() = active()

    fun collect(disposable: Disposable) {
        // A disposer yielded by an async step must still be collected when the
        // handle was disposed while that step was in flight. The producer uses
        // ensureActive() between steps to implement async-iterator abortion.
        collector(disposable)
    }

    fun collect(dispose: suspend () -> Unit) = collect(Disposable(dispose))

    /** Explicit checkpoint for async-iterator-style effects. */
    fun ensureActive(): Boolean = isActive
}

class EffectHandle internal constructor(
    val meta: EffectMeta,
    private val detachFromFiber: (Disposable) -> Unit,
) : Disposable {
    private val active = atomic(true)
    private val collected = DisposableList<Disposable>()
    private val disposal = CompletableDeferred<Unit>()
    private lateinit var task: Deferred<Unit>

    internal fun attach(task: Deferred<Unit>) {
        this.task = task
    }

    internal fun collect(disposable: Disposable) {
        if (disposable === this) return
        detachFromFiber(disposable)
        collected.delete(disposable)
        collected.push(disposable)
        if (disposable is EffectHandle) meta.children += disposable.meta
    }

    internal fun isActive(): Boolean = active.value

    suspend fun awaitReady(): EffectHandle {
        task.await()
        return this
    }

    override suspend fun dispose() {
        if (!active.compareAndSet(true, false)) {
            disposal.await()
            return
        }
        var failure: Throwable? = null
        try {
            task.await()
        } catch (error: Throwable) {
            failure = error
        }
        collected.clear().forEach { disposable ->
            try {
                disposable.dispose()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        if (failure == null) {
            disposal.complete(Unit)
        } else {
            disposal.completeExceptionally(failure!!)
            throw failure!!
        }
    }
}

enum class FiberState {
    PENDING,
    LOADING,
    ACTIVE,
    FAILED,
    DISPOSED,
    UNLOADING,
}

class ValidationError(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class CordisError(val code: Code, message: String = code.message) : IllegalStateException(message) {
    enum class Code(val message: String) {
        INACTIVE_EFFECT("cannot create effect on inactive context"),
    }
}

class Fiber<C> internal constructor(
    val parent: Context,
    initialConfig: C,
    val inject: Dependencies,
    internal val runtime: PluginRuntime<C>?,
) {
    private val uidRef = atomic<Long?>(null)
    var uid: Long?
        get() = uidRef.value
        private set(value) { uidRef.value = value }

    val ctx: Context
    private val configRef = atomic(initialConfig)
    var config: C
        get() = configRef.value
        private set(value) { configRef.value = value }
    private val stateRef = atomic(FiberState.PENDING)
    var state: FiberState
        get() = stateRef.value
        private set(value) { stateRef.value = value }
    private val serviceLock = SynchronizedObject()
    private var serviceStore: MutableMap<String, ServiceBinding>? = null
    private val inertiaRef = atomic<Job?>(null)
    var inertia: Job?
        get() = inertiaRef.value
        private set(value) { inertiaRef.value = value }
    val attributes = Attributes()

    internal val hooks = mutableMapOf<EventKey<*, *>, DisposableList<RawEventCallback>>()
    internal val disposables = DisposableList<Disposable>()
    private val candidates = mutableMapOf<String, ServiceBinding>()
    private val dependencyLock = SynchronizedObject()
    private val transitionLock = SynchronizedObject()
    private var desiredEpoch = INACTIVE
    private var loadedEpoch = INACTIVE
    private var loaded = false
    private val errorRef = atomic<Throwable?>(null)
    private var error: Throwable?
        get() = errorRef.value
        set(value) { errorRef.value = value }
    private var owner: EffectHandle? = null

    /**
     * Starts rollback from a synchronous API without blocking its caller.
     * Undispatched start performs identity/list detachment before returning even
     * when a later disposer has to suspend.
     */
    private fun launchCleanup(block: suspend () -> Unit): Job =
        ctx.scope.launch(start = CoroutineStart.UNDISPATCHED) { block() }

    init {
        if (runtime == null) {
            uid = 0
            ctx = parent
            state = FiberState.ACTIVE
            serviceStore = mutableMapOf()
            desiredEpoch = ""
            loadedEpoch = ""
            loaded = true
        } else {
            uid = parent.registry.counter
            ctx = parent.extend()
            ctx.fiber = this
            inject.forEach { (name, value) ->
                if (value != null) ctx.setIntercept(ServiceReference(name), value)
            }
            // Context metadata (notably loader Entry association) must be
            // observable during internal/plugin, just as symbol properties on
            // the JavaScript parent Context are observable upstream.
            attributes.copyFrom(ctx.attributes)
            try {
                parent.emitEvent(CoreEvents.Plugin, this)
                owner = parent.fiber.effect("ctx.plugin()") {
                    collect { disposeInternal() }
                }
                runtime.fibers.push(this)
                if (runtime.isDeleting) {
                    launchCleanup { owner?.dispose() }
                } else if (uid != null) {
                    inject.keys.forEach(::checkBinding)
                    try {
                        val validator = runtime.plugin.config
                        config = validator?.validate(initialConfig) ?: initialConfig
                        refresh()
                    } catch (cause: Throwable) {
                        error = cause
                        state = FiberState.FAILED
                        ctx.logger().error(cause)
                    }
                }
            } catch (cause: Throwable) {
                runtime.fibers.delete(this)
                uid = null
                launchCleanup { owner?.dispose() }
                throw cause
            }
        }
    }

    val name: String
        get() {
            var current: Fiber<*> = this
            while (true) {
                current.runtime?.name?.let { return it }
                val next = current.parent.fiber
                if (next === current) return "root"
                current = next
            }
        }

    fun assertActive() {
        if (uid == null) throw CordisError(CordisError.Code.INACTIVE_EFFECT)
    }

    /** Whether this Fiber's plugin runtime is still owned by the Registry. */
    fun isRuntimeRegistered(): Boolean = runtime?.let { ctx.registry.has(it.plugin) } == true

    @OptIn(ExperimentalCoroutinesApi::class)
    fun effect(label: String = "anonymous", execute: suspend EffectScope.() -> Unit): EffectHandle {
        assertActive()
        val handle = EffectHandle(EffectMeta(label), disposables::delete)
        val task = ctx.scope.async(start = CoroutineStart.UNDISPATCHED) {
            execute(EffectScope(handle::collect, handle::isActive))
        }
        handle.attach(task)
        val remove = disposables.push(handle)
        handle.collect(Disposable { remove(); Unit })
        // Kotlin has no runtime distinction between a synchronous lambda and an
        // async function returning Promise. If execution completed before the
        // undispatched launch returned, preserve Cordis' synchronous throw and
        // partial-cleanup behavior.
        if (task.isCompleted) {
            val immediate = task.getCompletionExceptionOrNull()
            if (immediate != null) {
                launchCleanup { runCatching { handle.dispose() } }
                throw immediate
            }
        }
        task.invokeOnCompletion { cause ->
            if (cause != null) {
                ctx.scope.launch {
                    runCatching { handle.dispose() }
                        .onFailure { cleanup -> cause.addSuppressed(cleanup) }
                    ctx.logger().error(cause)
                }
            }
        }
        return handle
    }

    fun getEffects(): List<EffectMeta> = disposables.snapshot().mapNotNull { (it as? EffectHandle)?.meta }

    internal fun service(name: String): ServiceBinding? = synchronized(serviceLock) {
        serviceStore?.get(name)
    }

    internal fun install(binding: ServiceBinding) = synchronized(serviceLock) {
        checkNotNull(serviceStore)[binding.name] = binding
    }

    internal fun removeService(name: String) = synchronized(serviceLock) {
        serviceStore?.remove(name)
        Unit
    }

    internal fun checkBinding(name: String) {
        val binding = ctx.services.getBinding(ctx, name, strict = true)
        if (binding == null) {
            synchronized(dependencyLock) { candidates.remove(name) }
            return
        }
        try {
            if (binding.check?.invoke(ctx) == false) {
                synchronized(dependencyLock) { candidates.remove(name) }
                return
            }
        } catch (cause: Throwable) {
            binding.fiber.ctx.logger().error(cause)
            synchronized(dependencyLock) { candidates.remove(name) }
            return
        }
        synchronized(dependencyLock) { candidates[name] = binding }
    }

    internal fun refresh() {
        val snapshot = synchronized(dependencyLock) { candidates.toMap() }
        var epoch = ""
        inject.keys.forEach { name ->
            val impl = snapshot[name]
            if (impl == null) {
                epoch = INACTIVE
                return@forEach
            }
            if (epoch != INACTIVE) epoch += ":${impl.fiber.uid}"
        }
        setEpoch(epoch)
    }

    private fun setEpoch(epoch: String) {
        var start: Job? = null
        synchronized(transitionLock) {
            if (epoch == desiredEpoch) return
            desiredEpoch = epoch
            if (inertia == null) {
                start = ctx.scope.launch(start = CoroutineStart.LAZY) { transitionLoop() }
                inertia = start
            }
        }
        start?.start()
    }

    private suspend fun transitionLoop() {
        try {
            while (true) {
                val target = synchronized(transitionLock) { desiredEpoch }
                if (loaded) {
                    if (target == loadedEpoch) {
                        updateState(if (error == null) FiberState.ACTIVE else FiberState.FAILED)
                        return
                    }
                    updateState(FiberState.UNLOADING)
                    unload()
                    loaded = false
                    loadedEpoch = INACTIVE
                    continue
                }

                if (target == INACTIVE) {
                    updateState(derivedState())
                    return
                }

                updateState(FiberState.LOADING)
                synchronized(serviceLock) {
                    serviceStore = synchronized(dependencyLock) { candidates.toMutableMap() }
                }
                loadedEpoch = target
                loaded = true
                try {
                    val currentRuntime = runtime
                    if (currentRuntime != null) {
                        val effect = EffectScope(
                            collector = { disposable ->
                                disposables.delete(disposable)
                                disposables.push(disposable)
                            },
                            active = { synchronized(transitionLock) { desiredEpoch == target } },
                        )
                        currentRuntime.plugin.apply(ctx, config, effect)
                    }
                } catch (cause: Throwable) {
                    ctx.logger().error(cause)
                    error = cause
                    synchronized(transitionLock) { desiredEpoch = INACTIVE }
                    continue
                }

                if (synchronized(transitionLock) { desiredEpoch } == target) {
                    updateState(FiberState.ACTIVE)
                    return
                }
            }
        } finally {
            var restart: Job? = null
            synchronized(transitionLock) {
                inertia = null
                val target = desiredEpoch
                val settled = (loaded && target == loadedEpoch) || (!loaded && target == INACTIVE)
                if (!settled) {
                    restart = ctx.scope.launch(start = CoroutineStart.LAZY) { transitionLoop() }
                    inertia = restart
                }
            }
            restart?.start()
        }
    }

    private suspend fun unload() {
        val failures = mutableListOf<Throwable>()
        disposables.clear().forEach { disposable ->
            try {
                disposable.dispose()
            } catch (cause: Throwable) {
                failures += cause
                ctx.logger().error(cause)
            }
        }
        synchronized(serviceLock) { serviceStore = null }
        if (failures.size > 1) failures.drop(1).forEach(failures[0]::addSuppressed)
    }

    private fun derivedState(): FiberState = when {
        uid == null -> FiberState.DISPOSED
        error != null -> FiberState.FAILED
        loaded -> FiberState.ACTIVE
        else -> FiberState.PENDING
    }

    private fun updateState(value: FiberState) {
        val old = state
        if (old == value) return
        state = value
        ctx.emitEvent(CoreEvents.Status, FiberStatusChange(this, old))
        if (old != FiberState.ACTIVE && value != FiberState.ACTIVE) return
        ctx.services.providers(this).forEach { binding ->
            ctx.services.notifyChange(ctx, listOf(binding.name)) { target, name ->
                target.isolateLabels[name] === binding.realm
            }
        }
    }

    suspend fun await(): Fiber<C> {
        while (true) {
            val current = inertia ?: break
            current.join()
        }
        error?.let { throw it }
        return this
    }

    private suspend fun awaitSettled() {
        while (true) {
            val current = inertia ?: break
            current.join()
        }
    }

    suspend fun dispose() {
        owner?.dispose() ?: restart()
    }

    private suspend fun disposeInternal() {
        if (uid == null) return
        uid = null
        ctx.emitEvent(CoreEvents.Plugin, this)
        runtime?.let { current ->
            current.fibers.delete(this)
            ctx.registry.removeIfEmpty(current)
        }
        setEpoch(INACTIVE)
        awaitSettled()
        if (state != FiberState.DISPOSED) updateState(FiberState.DISPOSED)
    }

    suspend fun restart() {
        assertActive()
        setEpoch(INACTIVE)
        awaitSettled()
        inject.keys.forEach(::checkBinding)
        refresh()
        await()
    }

    suspend fun update(config: C, noSave: Boolean = false) {
        assertActive()
        val current = checkNotNull(runtime)
        val validator = current.plugin.config
        val nextConfig = validator?.validate(config) ?: config
        var reachedTerminal = false
        ctx.waterfallEvent(this, CoreEvents.Update, FiberUpdate(this, nextConfig, noSave)) {
            reachedTerminal = true
            this.config = nextConfig
            error = null
            Unit
        }
        if (reachedTerminal) restart()
    }

    companion object {
        private const val INACTIVE = "__INACTIVE__"

        internal fun root(ctx: Context): Fiber<Unit> = Fiber(ctx, Unit, Dependencies.Empty, null)
    }
}
