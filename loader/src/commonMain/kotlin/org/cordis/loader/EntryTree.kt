package org.cordis.loader

import kotlinx.coroutines.joinAll
import org.cordis.Context
import org.cordis.Plugin
import kotlin.random.Random

abstract class EntryTree(parent: Context) {
    val ctx: Context = parent.extend { baseUrl = parent.baseUrl }
    var enableLogs: Boolean = false
    val root = EntryGroup(ctx, this)
    val store: MutableMap<String, Entry> = linkedMapOf()

    init {
        ctx.attributes[Entry.ATTRIBUTE]?.subtree = this
    }

    fun entries(): Sequence<Entry> = sequence {
        store.values.forEach { entry ->
            yield(entry)
            entry.subtree?.let { yieldAll(it.entries()) }
        }
    }

    fun getTasks() = entries().mapNotNull { it.initTask ?: it.fiber?.inertia }.toList()

    suspend fun await() {
        while (true) {
            val tasks = getTasks()
            if (tasks.isEmpty()) return
            tasks.joinAll()
        }
    }

    fun ensureId(options: EntryOptions): String {
        if (options.id.isBlank()) do {
            options.id = Random.nextBytes(4).joinToString("") { byte ->
                byte.toUByte().toString(16).padStart(2, '0')
            }
        } while (store.containsKey(options.id))
        return options.id
    }

    fun resolve(id: String): Entry {
        val parts = id.split(SEP).toMutableList()
        val final = parts.removeLast()
        var tree: EntryTree = this
        parts.forEach { part -> tree = tree.store[part]?.subtree ?: error("cannot resolve entry $id") }
        return tree.store[final] ?: error("cannot resolve entry $id")
    }

    fun resolveGroup(id: String?): EntryGroup = if (id == null) root
        else resolve(id).subgroup ?: error("entry $id is not a group")

    suspend fun create(options: EntryOptions, parent: String? = null, position: Int = Int.MAX_VALUE): String {
        val group = resolveGroup(parent)
        group.data.add(insertionIndex(group.data.size, position), options)
        group.tree.write()
        return group.create(options)
    }

    suspend fun remove(id: String) {
        val entry = resolve(id)
        entry.parent.remove(entry.options.id)
        entry.parent.tree.write()
    }

    suspend fun update(id: String, options: EntryPatch) {
        updateInternal(id, options, FieldPatch.Keep, null)
    }

    suspend fun update(id: String, options: EntryPatch, parent: String?, position: Int? = null) {
        updateInternal(id, options, changeTo(parent), position)
    }

    private suspend fun updateInternal(
        id: String,
        options: EntryPatch,
        parent: FieldPatch<String?>,
        position: Int?,
    ) {
        val entry = resolve(id)
        val source = entry.parent
        if (parent is FieldPatch.Set) {
            val target = resolveGroup(parent.value)
            source.unlink(entry.options)
            target.data.add(insertionIndex(target.data.size, position ?: target.data.size), entry.options)
            target.tree.write()
            entry.parent = target
            entry.rebase(target.ctx)
        }
        source.tree.write()
        entry.update(options, force = true)
    }

    suspend fun import(name: String): Any? {
        if (name.startsWith("cordis:")) return loader().builtins[name.removePrefix("cordis:")]
        return loader().internal?.import(name, ctx.baseUrl)
            ?: ClasspathModuleLoader().import(name, ctx.baseUrl)
    }

    private fun loader(): Loader = ctx[Loader.Key] ?: (this as? Loader ?: error("loader unavailable"))
    abstract fun write()

    private fun insertionIndex(size: Int, position: Int): Int =
        if (position < 0) (size + position).coerceAtLeast(0) else position.coerceAtMost(size)

    companion object { const val SEP = ":" }
}
