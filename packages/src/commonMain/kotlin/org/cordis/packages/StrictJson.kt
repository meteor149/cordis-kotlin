package org.cordis.packages

import kotlinx.serialization.json.Json

/** kotlinx.serialization otherwise keeps the last occurrence of a duplicate object key. */
internal fun rejectDuplicateJsonKeys(source: String) {
    var offset = 0
    fun whitespace() {
        while (offset < source.length && source[offset] in " \t\r\n") offset++
    }
    fun token(expected: Char) {
        whitespace()
        require(offset < source.length && source[offset++] == expected) { "Invalid JSON at $offset" }
    }
    fun string(): String {
        whitespace()
        val start = offset
        token('"')
        while (offset < source.length) {
            when (source[offset++]) {
                '\\' -> { require(offset < source.length) { "Incomplete JSON escape" }; offset++ }
                '"' -> return Json.decodeFromString<String>(source.substring(start, offset))
            }
        }
        error("Unterminated JSON string")
    }
    fun value(depth: Int) {
        require(depth <= 64) { "Package JSON nesting exceeds limit" }
        whitespace()
        require(offset < source.length) { "Incomplete JSON" }
        when (source[offset]) {
            '{' -> {
                token('{')
                val keys = mutableSetOf<String>()
                whitespace()
                if (offset < source.length && source[offset] == '}') { offset++; return }
                while (true) {
                    val key = string()
                    require(keys.add(key)) { "Duplicate JSON key: $key" }
                    token(':')
                    value(depth + 1)
                    whitespace()
                    if (offset < source.length && source[offset] == '}') { offset++; break }
                    token(',')
                }
            }
            '[' -> {
                token('[')
                whitespace()
                if (offset < source.length && source[offset] == ']') { offset++; return }
                while (true) {
                    value(depth + 1)
                    whitespace()
                    if (offset < source.length && source[offset] == ']') { offset++; break }
                    token(',')
                }
            }
            '"' -> string()
            else -> {
                val start = offset
                while (offset < source.length && source[offset] !in ",]} \t\r\n") offset++
                require(offset > start) { "Invalid JSON value" }
            }
        }
    }
    value(0)
    whitespace()
    require(offset == source.length) { "Trailing JSON content" }
}
