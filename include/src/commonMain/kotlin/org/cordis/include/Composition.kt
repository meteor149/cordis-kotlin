package org.cordis.include

import org.cordis.loader.EntryOptions
import org.cordis.loader.FieldPatch

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
    val diagnostics = mutableListOf<CompositionDiagnostic>()
    val origins = linkedMapOf<String, MutableMap<String, CompositionOrigin>>()
    fun report(source: CompositionOrigin, target: String?, message: String) {
        diagnostics += CompositionDiagnostic(source.layer, source.operation, target, message)
    }
    fun children(entry: EntryOptions): List<EntryOptions> =
        if (entry.group == true) (entry.config as? List<*>)?.filterIsInstance<EntryOptions>().orEmpty() else emptyList()
    fun rebuild(source: CompositionOrigin) {
        index.clear()
        fun visit(entries: List<EntryOptions>) {
            entries.forEach { entry ->
                if (entry.id.isNotBlank()) {
                    if (index.put(entry.id, entry) != null) report(source, entry.id, "duplicate entry id '${entry.id}'")
                }
                if (entry.group == true && (entry.config !is List<*> || (entry.config as List<*>).any { it !is EntryOptions })) {
                    report(source, entry.id, "group config must be an entry list")
                }
                visit(children(entry))
            }
        }
        visit(output)
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
                val items = inserted.map(::copyCompositionEntry)
                if (patch.id == null) output += items else {
                    val target = index[patch.id]
                    if (target == null || target.group != true) {
                        report(source, patch.id, if (target == null) "patch insert: entry '${patch.id}' not found" else "patch insert: entry '${patch.id}' is not a group")
                        return@forEachIndexed
                    }
                    target.config = children(target) + items
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
