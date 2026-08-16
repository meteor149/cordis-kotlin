package org.cordis

/** Insertion-ordered map whose keys use reference identity, like JavaScript object keys. */
internal class IdentityMap<K : Any, V> {
    private val entries = mutableListOf<Pair<K, V>>()
    val size: Int get() = entries.size
    val keys: List<K> get() = entries.map { it.first }
    val values: List<V> get() = entries.map { it.second }

    operator fun get(key: K): V? = entries.firstOrNull { it.first === key }?.second
    operator fun set(key: K, value: V) {
        val index = entries.indexOfFirst { it.first === key }
        if (index < 0) entries += key to value else entries[index] = key to value
    }
    fun containsKey(key: K): Boolean = entries.any { it.first === key }
    fun remove(key: K): V? {
        val index = entries.indexOfFirst { it.first === key }
        return if (index < 0) null else entries.removeAt(index).second
    }
    fun clear() = entries.clear()
    fun pairs(): List<Pair<K, V>> = entries.toList()
}
