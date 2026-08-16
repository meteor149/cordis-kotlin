package org.cordis

/**
 * The single type-erasure boundary for identity-keyed heterogeneous storage.
 * Callers must first prove that the value was stored under the same typed key.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> erasedValue(value: Any?): T = value as T

/** Validates a dynamic module export before adapting its erased config type. */
fun Any?.asDynamicPlugin(): Plugin<Any?>? {
    val plugin = this as? Plugin<*> ?: return null
    return erasedValue(plugin)
}
