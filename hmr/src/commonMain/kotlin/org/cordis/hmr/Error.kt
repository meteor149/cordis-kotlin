package org.cordis.hmr

import org.cordis.Context

data class BuildLocation(val file: String, val line: Int, val column: Int)
data class BuildMessage(val text: String, val location: BuildLocation? = null)
class BuildFailure(val errors: List<BuildMessage>) : RuntimeException(errors.joinToString { it.text })

fun handleError(ctx: Context, error: Throwable) {
    if (error !is BuildFailure) {
        ctx.logger().warn(error)
        return
    }
    error.errors.forEach { message ->
        val location = message.location
        if (location == null) {
            ctx.logger().warn(message.text)
            return@forEach
        }
        try {
            val lines = PlatformHmr.readLines(location.file)
            val source = lines.getOrNull(location.line - 1).orEmpty()
            val caret = " ".repeat((location.column - 1).coerceAtLeast(0)) + "^ ${message.text}"
            ctx.logger().warn(
                "File: %s:%d:%d\n%d | %s\n    %s",
                location.file, location.line, location.column, location.line, source, caret,
            )
        } catch (nested: Throwable) {
            ctx.logger().warn(nested)
        }
    }
}
