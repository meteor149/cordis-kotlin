package org.cordis

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private fun isBailed(value: Any?): Boolean = value != null && value != false

enum class DispatchMode { EMIT, PARALLEL, SERIAL, BAIL, WATERFALL }

/** Identity-based, strongly typed event channel. */
class EventKey<P, R>(val description: String, internal val frameworkInternal: Boolean = false) {
    init {
        require(description.isNotBlank()) { "event description must not be blank" }
    }

    override fun toString(): String = "EventKey($description)"
}

data class TypedEvent<P>(
    val registrationContext: Context,
    val receiver: Any?,
    val payload: P,
)

fun interface TypedEventListener<P, R> {
    fun handle(event: TypedEvent<P>): R
}

fun interface AsyncTypedEventListener<P, R> {
    suspend fun handle(event: TypedEvent<P>): R
}

fun interface TypedEventMiddleware<P, R> {
    fun handle(event: TypedEvent<P>, next: () -> R?): R?
}

fun interface AsyncTypedEventMiddleware<P, R> {
    suspend fun handle(event: TypedEvent<P>, next: suspend () -> R?): R?
}

data class EventOptions(
    val prepend: Boolean = false,
    val global: Boolean = false,
)

/** Strongly typed counterpart of an object's upstream `[Context.filter]`. */
fun interface EventFilter {
    fun filter(context: Context): Boolean
}

data class FiberUpdate(
    val fiber: Fiber<*>,
    val config: Any?,
    val noSave: Boolean,
)

data class FiberStatusChange(
    val fiber: Fiber<*>,
    val previous: FiberState,
)

data class ServiceChange(
    val name: String,
    val value: Any?,
)

data class EventDispatch(
    val mode: DispatchMode,
    val key: EventKey<*, *>,
    val payload: Any?,
    val receiver: Any?,
)

/** Framework lifecycle channels shared by all Cordis modules. */
object CoreEvents {
    val Plugin = EventKey<Fiber<*>, Unit>("internal/plugin", frameworkInternal = true)
    val Status = EventKey<FiberStatusChange, Unit>("internal/status", frameworkInternal = true)
    val Service = EventKey<ServiceChange, Unit>("internal/service", frameworkInternal = true)
    val Update = EventKey<FiberUpdate, Any?>("internal/update", frameworkInternal = true)
    val Dispatch = EventKey<EventDispatch, Unit>("internal/dispatch", frameworkInternal = true)
}

internal data class RawEvent(
    val registrationContext: Context,
    val receiver: Any?,
    val payload: Any?,
    val next: (() -> Any?)? = null,
)

internal fun interface RawEventCallback {
    fun handle(event: RawEvent): Any?
}

private data class Hook(
    val ctx: Context,
    val callback: RawEventCallback,
    val options: EventOptions,
)

private data class HookBucket(
    val key: EventKey<*, *>,
    val hooks: MutableList<Hook> = mutableListOf(),
)

class AggregateEventException(val causes: List<Throwable>) : RuntimeException(
    causes.joinToString(prefix = "multiple event handlers failed: ") { it.message ?: it::class.simpleName.orEmpty() },
) {
    init { causes.forEach(::addSuppressed) }
}

internal class EventsService(private val root: Context) {
    private val lock = SynchronizedObject()
    private val hooks = mutableListOf<HookBucket>()

    private fun bucket(key: EventKey<*, *>): HookBucket? = hooks.firstOrNull { it.key === key }

    internal fun installInternalHooks() {
        on(root, CoreEvents.Update, RawEventCallback { event ->
            val update = event.payload as FiberUpdate
            val callbacks = update.fiber.hooks[CoreEvents.Update]?.snapshot().orEmpty().toMutableList()
            val terminal = checkNotNull(event.next)
            fun next(): Any? {
                val callback = if (callbacks.isNotEmpty()) callbacks.removeAt(0) else return terminal()
                return callback.handle(event.copy(next = ::next))
            }
            next()
        }, EventOptions(prepend = true, global = true))
    }

    private fun resolve(
        mode: DispatchMode,
        receiver: Any?,
        key: EventKey<*, *>,
        payload: Any?,
    ): List<RawEventCallback> {
        if (!key.frameworkInternal && snapshot(CoreEvents.Dispatch).isNotEmpty()) {
            emit(null, CoreEvents.Dispatch, EventDispatch(mode, key, payload, receiver))
        }
        val filter: ((Context) -> Boolean)? = when (receiver) {
            is EventFilter -> receiver::filter
            is Context -> receiver.eventFilter
            else -> null
        }
        return snapshot(key)
            .filter { it.options.global || filter == null || filter(it.ctx) }
            .map { hook -> RawEventCallback { event ->
                hook.callback.handle(event.copy(registrationContext = hook.ctx))
            } }
    }

    private fun snapshot(key: EventKey<*, *>): List<Hook> =
        synchronized(lock) { bucket(key)?.hooks?.toList().orEmpty() }

    internal suspend fun parallel(receiver: Any?, key: EventKey<*, *>, payload: Any?) = coroutineScope {
        val results = resolve(DispatchMode.PARALLEL, receiver, key, payload).map { callback ->
            async {
                try {
                    Result.success(awaitResult(callback.handle(RawEvent(root, receiver, payload))))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
        }.awaitAll()
        val errors = results.mapNotNull { it.exceptionOrNull() }
        if (errors.isNotEmpty()) throw AggregateEventException(errors)
    }

    internal fun emit(receiver: Any?, key: EventKey<*, *>, payload: Any?) {
        resolve(DispatchMode.EMIT, receiver, key, payload).forEach {
            it.handle(RawEvent(root, receiver, payload))
        }
    }

    internal suspend fun serial(receiver: Any?, key: EventKey<*, *>, payload: Any?): Any? {
        resolve(DispatchMode.SERIAL, receiver, key, payload).forEach { callback ->
            val result = awaitResult(callback.handle(RawEvent(root, receiver, payload)))
            if (isBailed(result)) return result
        }
        return null
    }

    internal fun bail(receiver: Any?, key: EventKey<*, *>, payload: Any?): Any? {
        resolve(DispatchMode.BAIL, receiver, key, payload).forEach { callback ->
            val result = callback.handle(RawEvent(root, receiver, payload))
            if (isBailed(result)) return result
        }
        return null
    }

    internal suspend fun waterfall(
        receiver: Any?,
        key: EventKey<*, *>,
        payload: Any?,
        terminal: () -> Any?,
    ): Any? {
        val queue = resolve(DispatchMode.WATERFALL, receiver, key, payload).toMutableList()
        fun next(): Any? {
            val callback = if (queue.isNotEmpty()) queue.removeAt(0) else return terminal()
            return callback.handle(RawEvent(root, receiver, payload, ::next))
        }
        return awaitResult(next())
    }

    internal suspend fun awaitResult(value: Any?): Any? {
        var current = value
        while (current is Deferred<*>) current = current.await()
        return current
    }

    internal fun on(
        ctx: Context,
        key: EventKey<*, *>,
        listener: RawEventCallback,
        options: EventOptions,
    ): EffectHandle {
        ctx.fiber.assertActive()
        if (key === CoreEvents.Update && !options.global && ctx.fiber.runtime != null) {
            val local = ctx.fiber.hooks.getOrPut(key) { DisposableList() }
            return ctx.effect("ctx.listen(${key.description})") {
                val remove = if (options.prepend) local.unshift(listener) else local.push(listener)
                collect { remove(); Unit }
            }
        }
        return ctx.effect("ctx.listen(${key.description})") {
            synchronized(lock) {
                val list = bucket(key)?.hooks ?: mutableListOf<Hook>().also { hooks += HookBucket(key, it) }
                val hook = Hook(ctx, listener, options)
                if (options.prepend) list.add(0, hook) else list.add(hook)
            }
            collect { unregister(key, listener) }
        }
    }

    internal fun once(
        ctx: Context,
        key: EventKey<*, *>,
        listener: RawEventCallback,
        options: EventOptions,
    ): EffectHandle {
        lateinit var dispose: EffectHandle
        lateinit var wrapper: RawEventCallback
        wrapper = RawEventCallback { event ->
            remove(ctx, key, wrapper, options)
            ctx.scope.async { dispose.dispose() }
            listener.handle(event)
        }
        dispose = on(ctx, key, wrapper, options)
        return dispose
    }

    internal fun onAsync(
        ctx: Context,
        key: EventKey<*, *>,
        listener: suspend (RawEvent) -> Any?,
        options: EventOptions,
    ): EffectHandle = on(ctx, key, RawEventCallback { event ->
        ctx.scope.async(start = CoroutineStart.UNDISPATCHED) { listener(event) }
    }, options)

    internal fun onceAsync(
        ctx: Context,
        key: EventKey<*, *>,
        listener: suspend (RawEvent) -> Any?,
        options: EventOptions,
    ): EffectHandle {
        lateinit var dispose: EffectHandle
        lateinit var wrapper: RawEventCallback
        wrapper = RawEventCallback { event ->
            remove(ctx, key, wrapper, options)
            ctx.scope.async { dispose.dispose() }
            ctx.scope.async(start = CoroutineStart.UNDISPATCHED) { listener(event) }
        }
        dispose = on(ctx, key, wrapper, options)
        return dispose
    }

    private fun unregister(key: EventKey<*, *>, callback: RawEventCallback): Boolean = synchronized(lock) {
        val bucket = bucket(key) ?: return@synchronized false
        val index = bucket.hooks.indexOfFirst { it.callback === callback }
        if (index < 0) return@synchronized false
        bucket.hooks.removeAt(index)
        if (bucket.hooks.isEmpty()) hooks.remove(bucket)
        true
    }

    private fun remove(
        ctx: Context,
        key: EventKey<*, *>,
        callback: RawEventCallback,
        options: EventOptions,
    ): Boolean {
        if (key === CoreEvents.Update && !options.global && ctx.fiber.runtime != null) {
            return ctx.fiber.hooks[key]?.delete(callback) == true
        }
        return unregister(key, callback)
    }
}
