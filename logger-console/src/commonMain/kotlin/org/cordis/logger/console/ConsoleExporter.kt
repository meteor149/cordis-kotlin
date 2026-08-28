@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.cordis.logger.console

import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.cordis.Context
import org.cordis.Exporter
import org.cordis.Formatter
import org.cordis.Logger
import org.cordis.Message

data class LabelStyle(
    val width: Int? = null,
    val margin: Int? = null,
    val align: Align? = null,
) {
    enum class Align { LEFT, RIGHT }
}

data class ConsoleExporterConfig(
    val colors: Int = 0,
    val maxLength: Int = 10_240,
    val levels: Map<String, Int> = emptyMap(),
    val showDiff: Boolean = false,
    val showTime: String = "yyyy-MM-dd hh:mm:ss ",
    val label: LabelStyle? = null,
)

open class ConsoleExporter(
    val ctx: Context,
    config: ConsoleExporterConfig = ConsoleExporterConfig(),
) : Exporter {
    override var colors: Int? = config.colors
    override var maxLength: Int = config.maxLength
    override var levels: Map<String, Int> = config.levels
    var showDiff: Boolean = config.showDiff
    var showTime: String = config.showTime
    var label: LabelStyle? = config.label
    var timestamp: Long = Clock.System.now().toEpochMilliseconds()
    override val formatters: MutableMap<Char, Formatter> = mutableMapOf(
        'o' to Formatter { value, _, _ -> inspect(value) },
        'O' to Formatter { value, _, _ -> inspect(value) },
    )

    init {
        ctx.logger.exporter(ctx, this)
    }

    override fun export(message: Message) {
        kotlin.io.println(render(message))
    }

    fun render(message: Message): String {
        val prefix = "[${message.type.name.first()}]"
        val space = " ".repeat(label?.margin ?: 1)
        var indent = 3 + space.length
        val output = StringBuilder()
        if (showTime.isNotEmpty()) {
            val renderedTime = renderTime(message.ts, showTime)
            indent += renderedTime.length
            output.append(Logger.color(this, 8, renderedTime))
        }
        val code = Logger.code(message.name, colors)
        val renderedLabel = Logger.color(this, code, message.name, ";1")
        val padLength = (label?.width ?: 0) + renderedLabel.length - message.name.length
        if (label?.align == LabelStyle.Align.RIGHT) {
            output.append(renderedLabel.padStart(padLength)).append(space).append(prefix).append(space)
            indent += (label?.width ?: 0) + space.length
        } else {
            output.append(prefix).append(space).append(renderedLabel.padEnd(padLength)).append(space)
        }
        output.append(Logger.format(this, message).replace("\n", "\n" + " ".repeat(indent)))
        if (showDiff && timestamp != 0L) {
            output.append(Logger.color(this, code, " +${formatDuration(message.ts - timestamp)}"))
        }
        timestamp = message.ts
        return output.toString()
    }

    companion object {
        const val NAME = "logger-console"

        private fun renderTime(epochMillis: Long, pattern: String): String = runCatching {
            val value = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
            pattern
                .replace("yyyy", value.year.toString().padStart(4, '0'))
                .replace("MM", value.monthNumber.toString().padStart(2, '0'))
                .replace("dd", value.dayOfMonth.toString().padStart(2, '0'))
                .replace("HH", value.hour.toString().padStart(2, '0'))
                .replace("hh", value.hour.toString().padStart(2, '0'))
                .replace("mm", value.minute.toString().padStart(2, '0'))
                .replace("ss", value.second.toString().padStart(2, '0'))
        }.getOrElse { pattern }

        private fun formatDuration(millis: Long): String = when {
            millis < 1_000 -> "${millis}ms"
            millis < 60_000 -> "${millis / 1_000.0}s"
            else -> "${millis / 60_000.0}m"
        }

        private fun inspect(value: Any?): String = when (value) {
            null -> "null"
            is String -> "'${value.replace("'", "\\'")}'"
            is Map<*, *> -> value.entries.joinToString(", ", "{ ", " }") { "${it.key}: ${inspect(it.value)}" }
            is Iterable<*> -> value.joinToString(", ", "[ ", " ]") { inspect(it) }
            else -> value.toString()
        }
    }
}

/** Browser-oriented exporter using console severity prefixes. */
class BrowserConsoleExporter(ctx: Context, config: ConsoleExporterConfig = ConsoleExporterConfig()) :
    ConsoleExporter(ctx, config) {
    override fun export(message: Message) {
        val prefix = "[${message.type.name.first()}] ${message.name}"
        val body = message.args.joinToString(" ")
        val line = "$prefix $body"
        println(line)
    }
}
