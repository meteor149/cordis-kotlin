package org.cordis.hmr

internal expect object PlatformHmr {
    fun resolveBase(configured: String?, baseUrl: String?): String
    fun normalize(path: String): String
    fun toFileUrl(path: String): String
    fun relative(base: String, url: String): String
    fun readLines(path: String): List<String>
}

internal expect class PlatformWatcher(
    baseDir: String,
    roots: List<String>,
    ignored: List<String>,
    onChange: (String) -> Unit,
) {
    fun start()
    fun stop()
}

internal fun matchesWatchPath(path: String, roots: List<String>, ignored: List<String>): Boolean =
    ignored.none { matchesGlob(path, it) } && roots.any { it == "." || it.isBlank() || matchesGlob(path, it) }

private fun matchesGlob(path: String, source: String): Boolean {
    val normalized = path.replace('\\', '/')
    val pattern = source.replace('\\', '/').removePrefix("./")
    if (pattern == "**/.*") return normalized.split('/').any { it.startsWith('.') }
    if (pattern.startsWith("**/") && pattern.drop(3).none { it == '*' || it == '?' }) {
        return pattern.drop(3) in normalized.split('/')
    }
    if (!pattern.any { it == '*' || it == '?' }) return normalized == pattern || normalized.startsWith("$pattern/")
    val regex = buildString {
        append('^')
        var index = 0
        while (index < pattern.length) when {
            pattern.startsWith("**/", index) -> { append("(?:.*/)?"); index += 3 }
            pattern.startsWith("**", index) -> { append(".*"); index += 2 }
            pattern[index] == '*' -> { append("[^/]*"); index++ }
            pattern[index] == '?' -> { append("[^/]"); index++ }
            else -> { append(Regex.escape(pattern[index].toString())); index++ }
        }
        append('$')
    }
    return Regex(regex).matches(normalized)
}
