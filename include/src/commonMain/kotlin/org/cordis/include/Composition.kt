package org.cordis.include

import org.cordis.loader.EntryOptions
import org.cordis.loader.FieldPatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A source-owned layer. Order is significant; later layers override earlier ones. */
data class CompositionLayer(val id: String, val patches: List<PatchOptions>)

data class CompositionDiagnostic(val layer: String, val operation: Int, val target: String?, val message: String)

data class CompositionOrigin(val layer: String, val operation: Int)

data class CompositionResult(
    val entries: List<EntryOptions>,
    val diagnostics: List<CompositionDiagnostic>,
    val origins: Map<String, Map<String, CompositionOrigin>>,
) {
    fun requireValid(): CompositionResult {
        require(diagnostics.isEmpty()) { diagnostics.joinToString("\n") { "${it.layer}[${it.operation}]: ${it.message}" } }
        return this
    }
}

/**
 * Detached configuration containers; opaque runtime objects are borrowed, never cloned.
 * Portable callers must supply data values rather than mutable application objects.
 */
internal fun copyCompositionValue(value: Any?): Any? = when (value) {
    is EntryOptions -> copyCompositionEntry(value)
    is JsonElement -> Json.parseToJsonElement(value.toString())
    is Map<*, *> -> value.entries.associate { it.key to copyCompositionValue(it.value) }
    is List<*> -> value.map(::copyCompositionValue)
    is Set<*> -> value.map(::copyCompositionValue).toSet()
    else -> value
}

@Suppress("UNCHECKED_CAST")
internal fun copyCompositionEntry(entry: EntryOptions): EntryOptions = entry.copy(
    config = copyCompositionValue(entry.config),
    inject = copyCompositionValue(entry.inject) as Map<String, Any?>?,
    intercept = copyCompositionValue(entry.intercept) as Map<String, Any?>?,
    isolate = entry.isolate?.toMap(),
    extra = copyCompositionValue(entry.extra) as Map<String, Any?>,
)

/** Pure patch interpretation, shared by previews and Include. IDs are unique within this tree. */
fun composeEntries(base: List<EntryOptions>, layers: List<CompositionLayer>): CompositionResult {
    val output = base.map(::copyCompositionEntry).toMutableList()
    val index = linkedMapOf<String, EntryOptions>()
    val parents = linkedMapOf<String, EntryOptions?>()
    val diagnostics = mutableListOf<CompositionDiagnostic>()
    val origins = linkedMapOf<String, MutableMap<String, CompositionOrigin>>()
    fun report(source: CompositionOrigin, target: String?, message: String) {
        diagnostics += CompositionDiagnostic(source.layer, source.operation, target, message)
    }
    fun children(entry: EntryOptions): List<EntryOptions> =
        if (entry.group == true) (entry.config as? List<*>)?.filterIsInstance<EntryOptions>().orEmpty() else emptyList()
    fun rebuild(source: CompositionOrigin) {
        index.clear()
        parents.clear()
        fun visit(entries: List<EntryOptions>, parent: EntryOptions?) {
            entries.forEach { entry ->
                if (entry.id.isNotBlank()) {
                    if (index.put(entry.id, entry) != null) report(source, entry.id, "duplicate entry id '${entry.id}'")
                    parents[entry.id] = parent
                }
                if (entry.group == true && (entry.config !is List<*> || (entry.config as List<*>).any { it !is EntryOptions })) {
                    report(source, entry.id, "group config must be an entry list")
                }
                visit(children(entry), entry)
            }
        }
        visit(output, null)
    }
    fun insertionIndex(size: Int, position: Int?): Int = when {
        position == null -> size
        position < 0 -> (size + position).coerceAtLeast(0)
        else -> position.coerceAtMost(size)
    }
    fun writeChildren(parent: EntryOptions?, items: List<EntryOptions>, source: CompositionOrigin) {
        if (parent == null) { output.clear(); output.addAll(items) }
        else {
            parent.config = items
            origins.getOrPut(parent.id) { linkedMapOf() }["config"] = source
        }
    }
    fun record(entry: EntryOptions, source: CompositionOrigin) {
        if (entry.id.isNotBlank()) origins[entry.id] = linkedMapOf("entry" to source)
        children(entry).forEach { record(it, source) }
    }
    output.forEach { record(it, CompositionOrigin("base", -1)) }
    rebuild(CompositionOrigin("base", -1))
    layers.forEach { layer ->
        layer.patches.forEachIndexed { position, patch ->
            val source = CompositionOrigin(layer.id, position)
            val inserted = patch.insert
            if (inserted != null) {
                if (patch.parent is FieldPatch.Set || patch.remove) {
                    report(source, patch.id, "insert cannot also move or remove an entry")
                    return@forEachIndexed
                }
                val items = inserted.map(::copyCompositionEntry)
                if (patch.id == null) output.addAll(insertionIndex(output.size, patch.position), items) else {
                    val target = index[patch.id]
                    if (target == null || target.group != true) {
                        report(source, patch.id, if (target == null) "patch insert: entry '${patch.id}' not found" else "patch insert: entry '${patch.id}' is not a group")
                        return@forEachIndexed
                    }
                    val nested = children(target).toMutableList()
                    nested.addAll(insertionIndex(nested.size, patch.position), items)
                    target.config = nested
                    origins.getOrPut(target.id) { linkedMapOf() }["config"] = source
                }
                items.forEach { record(it, source) }
                rebuild(source)
                return@forEachIndexed
            }
            val target = patch.id?.let(index::get)
            if (target == null) {
                report(source, patch.id, if (patch.id == null) "patch: id is required for non-insert patches" else "patch: entry '${patch.id}' not found")
                return@forEachIndexed
            }
            if (patch.name != null && patch.name != target.name) {
                report(source, target.id, "patch: name mismatch for '${target.id}'")
                return@forEachIndexed
            }
            if (patch.remove) {
                if (patch.parent is FieldPatch.Set || patch.position != null) {
                    report(source, target.id, "remove cannot also move an entry")
                    return@forEachIndexed
                }
                fun removeFrom(items: MutableList<EntryOptions>): Boolean {
                    if (items.remove(target)) return true
                    items.forEach { entry ->
                        if (entry.group == true) {
                            val nested = children(entry).toMutableList()
                            if (removeFrom(nested)) {
                                entry.config = nested
                                return true
                            }
                        }
                    }
                    return false
                }
                removeFrom(output)
                rebuild(source)
                return@forEachIndexed
            }
            val fields = origins.getOrPut(target.id) { linkedMapOf() }
            if (patch.parent is FieldPatch.Set || patch.position != null) {
                val previousParent = parents[target.id]
                val parentId = (patch.parent as? FieldPatch.Set)?.value
                val nextParent = if (patch.parent is FieldPatch.Set) parentId?.let(index::get) else previousParent
                if (patch.parent is FieldPatch.Set && parentId != null && nextParent == null) {
                    report(source, target.id, "patch move: parent '$parentId' not found")
                    return@forEachIndexed
                }
                if (nextParent != null && nextParent.group != true) {
                    report(source, target.id, "patch move: parent '${nextParent.id}' is not a group")
                    return@forEachIndexed
                }
                var ancestor = nextParent
                val visited = mutableSetOf<String>()
                while (ancestor != null && ancestor !== target) {
                    if (!visited.add(ancestor.id)) {
                        report(source, target.id, "patch move: invalid parent ancestry")
                        return@forEachIndexed
                    }
                    ancestor = parents[ancestor.id]
                }
                if (ancestor === target) {
                    report(source, target.id, "patch move: an entry cannot contain itself")
                    return@forEachIndexed
                }
                val previousItems = (previousParent?.let(::children) ?: output).toMutableList()
                previousItems.removeAt(previousItems.indexOfFirst { it === target })
                val nextItems = if (previousParent === nextParent) previousItems else
                    (nextParent?.let(::children) ?: output).toMutableList()
                nextItems.add(insertionIndex(nextItems.size, patch.position), target)
                if (previousParent !== nextParent) writeChildren(previousParent, previousItems, source)
                writeChildren(nextParent, nextItems, source)
                if (patch.parent is FieldPatch.Set) fields["parent"] = source
                fields["position"] = source
            }
            patch.replacement?.let {
                if (it.isBlank()) report(source, target.id, "replacement module must not be blank") else {
                    target.name = it
                    fields["name"] = source
                }
            }
            fun <T> apply(field: String, value: FieldPatch<T>, write: (T) -> Unit) {
                if (value is FieldPatch.Set) {
                    write(value.value)
                    fields[field] = source
                }
            }
            apply("config", patch.config) { target.config = copyCompositionValue(it) }
            apply("group", patch.group) { target.group = it }
            apply("disabled", patch.disabled) { target.disabled = it }
            apply("inject", patch.inject) { target.inject = it?.mapValues { item -> copyCompositionValue(item.value) } }
            apply("intercept", patch.intercept) { target.intercept = it?.mapValues { item -> copyCompositionValue(item.value) } }
            apply("isolate", patch.isolate) { target.isolate = it?.toMap() }
            patch.extra.forEach { (key, value) ->
                target.extra = target.extra + (key to copyCompositionValue(value))
                fields["extra.$key"] = source
            }
            rebuild(source)
        }
    }
    return CompositionResult(output, diagnostics.distinct(), origins.filterKeys { it in index }.mapValues { it.value.toMap() })
}
