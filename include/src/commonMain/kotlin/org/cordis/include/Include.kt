package org.cordis.include

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.cordis.Context
import org.cordis.CoreEvents
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.loader.Entry
import org.cordis.loader.EntryOptions
import org.cordis.loader.EntryTree
import org.cordis.loader.FieldPatch
import org.cordis.loader.JsExpr
import org.cordis.loader.IsolationConfig
import org.cordis.loader.IsolationRule
import org.cordis.loader.Loader
import org.cordis.loader.LoaderEvents
import org.cordis.loader.RefreshableEntryTree

data class PatchOptions(
    val id: String? = null,
    val insert: List<EntryOptions>? = null,
    val name: String? = null,
    val config: FieldPatch<Any?> = FieldPatch.Keep,
    val group: FieldPatch<Boolean?> = FieldPatch.Keep,
    val disabled: FieldPatch<Boolean?> = FieldPatch.Keep,
    val inject: FieldPatch<Map<String, Any?>?> = FieldPatch.Keep,
    val intercept: FieldPatch<Map<String, Any?>?> = FieldPatch.Keep,
    val isolate: FieldPatch<IsolationConfig?> = FieldPatch.Keep,
    val extra: Map<String, Any?> = emptyMap(),
)

private inline fun <T> FieldPatch<T>.ifSet(block: (T) -> Unit) {
    if (this is FieldPatch.Set) block(value)
}

/**
 * A relative path is resolved from the owning loader file. `config:foo.yml`
 * uses the host's user configuration directory (AppData, Application Support,
 * XDG_CONFIG_HOME, or their Node equivalents).
 */
data class IncludeConfig(
    val path: String,
    val initial: List<EntryOptions>? = null,
    val patches: List<PatchOptions>? = null,
    val enableLogs: Boolean? = null,
)

class Include(parent: Context, var config: IncludeConfig) : EntryTree(parent), RefreshableEntryTree {
    override val filename: String = PlatformFileSystem.resolve(config.path, ctx.baseUrl)
    private val mediaType: String = when (PlatformFileSystem.extension(filename)) {
        ".json" -> JSON
        ".yaml", ".yml" -> YAML
        else -> throw IllegalArgumentException("extension \"${PlatformFileSystem.extension(filename)}\" not supported")
    }
    private val ioLock = SynchronizedObject()
    private val readonlyRef = atomic(false)
    val readonly: Boolean get() = readonlyRef.value
    private var content: String? = null
    private var data: List<EntryOptions>? = null

    init {
        val inheritedLogs = parent.attributes[Entry.ATTRIBUTE]?.parent?.tree?.enableLogs ?: false
        enableLogs = config.enableLogs ?: inheritedLogs
        ctx.baseUrl = PlatformFileSystem.toFileUrl(PlatformFileSystem.parent(filename))
    }

    suspend fun init() {
        if (!PlatformFileSystem.exists(filename)) {
            val initial = config.initial ?: throw IllegalStateException("config file not found: $filename")
            writeFileNow(initial)
        }
        read(forced = true)
        root.update(applyPatches(synchronized(ioLock) { data.orEmpty().toMutableList() }))
    }

    suspend fun updateConfig(next: IncludeConfig): Boolean {
        if (next.path != synchronized(ioLock) { config.path }) return false
        val current = synchronized(ioLock) {
            config = next
            enableLogs = next.enableLogs ?: enableLogs
            data.orEmpty().toList()
        }
        root.update(current)
        return true
    }

    private fun checkAccess() {
        readonlyRef.value = !PlatformFileSystem.isWritable(filename)
    }

    fun read(forced: Boolean = false): Boolean = synchronized(ioLock) {
        val next = PlatformFileSystem.readUtf8(filename)
        if (!forced && content == next) return@synchronized false
        content = next
        data = decode(next)
        checkAccess()
        true
    }

    fun applyPatches(input: MutableList<EntryOptions>): List<EntryOptions> {
        val patches = synchronized(ioLock) { config.patches.orEmpty() }
        if (patches.isEmpty()) return input
        val entries = linkedMapOf<String, EntryOptions>()
        fun buildMap(items: List<EntryOptions>) {
            items.forEach { entry ->
                if (entry.id.isNotBlank()) entries[entry.id] = entry
                if (entry.group == true) (entry.config as? List<*>)
                    ?.filterIsInstance<EntryOptions>()?.let(::buildMap)
            }
        }
        buildMap(input)

        patches.forEach { patch ->
            val inserted = patch.insert
            if (inserted != null) {
                if (patch.id == null) {
                    input += inserted
                } else {
                    val target = entries[patch.id]
                    if (target == null) {
                        warn("patch insert: entry %s not found", patch.id)
                    } else if (target.group != true) {
                        warn("patch insert: entry %s is not a group", patch.id)
                    } else {
                        val children = (target.config as? List<*>)?.filterIsInstance<EntryOptions>()?.toMutableList()
                            ?: mutableListOf()
                        children += inserted
                        target.config = children
                    }
                }
                return@forEach
            }
            val id = patch.id
            if (id == null) {
                warn("patch: id is required for non-insert patches")
                return@forEach
            }
            val target = entries[id]
            if (target == null) {
                warn("patch: entry %s not found", id)
                return@forEach
            }
            if (patch.name != null && patch.name != target.name) {
                warn("patch: name mismatch for %s (expected %s, got %s), skipping", id, target.name, patch.name)
                return@forEach
            }
            patch.config.ifSet { target.config = it }
            patch.group.ifSet { target.group = it }
            patch.disabled.ifSet { target.disabled = it }
            patch.inject.ifSet { target.inject = it }
            patch.intercept.ifSet { target.intercept = it }
            patch.isolate.ifSet { target.isolate = it }
            if (patch.extra.isNotEmpty()) target.extra = target.extra + patch.extra
        }
        return input
    }

    suspend fun stop() = root.stop()

    override suspend fun refresh() {
        if (read()) root.update(synchronized(ioLock) { data.orEmpty().toList() })
    }

    private fun writeFileNow(entries: List<EntryOptions>) = synchronized(ioLock) {
        if (readonly) throw IllegalStateException("cannot overwrite readonly config")
        val next = encode(entries)
        PlatformFileSystem.writeUtf8Atomic(filename, next)
        content = next
        checkAccess()
    }

    override fun write() {
        ctx.emitEvent(LoaderEvents.ConfigUpdate, Unit)
        // Config files are intentionally small. A synchronous atomic replace
        // prevents the owner from being disposed while a background write is
        // still holding its configuration directory open.
        writeFileNow(root.data.map { it.copy() })
    }

    private fun decode(text: String): List<EntryOptions> {
        val raw = if (mediaType == YAML) yamlToValue(Yaml.default.parseToYamlNode(text))
        else jsonToValue(JSON_FORMAT.parseToJsonElement(text))
        return (raw as? List<*>)?.map { item ->
            toEntry(stringMap(item, "entry"))
        } ?: error("configuration root must be a list")
    }

    private fun encode(entries: List<EntryOptions>): String {
        val value = entries.map(::entryToMap)
        return if (mediaType == YAML) encodeYaml(value) + "\n"
        else JSON_FORMAT.encodeToString(JsonElement.serializer(), valueToJson(value)) + "\n"
    }

    private fun warn(format: String, vararg args: Any?) = ctx.logger("loader").warn(format, *args)

    companion object {
        private const val JSON = "application/json"
        private const val YAML = "application/yaml"
        private val JSON_FORMAT = Json { prettyPrint = true }

        private fun entryToMap(entry: EntryOptions): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            putAll(entry.extra)
            put("id", entry.id)
            put("name", entry.name)
            entry.config?.let { put("config", it) }
            entry.group?.let { put("group", it) }
            entry.disabled?.let { put("disabled", it) }
            entry.inject?.let { put("inject", it) }
            entry.intercept?.let { put("intercept", it) }
            entry.isolate?.let { isolate ->
                put("isolate", isolate.mapValues { (_, rule) ->
                    when (rule) {
                        IsolationRule.Local -> true
                        is IsolationRule.Shared -> rule.realm
                    }
                })
            }
        }

        private fun valueToJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is JsExpr -> JsonObject(mapOf("__jsExpr" to JsonPrimitive(value.__jsExpr)))
            is EntryOptions -> valueToJson(entryToMap(value))
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to valueToJson(it.value) })
            is Iterable<*> -> JsonArray(value.map(::valueToJson))
            is Array<*> -> JsonArray(value.map(::valueToJson))
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

        private fun jsonToValue(value: JsonElement): Any? = when (value) {
            JsonNull -> null
            is JsonArray -> value.map(::jsonToValue)
            is JsonObject -> value.entries.associate { it.key to jsonToValue(it.value) }
            is JsonPrimitive -> if (value.isString) value.content else
                value.booleanOrNull ?: value.longOrNull?.narrow() ?: value.doubleOrNull ?: value.contentOrNull
        }

        private fun yamlToValue(value: YamlNode): Any? = when (value) {
            is YamlNull -> null
            is YamlList -> value.items.map(::yamlToValue)
            is YamlMap -> value.entries.entries.associate { it.key.content to yamlToValue(it.value) }
            is YamlTaggedNode -> if (value.tag.removePrefix("!") == "js") JsExpr((yamlToValue(value.innerNode) ?: "").toString())
                else yamlToValue(value.innerNode)
            is YamlScalar -> value.content.toBooleanStrictOrNull()
                ?: value.content.toLongOrNull()?.narrow()
                ?: value.content.toDoubleOrNull()
                ?: value.content
        }

        private fun Long.narrow(): Number = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else this

        private fun encodeYaml(value: Any?, indent: Int = 0): String {
            val padding = " ".repeat(indent)
            return when (value) {
                is Map<*, *> -> if (value.isEmpty()) "$padding{}" else value.entries.joinToString("\n") { (key, item) ->
                    if (yamlInline(item)) "$padding${key}: ${yamlScalar(item)}"
                    else "$padding${key}:\n${encodeYaml(item, indent + 2)}"
                }
                is Iterable<*> -> {
                    val items = value.toList()
                    if (items.isEmpty()) "$padding[]" else items.joinToString("\n") { item ->
                        if (yamlInline(item)) "$padding- ${yamlScalar(item)}"
                        else "$padding-\n${encodeYaml(item, indent + 2)}"
                    }
                }
                is Array<*> -> encodeYaml(value.asList(), indent)
                else -> "$padding${yamlScalar(value)}"
            }
        }

        private fun yamlInline(value: Any?): Boolean = value !is Map<*, *> && value !is Iterable<*> && value !is Array<*> ||
            value is Map<*, *> && value.isEmpty() || value is Iterable<*> && !value.iterator().hasNext() ||
            value is Array<*> && value.isEmpty()

        private fun yamlScalar(value: Any?): String = when (value) {
            null -> "null"
            is JsExpr -> "!js ${quoteYaml(value.__jsExpr)}"
            is Boolean, is Number -> value.toString()
            is Map<*, *> -> "{}"
            is Iterable<*>, is Array<*> -> "[]"
            else -> quoteYaml(value.toString())
        }

        private fun quoteYaml(value: String): String = buildString {
            append('"')
            value.forEach { char -> when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            } }
            append('"')
        }

        private fun toEntry(map: Map<String, Any?>): EntryOptions = EntryOptions(
            id = map["id"]?.toString().orEmpty(),
            name = map["name"]?.toString() ?: error("entry name is required"),
            config = normalize(map["config"]),
            group = map["group"] as? Boolean,
            disabled = map["disabled"] as? Boolean,
            inject = map["inject"]?.let { stringMap(it, "inject") },
            intercept = map["intercept"]?.let { stringMap(it, "intercept") },
            isolate = parseIsolation(map["isolate"]),
            extra = map - setOf("id", "name", "config", "group", "disabled", "inject", "intercept", "isolate"),
        )

        private fun parseIsolation(value: Any?): IsolationConfig? {
            val map = value as? Map<*, *> ?: return null
            return map.mapNotNull { (key, raw) ->
                val rule = when (raw) {
                    true -> IsolationRule.Local
                    is String -> IsolationRule.Shared(raw)
                    else -> return@mapNotNull null
                }
                key.toString() to rule
            }.toMap()
        }

        private fun normalize(value: Any?): Any? = when (value) {
            is JsExpr -> value
            is Map<*, *> -> if (value.keys == setOf("__jsExpr")) JsExpr(value["__jsExpr"].toString())
                else value.entries.associate { it.key.toString() to normalize(it.value) }
            is List<*> -> if (value.all { it is Map<*, *> && it.containsKey("name") })
                value.map { toEntry(stringMap(it, "entry")) }
                else value.map(::normalize)
            else -> value
        }

        private fun stringMap(value: Any?, label: String): Map<String, Any?> {
            val map = value as? Map<*, *> ?: error("$label must be an object")
            return map.entries.associate { (key, item) ->
                val name = key as? String ?: error("$label keys must be strings")
                name to item
            }
        }
    }
}

object IncludePlugin : Plugin<IncludeConfig> {
    override val name = "include"
    override val inject = dependencies(Loader.Key)
    override suspend fun apply(ctx: Context, config: IncludeConfig, effect: EffectScope) {
        val include = Include(ctx, config)
        effect.collect { include.root.stop() }
        ctx.interceptEventAsync(CoreEvents.Update) { event, next ->
            val nextConfig = event.payload.config as? IncludeConfig
                ?: error("include update config must be IncludeConfig")
            if (include.updateConfig(nextConfig)) null else next()
        }
        include.init()
    }
}
