package org.cordis.loader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cordis.asDynamicPlugin

/** Copies data containers, borrowing opaque runtime configuration objects. */
fun detachedEntryOptions(entry: EntryOptions): EntryOptions {
    fun value(input: Any?): Any? = when (input) {
        is EntryOptions -> detachedEntryOptions(input)
        is List<*> -> input.map(::value)
        is Map<*, *> -> input.entries.associate { it.key to value(it.value) }
        is Set<*> -> input.map(::value).toSet()
        else -> input
    }
    return entry.copy(
        config = value(entry.config),
        inject = entry.inject?.mapValues { value(it.value) },
        intercept = entry.intercept?.mapValues { value(it.value) },
        isolate = entry.isolate?.toMap(),
        extra = entry.extra.mapValues { value(it.value) },
    )
}

/**
 * Applies a whole tree and lets the host publish its durable commit while rollback remains
 * possible. The callback is the last fallible step: it must be atomic and must not recursively
 * mutate this Loader. A successful callback commits; provider side effects are not reversible.
 */
suspend fun <T> EntryTree.withTreeTransaction(
    candidate: List<EntryOptions>,
    publish: suspend () -> T,
): T = loaderForTransaction().withMutation {
    val loader = loaderForTransaction()
    check(!loader.transactionActive) { "nested tree transactions are not supported" }
    val next = candidate.map(::detachedEntryOptions)
    val ids = mutableSetOf<String>()
    suspend fun prepare(items: List<EntryOptions>, ancestorDisabled: Boolean = false) {
        items.forEach { item ->
            ensureId(item)
            require(ids.add(item.id)) { "duplicate entry id '${item.id}'" }
            require(item.name.isNotBlank()) { "entry module must not be blank" }
            val disabled = ancestorDisabled || item.disabled == true
            if (!disabled || item.group == true) {
                require(loader.unwrapExports(import(item.name)).asDynamicPlugin() != null) {
                    "module ${item.name} does not export a Cordis Plugin"
                }
            }
            if (item.group == true) {
                val children = item.config as? List<*> ?: error("group config must be an entry list")
                prepare(children.map { it as? EntryOptions ?: error("group child must be an entry") }, disabled)
            }
        }
    }
    prepare(next)
    val previous = root.data.map(::detachedEntryOptions)
    fun parentIndex(items: List<EntryOptions>): Map<String, String?> {
        val parents = linkedMapOf<String, String?>()
        fun visit(entries: List<EntryOptions>, parent: String?) {
            entries.forEach { entry ->
                parents[entry.id] = parent
                if (entry.group == true) visit((entry.config as List<*>).map { it as EntryOptions }, entry.id)
            }
        }
        visit(items, null)
        return parents
    }
    val previousParents = parentIndex(previous)
    val nextParents = parentIndex(next)
    loader.transactionActive = true
    try {
        // Retire moved branches before either group updates. Otherwise a later source update
        // can remove the destination's entry with the same ID. Reallocation owns the new realm;
        // a same-parent reorder retains the existing effects and resources.
        previousParents.forEach { (id, parent) ->
            if (id in nextParents && nextParents[id] != parent) {
                store[id]?.let { entry -> entry.parent.remove(entry.options.id) }
            }
        }
        root.update(next)
        await()
        entries().forEach {
            it.fiber?.await()
            check(it.disabled || it.fiber?.uid != null) { "enabled entry '${it.id}' disposed itself during application" }
        }
        currentCoroutineContext().ensureActive()
        // Once durable publication starts, cancellation must not restore the old tree
        // after the host has already atomically committed the new generation.
        withContext(NonCancellable) { publish() }
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            // Include partial creations too, not only entries in the old root list.
            root.data.toList().asReversed().forEach { item ->
                runCatching { root.remove(item.id, true) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            root.data = previous.toMutableList()
            previous.forEach { item ->
                runCatching { root.create(item) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            runCatching {
                await()
                entries().forEach { it.fiber?.await() }
            }.exceptionOrNull()?.let(error::addSuppressed)
        }
        throw error
    } finally {
        loader.transactionActive = false
    }
}

suspend fun EntryTree.applyTree(candidate: List<EntryOptions>) = withTreeTransaction(candidate) { Unit }

private fun EntryTree.loaderForTransaction(): Loader = ctx[Loader.Key] ?: (this as? Loader ?: error("loader unavailable"))
