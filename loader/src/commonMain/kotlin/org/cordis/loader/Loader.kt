package org.cordis.loader

import kotlinx.datetime.Clock
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.CoreEvents
import org.cordis.EventKey
import org.cordis.EventOptions
import org.cordis.Fiber
import org.cordis.InterceptKey
import org.cordis.Plugin
import org.cordis.ServiceKey
import org.cordis.ServiceReference

data class LoaderConfig(val baseUrl: String? = null)
data class LoaderIntercept(val await: Boolean = false)

data class PartialDispose(
    val entry: Entry,
    val previous: EntryOptions,
    val updating: Boolean,
)

object LoaderEvents {
    val ConfigUpdate = EventKey<Unit, Unit>("loader/config-update")
    val PartialDispose = EventKey<org.cordis.loader.PartialDispose, Unit>("loader/partial-dispose")
    val Exit = EventKey<String, Unit>("exit")
}

open class Loader(ctx: Context, val config: LoaderConfig = LoaderConfig()) : EntryTree(ctx) {
    val envData: MutableMap<String, Any?> = mutableMapOf("startTime" to Clock.System.now().toEpochMilliseconds())
    val name = "loader"
    var internal: ModuleLoader? = ModuleLoader.fromInternal()
    val builtins: MutableMap<String, Any?> = mutableMapOf()
    private val realms = mutableMapOf<String, GlobalRealm>()
    var exitRequested: Boolean = false
        private set

    init {
        config.baseUrl?.let { this.ctx.baseUrl = it }
        this.ctx.provide(Key, this) { target ->
            var awaitTasks = false
            target.configuredInterceptValues(ServiceReference(Intercept.name)).forEach { value ->
                when (value) {
                    is LoaderIntercept -> awaitTasks = value.await
                    is Map<*, *> -> if (value.containsKey("await")) awaitTasks = value["await"] == true
                }
            }
            !awaitTasks || getTasks().isEmpty()
        }

        // Persist a root entry's self-update before its local update middleware
        // gets a chance to stop the waterfall. Child plugins inherit the same
        // Entry marker but must not write the entry's root configuration.
        this.ctx.interceptEvent(CoreEvents.Update, EventOptions(global = true, prepend = true)) { event, next ->
            val update = event.payload
            val fiber = update.fiber
            val entry = fiber.attributes[Entry.ATTRIBUTE] ?: return@interceptEvent next()
            if (update.noSave || fiber.parent.fiber.attributes[Entry.ATTRIBUTE] === entry) {
                return@interceptEvent next()
            }
            entry.options.config = update.config
            entry.parent.tree.write()
            next()
        }

        this.ctx.interceptEvent(CoreEvents.Update, EventOptions(global = true)) { event, next ->
            val fiber = event.payload.fiber
            val entry = fiber.attributes[Entry.ATTRIBUTE] ?: return@interceptEvent next()
            if (fiber.parent.fiber.attributes[Entry.ATTRIBUTE] !== entry) showLog(entry, "reload")
            next()
        }

        this.ctx.listen(CoreEvents.Plugin, EventOptions(global = true)) { event ->
            val fiber = event.payload
            val entry = fiber.attributes[Entry.ATTRIBUTE] ?: return@listen Unit

            // Creation, untracked plugins, nested child plugins, registry/HMR
            // deletion, tree disposal, and loader-driven disable are excluded
            // in the same order as the upstream Loader.
            if (fiber.uid != null) return@listen Unit
            if (fiber.parent.fiber.attributes[Entry.ATTRIBUTE] === entry) return@listen Unit
            if (!fiber.isRuntimeRegistered()) return@listen Unit
            if (entry.parent.tree.ctx.fiber.uid == null) return@listen Unit
            showLog(entry, "unload")
            if (entry.disabled) return@listen Unit
            entry.options.disabled = true
            entry.parent.tree.write()
        }
    }

    override fun write() = Unit
    fun showLog(entry: Entry, type: String) {
        if (entry.options.group == true || !entry.parent.tree.enableLogs) return
        ctx.logger("loader").info("%s plugin %s", type, entry.options.name)
    }
    fun locate(fiber: Fiber<*> = ctx.fiber): String? {
        var current = fiber
        while (true) {
            current.attributes[Entry.ATTRIBUTE]?.let { return it.id }
            val next = current.parent.fiber
            if (next === current) return null
            current = next
        }
    }
    fun exit() {
        exitRequested = true
        ctx.emitEvent(LoaderEvents.Exit, "HMR")
    }
    fun unwrapExports(exports: Any?): Any? = exports
    internal fun globalRealm(label: String) = realms.getOrPut(label) { GlobalRealm(label) }

    internal fun releaseIsolation(previous: IsolationConfig?) {
        previous.orEmpty().forEach { (name, rule) ->
            val shared = rule as? IsolationRule.Shared ?: return@forEach
            val realm = realms[shared.realm] ?: return@forEach
            val stillReferenced = entries().any { entry ->
                entry.options.isolate?.get(name) == shared
            }
            if (stillReferenced) return@forEach
            realm.delete(name)
            if (realm.size == 0) realms.remove(shared.realm)
        }
    }

    companion object {
        val Key = ServiceKey<Loader>("loader")
        val Intercept = InterceptKey<LoaderIntercept>("loader")
    }
}

object LoaderPlugin : Plugin<LoaderConfig> {
    override val name = "loader"
    override suspend fun apply(ctx: Context, config: LoaderConfig, effect: EffectScope) {
        Loader(ctx, config)
    }
}
