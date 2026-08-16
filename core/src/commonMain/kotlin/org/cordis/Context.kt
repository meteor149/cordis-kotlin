package org.cordis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal class LayeredMap<K, V>(private var parent: LayeredMap<K, V>? = null) : SynchronizedObject() {
    private val own = mutableMapOf<K, V>()

    operator fun get(key: K): V? = synchronized(this) {
        if (own.containsKey(key)) own[key] else parent?.get(key)
    }

    fun contains(key: K): Boolean = synchronized(this) { own.containsKey(key) } || parent?.contains(key) == true
    fun containsOwn(key: K): Boolean = synchronized(this) { own.containsKey(key) }
    operator fun set(key: K, value: V) = synchronized(this) { own[key] = value }
    fun putIfAbsent(key: K, value: V): V = synchronized(this) {
        own[key] ?: parent?.get(key) ?: value.also { own[key] = it }
    }
    fun clearOwn() = synchronized(this) { own.clear() }
    fun rebase(parent: LayeredMap<K, V>?) = synchronized(this) { this.parent = parent }
    fun child() = LayeredMap(this)
    fun lineageValues(key: K): List<V> = (parent?.lineageValues(key).orEmpty()) +
        synchronized(this) { if (own.containsKey(key)) listOfNotNull(own[key]) else emptyList() }
    fun keys(): Set<K> = parent?.keys().orEmpty() + synchronized(this) { own.keys.toSet() }
}

/** Runtime context and capability boundary. */
open class Context private constructor(prototype: Context?) {
    private var prototypeContext: Context? = prototype
    val root: Context
    var baseUrl: String? = prototype?.baseUrl

    internal val scope: CoroutineScope
    internal var isolateLabels: LayeredMap<String, Any>
    internal var interceptConfigs: LayeredMap<String, Any?>
    internal var eventFilter: ((Context) -> Boolean)? = null
    val attributes = Attributes()

    lateinit var fiber: Fiber<*>
        internal set
    internal lateinit var services: ContextServices
        private set
    lateinit var registry: RegistryService
        private set
    internal lateinit var events: EventsService
        private set
    lateinit var logger: LoggerService
        private set

    constructor() : this(null)

    init {
        if (prototype == null) {
            root = this
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            isolateLabels = LayeredMap()
            interceptConfigs = LayeredMap()
            fiber = Fiber.root(this)
            services = ContextServices(this)
            registry = RegistryService(this)
            events = EventsService(this)
            logger = LoggerService(this)
            events.installInternalHooks()
            // Bootstrap registrations live for the root lifetime and are deliberately
            // not part of root.restart(), matching Context's constructor in Cordis.
            fiber.disposables.clear()
        } else {
            root = prototype.root
            scope = prototype.scope
            isolateLabels = prototype.isolateLabels.child()
            interceptConfigs = prototype.interceptConfigs.child()
            fiber = prototype.fiber
            services = prototype.services
            registry = prototype.registry
            events = prototype.events
            logger = prototype.logger.bind(this)
            attributes.copyFrom(prototype.attributes)
        }
    }

    fun extend(configure: (Context.() -> Unit)? = null): Context = Context(this).also { child ->
        configure?.invoke(child)
    }

    fun <T> isolate(key: ServiceKey<T>, label: Any = ServiceRealm(key.name)): Context =
        extend { isolateLabels[key.name] = label }

    fun <T> intercept(key: InterceptKey<T>, config: T): Context = extend {
        interceptConfigs[key.name] = config
    }

    /** Runtime hook used by the declarative loader when an Entry changes realm. */
    fun setIsolation(reference: ServiceReference, label: Any) {
        isolateLabels[reference.name] = label
    }

    fun setIntercept(reference: ServiceReference, config: Any?) {
        interceptConfigs[reference.name] = config
    }

    fun <T> setIntercept(key: InterceptKey<T>, config: T) {
        interceptConfigs[key.name] = config
    }

    fun replaceIsolation(values: Map<ServiceReference, Any>) {
        isolateLabels.clearOwn()
        values.forEach { (reference, label) -> isolateLabels[reference.name] = label }
    }

    fun replaceIntercept(values: Map<ServiceReference, Any?>) {
        interceptConfigs.clearOwn()
        values.forEach { (reference, config) -> interceptConfigs[reference.name] = config }
    }

    fun <T> interceptValues(key: InterceptKey<T>): List<T> =
        interceptConfigs.lineageValues(key.name).map(::erasedValue)

    /** Reads untyped intercept values that originated in loader configuration. */
    fun configuredInterceptValues(reference: ServiceReference): List<Any?> =
        interceptConfigs.lineageValues(reference.name)

    /** Reparents a loader-managed Context while preserving its own metadata. */
    fun rebase(prototype: Context) {
        require(root === prototype.root) { "cannot rebase Context across roots" }
        prototypeContext = prototype
        isolateLabels.rebase(prototype.isolateLabels)
        interceptConfigs.rebase(prototype.interceptConfigs)
        baseUrl = prototype.baseUrl
    }

    fun isolationToken(reference: ServiceReference): Any? = isolateLabels[reference.name]
    fun isolationNames(): Set<ServiceReference> = isolateLabels.keys().mapTo(mutableSetOf(), ::ServiceReference)

    fun isDescendantOf(ancestor: Context): Boolean {
        var current: Context? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.prototypeContext
        }
        return false
    }

    operator fun <T> get(key: ServiceKey<T>): T? = services.get(this, key)

    operator fun <T> get(property: ContextProperty<T>): T? = services.get(this, property)

    /** Resolves the deliberately dynamic service reference used by configuration expressions. */
    fun resolveService(reference: ServiceReference): Any? = services.getDynamic(this, reference.name)

    /** Applies a loader-requested realm move for a configured service name. */
    fun relocateService(reference: ServiceReference, previousRealm: Any?, nextRealm: Any?) {
        services.relocateProvider(reference.name, previousRealm, nextRealm, this)
        services.notifyChange(this, listOf(reference.name)) { target, name ->
            val token = target.isolateLabels[name]
            token === previousRealm || token === nextRealm
        }
    }

    fun <T> notifyServiceChange(key: ServiceKey<T>) {
        services.notifyChange(this, listOf(key.name))
    }

    fun <T : Any> require(key: ServiceKey<T>): T = get(key)
        ?: throw IllegalStateException("required service \"${key.name}\" is unavailable")

    operator fun <T> set(key: ServiceKey<T>, value: T?) {
        services.set(this, key, value)
    }

    operator fun <T> set(property: ContextProperty<T>, value: T?) {
        services.set(this, property, value)
    }

    fun <T> provide(key: ServiceKey<T>, value: T? = null, check: ((Context) -> Boolean)? = null): EffectHandle =
        services.provide(this, key, value, check)

    fun <T> property(property: ContextProperty<T>): EffectHandle = services.register(this, property)

    fun effect(label: String = "anonymous", execute: suspend EffectScope.() -> Unit): EffectHandle =
        fiber.effect(label, execute)

    fun effect(dispose: suspend () -> Unit, label: String = "anonymous"): EffectHandle =
        effect(label) { collect(dispose) }

    fun <C> plugin(plugin: Plugin<C>, config: C): Fiber<C> = registry.plugin(this, plugin, config)

    fun inject(deps: Dependencies, callback: suspend EffectScope.(Context) -> Unit): Fiber<Unit> =
        plugin(plugin<Unit>(name = null, inject = deps) { ctx, _ -> callback(ctx) }, Unit)

    fun inject(vararg deps: ServiceKey<*>, callback: suspend EffectScope.(Context) -> Unit): Fiber<Unit> =
        inject(dependencies(*deps), callback)

    fun <P, R> listen(
        key: EventKey<P, R>,
        options: EventOptions = EventOptions(),
        listener: TypedEventListener<P, R>,
    ): EffectHandle = events.on(this, key, RawEventCallback { event ->
        listener.handle(TypedEvent(event.registrationContext, event.receiver, erasedValue(event.payload)))
    }, options)

    fun <P, R> listenOnce(
        key: EventKey<P, R>,
        options: EventOptions = EventOptions(),
        listener: TypedEventListener<P, R>,
    ): EffectHandle = events.once(this, key, RawEventCallback { event ->
        listener.handle(TypedEvent(event.registrationContext, event.receiver, erasedValue(event.payload)))
    }, options)

    fun <P, R> listenAsync(
        key: EventKey<P, R>,
        options: EventOptions = EventOptions(),
        listener: AsyncTypedEventListener<P, R>,
    ): EffectHandle = events.onAsync(this, key, { event ->
        listener.handle(TypedEvent(event.registrationContext, event.receiver, erasedValue(event.payload)))
    }, options)

    fun <P, R> listenOnceAsync(
        key: EventKey<P, R>,
        options: EventOptions = EventOptions(),
        listener: AsyncTypedEventListener<P, R>,
    ): EffectHandle = events.onceAsync(this, key, { event ->
        listener.handle(TypedEvent(event.registrationContext, event.receiver, erasedValue(event.payload)))
    }, options)

    fun <P, R> interceptEvent(
        key: EventKey<P, R>,
        options: EventOptions = EventOptions(),
        middleware: TypedEventMiddleware<P, R>,
    ): EffectHandle = events.on(this, key, RawEventCallback { event ->
        middleware.handle(
            TypedEvent(event.registrationContext, event.receiver, erasedValue(event.payload)),
            erasedValue(checkNotNull(event.next)),
        )
    }, options)

    fun <P, R> interceptEventAsync(
        key: EventKey<P, R>,
        options: EventOptions = EventOptions(),
        middleware: AsyncTypedEventMiddleware<P, R>,
    ): EffectHandle = events.onAsync(this, key, { event ->
        middleware.handle(
            TypedEvent(event.registrationContext, event.receiver, erasedValue(event.payload)),
        ) {
            erasedValue(events.awaitResult(checkNotNull(event.next).invoke()))
        }
    }, options)

    fun <P, R> emitEvent(key: EventKey<P, R>, payload: P) = events.emit(null, key, payload)
    fun <P, R> emitEvent(receiver: Any?, key: EventKey<P, R>, payload: P) =
        events.emit(receiver, key, payload)
    suspend fun <P, R> parallelEvent(key: EventKey<P, R>, payload: P) =
        events.parallel(null, key, payload)
    suspend fun <P, R> parallelEvent(receiver: Any?, key: EventKey<P, R>, payload: P) =
        events.parallel(receiver, key, payload)
    suspend fun <P, R> serialEvent(key: EventKey<P, R>, payload: P): R? =
        erasedValue(events.serial(null, key, payload))
    suspend fun <P, R> serialEvent(receiver: Any?, key: EventKey<P, R>, payload: P): R? =
        erasedValue(events.serial(receiver, key, payload))
    fun <P, R> bailEvent(key: EventKey<P, R>, payload: P): R? =
        erasedValue(events.bail(null, key, payload))
    fun <P, R> bailEvent(receiver: Any?, key: EventKey<P, R>, payload: P): R? =
        erasedValue(events.bail(receiver, key, payload))

    suspend fun <P, R> waterfallEvent(key: EventKey<P, R>, payload: P, terminal: () -> R?): R? =
        erasedValue(events.waterfall(null, key, payload, terminal))

    suspend fun <P, R> waterfallEvent(
        receiver: Any?,
        key: EventKey<P, R>,
        payload: P,
        terminal: () -> R?,
    ): R? = erasedValue(events.waterfall(receiver, key, payload, terminal))

    override fun toString(): String = "Context <${fiber.name}>"

}

/** Identity token (like JavaScript Symbol); equal descriptions are still distinct realms. */
class ServiceRealm(val name: String) {
    override fun toString(): String = "ServiceRealm($name)"
}
