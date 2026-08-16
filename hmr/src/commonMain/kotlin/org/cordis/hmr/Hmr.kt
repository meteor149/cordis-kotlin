package org.cordis.hmr

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.EventKey
import org.cordis.Fiber
import org.cordis.Plugin
import org.cordis.PluginRuntime
import org.cordis.Service
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.asDynamicPlugin
import org.cordis.loader.Entry
import org.cordis.loader.Loader
import org.cordis.loader.ModuleLoader
import org.cordis.loader.RefreshableEntryTree

data class HmrConfig(
    val base: String? = null,
    val root: List<String> = listOf("."),
    val debounce: Long = 100,
    val ignored: List<String> = listOf("**/node_modules", "**/.*", "cache", "data"),
)

data class Reload(val filename: String, val runtime: PluginRuntime<*>? = null)

object HmrEvents {
    val Change = EventKey<Set<String>, Unit>("hmr/change")
    val Reload = EventKey<Map<Plugin<*>, org.cordis.hmr.Reload>, Unit>("hmr/reload")
}

class Hmr(private val context: Context, val config: HmrConfig) : Service<Unit>(context, Key) {
    val baseDir: String = PlatformHmr.resolveBase(config.base, context.baseUrl)
    private val loader: Loader = context.require(Loader.Key)
    private val internal: ModuleLoader = loader.internal
        ?: throw IllegalStateException("a reload-capable ModuleLoader is required for HMR service")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private var watcher: PlatformWatcher? = null
    private var scheduled: Job? = null
    private val externals = linkedSetOf<String>()
    private val stashed = linkedSetOf<String>()
    var accepted: Set<String> = emptySet()
        private set
    var declined: Set<String> = emptySet()
        private set

    companion object {
        val Key = ServiceKey<Hmr>("hmr")
    }

    init { externals += internal.externals() }

    fun start() {
        if (watcher != null) return
        watcher = PlatformWatcher(baseDir, config.root, config.ignored) { path ->
            scope.launch { change(path) }
        }.also { it.start() }
        context.logger().info("watching %o in %s", config.root, baseDir)
    }

    suspend fun stop() {
        synchronized(lock) {
            scheduled?.cancel()
            scheduled = null
        }
        watcher?.stop()
        watcher = null
        scope.cancel()
    }

    suspend fun getLinked(url: String): List<String> = internal.linked(url)

    fun stash(url: String) {
        synchronized(lock) {
            stashed += url
            scheduled?.cancel()
            scheduled = scope.launch {
                delay(config.debounce.coerceAtLeast(0))
                partialReload()
            }
        }
    }

    suspend fun change(path: String) {
        val filename = PlatformHmr.normalize(path)
        val url = PlatformHmr.toFileUrl(filename)
        context.logger().debug("change detected at %s", path)
        if (url in externals) {
            loader.exit()
            return
        }
        if (internal.contains(url)) {
            stash(url)
            return
        }
        val include = loader.entries().mapNotNull { it.subtree as? RefreshableEntryTree }
            .firstOrNull { PlatformHmr.normalize(it.filename) == filename }
        if (include != null) {
            include.refresh()
            return
        }
        context.emitEvent(HmrEvents.Change, setOf(url))
    }

    private fun dependencyClosure(root: String, ignored: Set<String> = emptySet()): Set<String> {
        val result = linkedSetOf<String>()
        fun visit(url: String) {
            if (url in ignored || url in result || excluded(url)) return
            result += url
            internal.linked(url).forEach(::visit)
        }
        visit(root)
        return result
    }

    fun analyzeChanges(changes: Set<String> = synchronized(lock) { stashed.toSet() }) {
        val nextAccepted = changes
        val all = loader.entries().mapNotNull { entry ->
            runCatching { internal.resolve(entry.options.name, entry.parent.tree.ctx.baseUrl).url }.getOrNull()
        }.flatMap { dependencyClosure(it) }.toSet()
        synchronized(lock) {
            accepted = nextAccepted
            declined = externals + (all - changes)
        }
    }

    suspend fun partialReload(): Boolean {
        val changes = synchronized(lock) {
            scheduled = null
            stashed.toSet().also(stashed::removeAll)
        }
        if (changes.isEmpty()) return true
        analyzeChanges(changes)

        data class Target(
            val oldPlugin: Plugin<Any?>,
            val url: String,
            val runtime: PluginRuntime<Any?>?,
            val fibers: List<FiberSnapshot>,
            val dependencies: Set<String>,
        )

        val targets = mutableListOf<Target>()
        loader.entries().forEach { entry ->
            val url = runCatching { internal.resolve(entry.options.name, entry.parent.tree.ctx.baseUrl).url }
                .getOrNull() ?: return@forEach
            val dependencies = dependencyClosure(url, externals)
            if (dependencies.none { it in changes }) return@forEach
            val plugin = internal.peek(url).asDynamicPlugin() ?: return@forEach
            if (targets.any { it.oldPlugin === plugin }) return@forEach
            val runtime = context.registry.get(plugin)
            targets += Target(
                plugin,
                url,
                runtime,
                runtime?.fibers?.snapshot().orEmpty().map(::snapshot),
                dependencies,
            )
        }
        if (targets.isEmpty()) {
            context.emitEvent(HmrEvents.Change, changes)
            return true
        }

        val reloadUrls = buildSet {
            addAll(changes)
            targets.forEach { addAll(it.dependencies) }
        }
        synchronized(lock) { accepted = reloadUrls }
        val transaction = internal.beginReload(reloadUrls)
        val attempts = linkedMapOf<String, Plugin<Any?>>()
        try {
            targets.forEach { target ->
                attempts[target.url] = loader.unwrapExports(transaction.import(target.url)).asDynamicPlugin()
                    ?: error("reloaded module ${target.url} does not export a Cordis Plugin")
            }
        } catch (error: Throwable) {
            transaction.rollback()
            synchronized(lock) { stashed += changes }
            if (error is CancellationException) throw error
            handleError(context, error)
            return false
        }

        val installed = mutableListOf<Plugin<Any?>>()
        try {
            targets.forEach { target ->
                if (target.runtime == null) return@forEach
                context.registry.delete(target.oldPlugin)
                val replacement = attempts.getValue(target.url)
                installed += replacement
                target.fibers.forEach { data ->
                    val fiber = data.parent.plugin(replacement, data.config).await()
                    restoreAssociation(fiber, data)
                }
                context.logger().info("reload plugin at %s", PlatformHmr.relative(baseDir, target.url))
            }
        } catch (error: Throwable) {
            transaction.rollback()
            if (error !is CancellationException) context.logger().warn(error)
            withContext(NonCancellable) {
                installed.forEach { plugin ->
                    try {
                        context.registry.delete(plugin)
                    } catch (cleanup: Throwable) {
                        context.logger().warn(cleanup)
                    }
                }
                targets.forEach { target ->
                    target.fibers.forEach { data ->
                        try {
                            val fiber = data.parent.plugin(target.oldPlugin, data.config).await()
                            restoreAssociation(fiber, data)
                        } catch (cleanup: Throwable) {
                            context.logger().warn(cleanup)
                        }
                    }
                }
            }
            synchronized(lock) { stashed += changes }
            if (error is CancellationException) throw error
            return false
        }

        transaction.commit()
        val reloads: Map<Plugin<*>, Reload> = targets.associate { it.oldPlugin to Reload(it.url, it.runtime) }
        context.emitEvent(HmrEvents.Reload, reloads)
        return true
    }

    private data class FiberSnapshot(val parent: Context, val config: Any?, val entry: Entry?)
    private fun snapshot(fiber: Fiber<*>) = FiberSnapshot(
        fiber.parent,
        fiber.config,
        fiber.attributes[Entry.ATTRIBUTE],
    )

    private fun restoreAssociation(fiber: Fiber<Any?>, data: FiberSnapshot) {
        val entry = data.entry ?: return
        fiber.attributes[Entry.ATTRIBUTE] = entry
        fiber.ctx.attributes[Entry.ATTRIBUTE] = entry
        entry.fiber = fiber
    }

    private fun excluded(url: String): Boolean = url.startsWith("node:") || url.contains("/node_modules/")
}

object HmrPlugin : Plugin<HmrConfig> {
    override val name = "hmr"
    override val inject = dependencies(Loader.Key, org.cordis.timer.TimerService.Key)
    override suspend fun apply(ctx: Context, config: HmrConfig, effect: EffectScope) {
        val hmr = Hmr(ctx, config)
        effect.collect { hmr.stop() }
        hmr.start()
    }
}
