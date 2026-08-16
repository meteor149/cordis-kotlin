package org.cordis.loader

import kotlinx.coroutines.CancellationException
import org.cordis.Context

open class EntryGroup(val ctx: Context, val tree: EntryTree) {
    var data: MutableList<EntryOptions> = mutableListOf()

    init { ctx.attributes[Entry.ATTRIBUTE]?.subgroup = this }
    val context get() = ctx

    suspend fun create(options: EntryOptions): String {
        val id = tree.ensureId(options)
        val entry = tree.store.getOrPut(id) { Entry(loader()) }
        entry.parent = this
        entry.rebase(ctx)
        entry.update(options, create = true, force = true)
        return entry.id
    }

    fun unlink(options: EntryOptions) { data.remove(options) }

    suspend fun remove(id: String, isDispose: Boolean = false) {
        val entry = tree.store[id] ?: return
        entry.fiber?.dispose()
        entry.fiber = null
        if (!isDispose) unlink(entry.options)
        tree.store.remove(id)
        entry.loader.releaseIsolation(entry.options.isolate)
        ctx.emitEvent(LoaderEvents.PartialDispose, PartialDispose(entry, entry.options, false))
    }

    suspend fun update(config: List<EntryOptions>) {
        val oldIds = data.map { it.id }.toSet()
        data = config.toMutableList()
        config.forEach { options ->
            try {
                create(options)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ctx.logger().error(error)
            }
        }
        (oldIds - config.map { it.id }.toSet()).forEach { remove(it) }
    }

    suspend fun stop() { data.toList().forEach { remove(it.id, true) } }

    private fun loader(): Loader = ctx[Loader.Key] ?: tree as Loader
}

class Group(ctx: Context, config: List<EntryOptions>) : EntryGroup(
    ctx,
    checkNotNull(ctx.attributes[Entry.ATTRIBUTE]).parent.tree,
) {
    init { data = config.toMutableList() }
}

object GroupPlugin : org.cordis.Plugin<List<EntryOptions>> {
    override val name = "group"
    override suspend fun apply(ctx: Context, config: List<EntryOptions>, effect: org.cordis.EffectScope) {
        val entry = checkNotNull(ctx.attributes[Entry.ATTRIBUTE])
        val group = EntryGroup(ctx, entry.parent.tree)
        effect.collect { group.stop() }
        group.update(config)
        // JavaScript arrays are shared by identity between plugin config and
        // EntryGroup.data. Rebind immutable Kotlin Lists to the mutable runtime
        // list so later loader.create(..., parent) persists across reloads.
        entry.options.config = group.data
        ctx.interceptEventAsync(org.cordis.CoreEvents.Update) { event, _ ->
            val items = event.payload.config as? List<*>
                ?: error("group update config must be a list")
            group.update(items.mapIndexed { index, item ->
                item as? EntryOptions ?: error("group update item $index must be EntryOptions")
            })
            // Do not call the appended next callback: Group coordinates children
            // in place instead of restarting its own Fiber.
            null
        }
    }
}
