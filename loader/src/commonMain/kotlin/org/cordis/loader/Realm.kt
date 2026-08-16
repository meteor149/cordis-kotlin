package org.cordis.loader

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.cordis.ServiceRealm

abstract class Realm {
    private val lock = SynchronizedObject()
    private val store = mutableMapOf<String, ServiceRealm>()
    abstract val suffix: String
    fun access(key: String, create: Boolean = false): ServiceRealm = synchronized(lock) {
        store[key] ?: ServiceRealm("$key$suffix").also { if (create) store[key] = it }
    }
    fun delete(key: String) = synchronized(lock) { store.remove(key) }
    val size: Int get() = synchronized(lock) { store.size }
}

class LocalRealm(private val entry: Entry) : Realm() {
    override val suffix: String get() = "#${entry.options.id}"
}

class GlobalRealm(val label: String) : Realm() {
    override val suffix: String get() = "@$label"
}
