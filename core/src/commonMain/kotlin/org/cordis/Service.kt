package org.cordis

fun interface ConfigResolver<T> {
    fun resolve(values: List<T>): T
}

fun <K, V> mergeMapConfig(): ConfigResolver<Map<K, V>> = ConfigResolver { values ->
    buildMap { values.forEach(::putAll) }
}

/** Base class for a context-provided capability. */
abstract class Service<T>(
    protected val ctx: Context,
    val key: ServiceKey<*>,
    private val configKey: InterceptKey<T>? = null,
    private val configResolver: ConfigResolver<T> = ConfigResolver { values ->
        values.lastOrNull() ?: error("no configuration is available")
    },
) : EventFilter {
    val name: String get() = key.name

    init {
        ctx.services.provideService(ctx, key, this) { target -> check(target) }
    }

    protected open fun check(target: Context): Boolean =
        target.isolateLabels[name] === ctx.isolateLabels[name]

    override fun filter(context: Context): Boolean =
        context.isolateLabels[name] === ctx.isolateLabels[name]

    /** Kotlin counterpart of the upstream `[Service.init]` lifecycle method. */
    protected open suspend fun initialize(): Disposable? = null

    internal suspend fun initializeForPlugin(effect: EffectScope) {
        initialize()?.let(effect::collect)
    }

    protected open fun resolveConfig(base: T? = null, head: T? = null): T =
        resolveConfig(ctx, base, head)

    /**
     * Resolves this service's configuration for an explicit calling Context.
     *
     * JavaScript Cordis obtains the caller from a Proxy-backed service shadow.
     * Kotlin makes that context explicit, which is deterministic and safe when
     * one service instance is used concurrently by multiple Fibers.
     */
    fun resolveConfig(context: Context, base: T? = null, head: T? = null): T {
        val configs = buildList {
            if (base != null) add(base)
            configKey?.let { addAll(context.interceptValues(it)) }
            if (head != null) add(head)
        }
        return configResolver.resolve(configs)
    }
}

/**
 * Adapts a Kotlin service constructor to Cordis' class-plugin lifecycle.
 * The service is provided by its constructor, but consumers only see it after
 * [Service.initialize] has completed because its owning Fiber is still loading.
 */
fun <C, S : Service<*>> servicePlugin(
    name: String? = null,
    inject: Dependencies = Dependencies.Empty,
    validator: ConfigValidator<C>? = null,
    create: (Context, C) -> S,
): Plugin<C> = plugin(name = name, inject = inject, validator = validator) { ctx, config ->
    create(ctx, config).initializeForPlugin(this)
}
