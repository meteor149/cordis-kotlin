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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.cordis.loader.withTreeTransaction

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
    val remove: Boolean = false,
    val replacement: String? = null,
    /** Keep retains the current parent; Set(null) moves an entry to the root. */
    val parent: FieldPatch<String?> = FieldPatch.Keep,
    /** Index after removal; negative values count from the end, and out-of-range values clamp. */
    val position: Int? = null,
)

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
    val layered: Boolean = false,
)

class Include(parent: Context, var config: IncludeConfig) : EntryTree(parent), RefreshableEntryTree {
    override val filename: String = PlatformFileSystem.resolve(config.path, ctx.baseUrl)
    private val mediaType: String = when (PlatformFileSystem.extension(filename)) {
        ".json" -> JSON
        ".yaml", ".yml" -> YAML
        else -> throw IllegalArgumentException("extension \"${PlatformFileSystem.extension(filename)}\" not supported")
    }
    private val ioLock = SynchronizedObject()
    private val updateLock = Mutex()
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
        refreshCandidate(forced = true)
    }

    suspend fun updateConfig(next: IncludeConfig): Boolean = updateLock.withLock {
        if (next.path != synchronized(ioLock) { config.path }) return@withLock false
        val current = synchronized(ioLock) { data.orEmpty().toList() }
        val composed = interpret(current, next.patches.orEmpty())
        withTreeTransaction(composed) {
            synchronized(ioLock) {
                config = next
                enableLogs = next.enableLogs ?: enableLogs
            }
        }
        true
    }

    private fun checkAccess() {
        readonlyRef.value = !PlatformFileSystem.isWritable(filename)
    }

    fun read(forced: Boolean = false): Boolean = synchronized(ioLock) {
        val next = PlatformFileSystem.readUtf8(filename)
        if (!forced && content == next) return@synchronized false
        // Read/parse preflight only. Committed state changes after tree application.
        decode(next)
        true
    }

    fun applyPatches(input: MutableList<EntryOptions>): List<EntryOptions> {
        val patches = synchronized(ioLock) { config.patches.orEmpty() }
        return interpret(input, patches)
    }

    private fun interpret(input: List<EntryOptions>, patches: List<PatchOptions>): List<EntryOptions> {
        val result = composeEntries(input, listOf(CompositionLayer(filename, patches)))
        result.diagnostics.forEach { warn(it.message) }
        return result.entries
    }
    suspend fun stop() = root.stop()

    override suspend fun refresh() = refreshCandidate(forced = false)

    private suspend fun refreshCandidate(forced: Boolean) = updateLock.withLock {
        val next = PlatformFileSystem.readUtf8(filename)
        if (!forced && synchronized(ioLock) { content == next }) return@withLock
        val parsed = decode(next)
        val patches = synchronized(ioLock) { config.patches.orEmpty() }
        withTreeTransaction(interpret(parsed, patches)) {
            synchronized(ioLock) {
                content = next
                data = parsed
                checkAccess()
            }
        }
    }

    private fun writeFileNow(entries: List<EntryOptions>) = synchronized(ioLock) {
        if (readonly) throw IllegalStateException("cannot overwrite readonly config")
        val next = encode(entries)
        PlatformFileSystem.writeUtf8Atomic(filename, next)
        content = next
        checkAccess()
    }

    override fun write() {
        if (config.layered || ctx[Loader.Key]?.transactionActive == true) return
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
