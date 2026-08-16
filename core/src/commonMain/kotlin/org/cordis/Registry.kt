package org.cordis

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.yield

class RegistryService internal constructor(private val root: Context) {
    private val lock = SynchronizedObject()
    private val serial = atomic(0L)
    private val runtimes = IdentityMap<Plugin<*>, PluginRuntime<*>>()

    val counter: Long get() = serial.incrementAndGet()
    val size: Int get() = synchronized(lock) { runtimes.size }

    fun <C> get(plugin: Plugin<C>): PluginRuntime<C>? =
        synchronized(lock) { erasedValue(runtimes[plugin]) }

    fun has(plugin: Plugin<*>): Boolean = synchronized(lock) { runtimes.containsKey(plugin) }

    suspend fun delete(plugin: Plugin<*>): PluginRuntime<*>? {
        val runtime = synchronized(lock) {
            runtimes.remove(plugin)?.also { it.markDeleting() }
        } ?: return null
        while (true) {
            runtime.fibers.snapshot().forEach { it.dispose() }
            if (runtime.creationCount == 0 && runtime.fibers.isEmpty) break
            yield()
        }
        return runtime
    }

    fun keys(): List<Plugin<*>> = synchronized(lock) { runtimes.keys }
    fun values(): List<PluginRuntime<*>> = synchronized(lock) { runtimes.values }
    fun entries(): List<Pair<Plugin<*>, PluginRuntime<*>>> = synchronized(lock) { runtimes.pairs() }

    fun forEach(callback: (PluginRuntime<*>, Plugin<*>) -> Unit) {
        entries().forEach { (plugin, runtime) -> callback(runtime, plugin) }
    }

    fun <C> plugin(ctx: Context, plugin: Plugin<C>, config: C): Fiber<C> {
        ctx.fiber.assertActive()
        val runtime = synchronized(lock) {
            (erasedValue<PluginRuntime<C>?>(runtimes[plugin])?.takeUnless { it.isDeleting }
                ?: PluginRuntime(plugin).also { runtimes[plugin] = it })
                .also { it.reserve() }
        }
        return try {
            Fiber(ctx, config, plugin.inject, runtime)
        } finally {
            runtime.release()
            removeIfEmpty(runtime)
        }
    }

    internal fun removeIfEmpty(runtime: PluginRuntime<*>) {
        if (!runtime.isUnused) return
        synchronized(lock) {
            if (!runtime.isUnused) return
            if (runtimes[runtime.plugin] === runtime) runtimes.remove(runtime.plugin)
        }
    }
}
