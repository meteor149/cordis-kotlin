package org.cordis

/** Identity-based key for metadata attached to a [Context] or [Fiber]. */
class AttributeKey<T>(val description: String) {
    init {
        require(description.isNotBlank()) { "attribute description must not be blank" }
    }

    override fun toString(): String = "AttributeKey($description)"
}

/** Type-safe metadata storage; keys with equal descriptions remain distinct. */
class Attributes internal constructor() {
    private val values = IdentityMap<AttributeKey<*>, Any>()

    operator fun <T> get(key: AttributeKey<T>): T? = erasedValue(values[key])

    operator fun <T : Any> set(key: AttributeKey<T>, value: T) {
        values[key] = value
    }

    fun remove(key: AttributeKey<*>): Boolean = values.remove(key) != null

    internal fun copyFrom(source: Attributes) {
        source.values.pairs().forEach { (key, value) -> values[key] = value }
    }
}
