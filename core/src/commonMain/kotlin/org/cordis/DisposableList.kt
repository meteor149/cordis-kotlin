package org.cordis

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Insertion-ordered identity set used by Cordis for effects, hooks and fibers. */
class DisposableList<T : Any> : Iterable<T> {
    private val lock = SynchronizedObject()
    private var serial = 0L
    private var values = linkedMapOf<Long, T>()
    private val serials = IdentityMap<T, Long>()

    val size: Int get() = synchronized(lock) { values.size }
    val isEmpty: Boolean get() = size == 0

    fun push(value: T): () -> Boolean = synchronized(lock) {
        val id = ++serial
        values[id] = value
        serials[value] = id
        return@synchronized {
            synchronized(lock) {
                val removed = values.remove(id) != null
                if (serials[value] == id) serials.remove(value)
                removed
            }
        }
    }

    fun unshift(value: T): () -> Boolean = synchronized(lock) {
        val id = ++serial
        values = linkedMapOf(id to value).also { it.putAll(values) }
        serials[value] = id
        return@synchronized {
            synchronized(lock) {
                val removed = values.remove(id) != null
                if (serials[value] == id) serials.remove(value)
                removed
            }
        }
    }

    fun delete(value: T): Boolean = synchronized(lock) {
        val id = serials.remove(value) ?: return@synchronized false
        values.remove(id) != null
    }

    /** Removes and returns entries in Cordis recovery (LIFO) order. */
    fun clear(): List<T> = synchronized(lock) {
        val result = values.values.toList().asReversed()
        values.clear()
        serials.clear()
        result
    }

    fun snapshot(): List<T> = synchronized(lock) { values.values.toList() }

    override fun iterator(): Iterator<T> = snapshot().iterator()

    override fun toString(): String = snapshot().toString()
}
