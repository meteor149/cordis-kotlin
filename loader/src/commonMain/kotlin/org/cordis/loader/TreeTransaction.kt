package org.cordis.loader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cordis.asDynamicPlugin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A candidate failed and its previous tree could not be safely restored. */
class TreeRestorationException(
    transactionFailure: Throwable,
    restorationFailures: List<Throwable>,
) : IllegalStateException("Tree transaction failed and restoration is incomplete", transactionFailure) {
    init {
        restorationFailures.filter { it !== this }.forEach(::addSuppressed)
    }
}

/** Copies data containers, borrowing opaque runtime configuration objects. */
fun detachedEntryOptions(entry: EntryOptions): EntryOptions {
    fun value(input: Any?): Any? = when (input) {
        is EntryOptions -> detachedEntryOptions(input)
        is JsonElement -> Json.parseToJsonElement(input.toString())
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
        val restorationFailures = mutableListOf<Throwable>()
        suspend fun restore(action: suspend () -> Unit) {
            try { action() } catch (failure: Throwable) { restorationFailures += failure }
        }
        withContext(NonCancellable) {
            // Include partial creations too, not only entries in the old root list.
            store.values.toList().asReversed().forEach { entry ->
                restore { entry.parent.remove(entry.options.id, true) }
            }
            root.data = previous.toMutableList()
            // Uncertain retirement must not overlap restored allocations. Keep the
            // previous recipe for diagnostics, but let the host recover the owner.
            if (restorationFailures.isEmpty()) {
                previous.forEach { item ->
                    restore { root.create(item) }
                }
                restore {
                    await()
                    entries().forEach { it.fiber?.await() }
                }
            }
        }
        if (restorationFailures.isNotEmpty()) throw TreeRestorationException(error, restorationFailures)
        throw error
    } finally {
        loader.transactionActive = false
    }
}

suspend fun EntryTree.applyTree(candidate: List<EntryOptions>) = withTreeTransaction(candidate) { Unit }

private fun EntryTree.loaderForTransaction(): Loader = ctx[Loader.Key] ?: (this as? Loader ?: error("loader unavailable"))
