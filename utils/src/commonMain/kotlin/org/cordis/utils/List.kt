package org.cordis.utils

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.cordis.Context
import org.cordis.EffectHandle

/** Context-owned insertion ordered list aligned with `@cordisjs/utils.List`. */
class ContextList<T>(
    val ctx: Context,
    private val trace: String,
) : Iterable<T> {
    private val lock = SynchronizedObject()
    private var serial = 0L
    private val inner = linkedMapOf<Long, T>()

    val size: Int get() = synchronized(lock) { inner.size }
    val isEmpty: Boolean get() = size == 0
    fun add(value: T): EffectHandle = add(ctx, value)

    fun add(owner: Context, value: T): EffectHandle =
        owner.effect("$trace.add()") {
            val id = synchronized(lock) { (++serial).also { inner[it] = value } }
            collect { synchronized(lock) { inner.remove(id) }; Unit }
        }

    fun filter(predicate: (T) -> Boolean): Sequence<T> = snapshot().asSequence().filter(predicate)
    fun <U> map(mapper: (T) -> U): Sequence<U> = snapshot().asSequence().map(mapper)
    private fun snapshot(): kotlin.collections.List<T> = synchronized(lock) { inner.values.toList() }
    override fun iterator(): Iterator<T> = snapshot().iterator()
    override fun toString(): String = snapshot().toString()
}
