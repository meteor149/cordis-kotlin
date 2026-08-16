package org.cordis

import kotlinx.atomicfu.atomic

class Dependencies private constructor(private val valuesByName: Map<String, Any?>) : Map<String, Any?> by valuesByName {
    override fun equals(other: Any?): Boolean = when (other) {
        is Dependencies -> valuesByName == other.valuesByName
        is Map<*, *> -> valuesByName == other
        else -> false
    }

    override fun hashCode(): Int = valuesByName.hashCode()
    override fun toString(): String = valuesByName.toString()

    companion object {
        val Empty = Dependencies(emptyMap())
        internal fun from(entries: Map<String, Any?>): Dependencies =
            if (entries.isEmpty()) Empty else Dependencies(entries.toMap())
    }
}

/** Creates an injection declaration without losing service names to string literals. */
fun dependencies(vararg keys: ServiceKey<*>): Dependencies =
    Dependencies.from(keys.associate { it.name to null })

class ConfiguredDependency<C> internal constructor(
    internal val name: String,
    internal val config: C,
)

fun <C, S : Service<C>> ServiceKey<S>.configured(config: C): ConfiguredDependency<C> =
    ConfiguredDependency(name, config)

fun <S, C> ServiceKey<S>.configured(intercept: InterceptKey<C>, config: C): ConfiguredDependency<C> {
    require(name == intercept.name) { "service and intercept keys must have the same name" }
    return ConfiguredDependency(name, config)
}

fun dependencies(vararg entries: ConfiguredDependency<*>): Dependencies =
    Dependencies.from(entries.associate { it.name to it.config })

/** Explicit loader boundary for dependency declarations read from configuration. */
fun Dependencies.withConfiguredServices(entries: Map<String, Any?>): Dependencies =
    Dependencies.from(this + entries)

fun interface ConfigValidator<C> {
    fun validate(value: C): C
}

/** Kotlin counterpart of Cordis' function/constructor/object plugin union. */
interface Plugin<C> {
    val name: String? get() = null
    val config: ConfigValidator<C>? get() = null
    val inject: Dependencies get() = Dependencies.Empty

    suspend fun apply(ctx: Context, config: C, effect: EffectScope)
}

fun <C> plugin(
    name: String? = null,
    inject: Dependencies = Dependencies.Empty,
    validator: ConfigValidator<C>? = null,
    apply: suspend EffectScope.(Context, C) -> Unit,
): Plugin<C> = object : Plugin<C> {
    override val name = name
    override val inject = inject
    override val config = validator

    override suspend fun apply(ctx: Context, config: C, effect: EffectScope) {
        effect.apply(ctx, config)
    }
}

class PluginRuntime<C>(
    val plugin: Plugin<C>,
) {
    val name: String? = plugin.name?.takeUnless { it == "apply" }
    val fibers = DisposableList<Fiber<*>>()
    private val pendingCreations = atomic(0)
    private val deleting = atomic(false)

    internal fun reserve() = pendingCreations.incrementAndGet()
    internal fun release() = pendingCreations.decrementAndGet()
    internal fun markDeleting() { deleting.value = true }
    internal val creationCount: Int get() = pendingCreations.value
    internal val isDeleting: Boolean get() = deleting.value
    internal val isUnused: Boolean get() = pendingCreations.value == 0 && fibers.isEmpty
}
