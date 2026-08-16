package org.cordis.loader

import org.cordis.Context

data class JsExpr(val __jsExpr: String)

fun isJsExpr(value: Any?): Boolean = value is JsExpr || value is Map<*, *> && value.containsKey("__jsExpr")

/**
 * Evaluates the portable subset used by loader configs: literals and Context service paths.
 * Arbitrary JavaScript is intentionally unavailable in portable loader configs (see ALIGNMENT.md).
 */
fun evaluate(ctx: Context, expression: String): Any? {
    val expr = expression.trim()
    return when {
        expr == "true" -> true
        expr == "false" -> false
        expr == "null" -> null
        expr.matches(Regex("-?\\d+")) -> expr.toLong().narrow()
        expr.matches(Regex("-?\\d+\\.\\d+")) -> expr.toDouble()
        (expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'")) ->
            decodeQuoted(expr)
        expr.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*")) ->
            ctx.resolveService(org.cordis.ServiceReference(expr))
        else -> throw IllegalArgumentException("unsupported portable loader expression: $expression")
    }
}

private fun Long.narrow(): Number = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else this

private fun decodeQuoted(expression: String): String = buildString {
    val quote = expression.first()
    var index = 1
    while (index < expression.lastIndex) {
        val char = expression[index++]
        if (char != '\\') {
            append(char)
            continue
        }
        require(index < expression.lastIndex) { "unterminated escape in loader expression" }
        append(when (val escaped = expression[index++]) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            '\\' -> '\\'
            quote -> quote
            else -> escaped
        })
    }
}

fun interpolate(ctx: Context, value: Any?): Any? = when (value) {
    is JsExpr -> evaluate(ctx, value.__jsExpr)
    is Map<*, *> -> if (value.containsKey("__jsExpr")) evaluate(ctx, value["__jsExpr"].toString())
        else value.entries.associate { it.key to interpolate(ctx, it.value) }
    is List<*> -> value.map { interpolate(ctx, it) }
    is Array<*> -> value.map { interpolate(ctx, it) }
    else -> value
}
