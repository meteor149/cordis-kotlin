@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.cordis

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.math.abs

enum class LoggerType { ERROR, INFO, WARN, DEBUG }

enum class LoggerLevel(val value: Int) {
    ERROR(0), WARN(1), INFO(2), DEBUG(3),
}

data class FiberLogMeta(
    val uid: Long?,
    val name: String,
)

data class Message(
    val sn: Long,
    val ts: Long,
    val name: String,
    val type: LoggerType,
    val level: Int,
    val args: List<Any?>,
    val fiber: FiberLogMeta? = null,
    val meta: Map<String, Any?> = emptyMap(),
)

fun interface Formatter {
    fun format(value: Any?, exporter: Exporter, message: Message): Any?
}

interface Exporter {
    val colors: Int? get() = null
    val maxLength: Int get() = 10_240
    val levels: Map<String, Int> get() = emptyMap()
    val formatters: Map<Char, Formatter> get() = emptyMap()
    fun export(message: Message)
}

data class LoggerOptions(
    val name: String,
    val meta: Map<String, Any?> = emptyMap(),
    val level: Int? = null,
    val fiber: FiberLogMeta? = null,
)

data class LoggerConfig(
    val name: String? = null,
    val level: Int? = null,
)

class Logger internal constructor(
    private val options: LoggerOptions,
    private val service: LoggerService,
) {
    val name get() = options.name
    private val levelRef = atomic(options.level)
    var level: Int?
        get() = levelRef.value
        set(value) { levelRef.value = value }

    fun error(vararg args: Any?) = log(LoggerType.ERROR, LoggerLevel.ERROR.value, args.toList())
    fun info(vararg args: Any?) = log(LoggerType.INFO, LoggerLevel.INFO.value, args.toList())
    fun warn(vararg args: Any?) = log(LoggerType.WARN, LoggerLevel.WARN.value, args.toList())
    fun debug(vararg args: Any?) = log(LoggerType.DEBUG, LoggerLevel.DEBUG.value, args.toList())

    private fun log(type: LoggerType, level: Int, original: List<Any?>) =
        log(type, level, original, IdentityMap())

    private fun log(
        type: LoggerType,
        level: Int,
        original: List<Any?>,
        seen: IdentityMap<Throwable, Unit>,
    ) {
        val error = original.singleOrNull() as? Throwable
        if (error != null && !seen.containsKey(error)) {
            seen[error] = Unit
            val cause = error.cause
            if (cause != null && !seen.containsKey(cause)) {
                // Cordis logs the cause first and still emits the wrapping error.
                log(type, level, listOf(cause), seen)
            } else if (cause == null) {
                val nested = error.suppressedExceptions.filterNot(seen::containsKey)
                if (nested.isNotEmpty()) {
                    nested.forEach { log(type, level, listOf(it), seen) }
                    return
                }
            }
        }

        write(type, level, original)
    }

    private fun write(type: LoggerType, severity: Int, original: List<Any?>) {
        val sn = service.nextMessageSerial()
        val ts = Clock.System.now().toEpochMilliseconds()
        service.exportersSnapshot().forEach { exporter ->
            val target = exporter.levels[name]
                ?: exporter.levels["default"]
                ?: level
                ?: LoggerLevel.INFO.value
            if (target >= severity) {
                exporter.export(Message(sn, ts, name, type, severity, original, options.fiber, options.meta))
            }
        }
    }

    companion object {
        private val defaultFormatters = mapOf(
            's' to Formatter { value, _, _ -> value.toString() },
            'd' to Formatter { value, _, _ -> (value as? Number)?.toLong() ?: value.toString().toDouble().toLong() },
            'i' to Formatter { value, _, _ -> (value as? Number)?.toLong() ?: value.toString().toDouble().toLong() },
            'f' to Formatter { value, _, _ -> (value as? Number)?.toDouble() ?: value.toString().toDouble() },
            'o' to Formatter { value, _, _ -> renderObject(value) },
            'O' to Formatter { value, _, _ -> renderObject(value) },
            'c' to Formatter { _, _, _ -> "" },
            'C' to Formatter { value, exporter, message -> color(exporter, code(message.name, exporter.colors), value) },
        )

        fun color(exporter: Exporter, code: Int, value: Any?, decoration: String = ""): String {
            val colors = exporter.colors ?: return value.toString()
            if (colors == 0) return value.toString()
            val prefix = if (code < 8) code.toString() else "8;5;$code"
            val suffix = if (colors >= 2) decoration else ""
            return "\u001b[3$prefix${suffix}m$value\u001b[0m"
        }

        fun code(name: String, level: Int? = null): Int {
            var hash = 0
            name.forEach { char -> hash = ((hash shl 3) - hash) + char.code + 13 }
            val palette = when {
                level == null || level == 0 -> return 0
                level >= 2 -> C256
                else -> C16
            }
            return palette[abs(hash.toLong()).rem(palette.size).toInt()]
        }

        fun format(exporter: Exporter, message: Message): String {
            val args = message.args.toMutableList()
            val firstError = args.firstOrNull() as? Throwable
            if (firstError != null) {
                val error = firstError
                args[0] = error.stackTraceToString().ifBlank { error.message.orEmpty() }
                args.add(0, "%s")
            } else if (args.firstOrNull() !is String) {
                args.add(0, "%o")
            }
            val template = (args.removeFirstOrNull() ?: "").toString()
            var index = 0
            val rendered = Regex("%([a-zA-Z%])").replace(template) { match ->
                val char = match.groupValues[1][0]
                if (char == '%') "%" else {
                    val value = args.getOrNull(index++)
                    (exporter.formatters[char] ?: defaultFormatters[char])?.format(value, exporter, message)?.toString()
                        ?: match.value
                }
            }
            val suffix = args.drop(index).joinToString(separator = "", prefix = if (args.size > index) " " else "") {
                if (it != null && it !is String && it !is Number && it !is Boolean) renderObject(it) else it.toString()
            }
            return (rendered + suffix).lineSequence().joinToString("\n") { line ->
                line.take(exporter.maxLength) + if (line.length > exporter.maxLength) "..." else ""
            }
        }

        private fun renderObject(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"${value.replace("\"", "\\\"")}\""
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "\"${it.key}\":${renderObject(it.value)}" }
            is Iterable<*> -> value.joinToString(",", "[", "]") { renderObject(it) }
            is Array<*> -> value.joinToString(",", "[", "]") { renderObject(it) }
            else -> value.toString()
        }

        val C16 = listOf(6, 2, 3, 4, 5, 1)
        val C256 = listOf(
            20, 21, 26, 27, 32, 33, 38, 39, 40, 41, 42, 43, 44, 45, 56, 57, 62, 63,
            68, 69, 74, 75, 76, 77, 78, 79, 80, 81, 92, 93, 98, 99, 112, 113, 129, 134,
            135, 148, 149, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172,
            173, 178, 179, 184, 185, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206,
            207, 208, 209, 214, 215, 220, 221,
        )
    }
}

class LoggerService private constructor(
    private val bound: Context,
    private val backend: Backend,
) {
    companion object {
        val Intercept = InterceptKey<LoggerConfig>("logger")
    }

    internal constructor(ctx: Context) : this(ctx, Backend())

    var bufferSize: Int
        get() = synchronized(backend) { backend.bufferSize }
        set(value) { synchronized(backend) { backend.bufferSize = value } }
    val buffer: MutableList<Message> get() = backend.buffer

    internal class Backend : SynchronizedObject() {
        var bufferSize: Int = 1000
        val buffer: MutableList<Message> = mutableListOf()
        val messageSerial = atomic(0L)
        val exporterSerial = atomic(0L)
        val exporters = linkedMapOf<Long, Exporter>()

        init {
            exporters[exporterSerial.incrementAndGet()] = object : Exporter {
            override val colors = 3
            override fun export(message: Message) = synchronized(this@Backend) {
                buffer += message
                val overflow = buffer.size - bufferSize
                if (overflow > 0) buffer.subList(0, overflow).clear()
            }
        }
        }
    }

    internal fun bind(ctx: Context): LoggerService = LoggerService(ctx, backend)

    operator fun invoke(name: String? = null, ctx: Context = bound): Logger {
        val rawConfigs = ctx.interceptConfigs.lineageValues(Intercept.name)
        val configuredName = rawConfigs.mapNotNull { config ->
            when (config) {
                is LoggerConfig -> config.name
                is Map<*, *> -> config["name"] as? String
                else -> null
            }
        }.lastOrNull()
        val configuredLevel = rawConfigs.mapNotNull { config ->
            when (config) {
                is LoggerConfig -> config.level
                is Map<*, *> -> (config["level"] as? Number)?.toInt()
                else -> null
            }
        }.lastOrNull()
        val fiber = ctx.fiber
        return Logger(
            LoggerOptions(
                name = name ?: configuredName ?: hyphenate(fiber.name),
                level = configuredLevel,
                fiber = FiberLogMeta(fiber.uid, fiber.name),
            ),
            this,
        )
    }

    fun error(vararg args: Any?) = invoke().error(*args)
    fun info(vararg args: Any?) = invoke().info(*args)
    fun warn(vararg args: Any?) = invoke().warn(*args)
    fun debug(vararg args: Any?) = invoke().debug(*args)

    fun exporter(ctx: Context, exporter: Exporter): EffectHandle = ctx.effect("ctx.logger.exporter()") {
        val id = backend.exporterSerial.incrementAndGet()
        synchronized(backend) { backend.exporters[id] = exporter }
        collect { synchronized(backend) { backend.exporters.remove(id) }; Unit }
    }

    internal fun nextMessageSerial(): Long = backend.messageSerial.incrementAndGet()
    internal fun exportersSnapshot(): List<Exporter> = synchronized(backend) { backend.exporters.values.toList() }

    private fun hyphenate(value: String): String = value
        .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
        .lowercase()
}
