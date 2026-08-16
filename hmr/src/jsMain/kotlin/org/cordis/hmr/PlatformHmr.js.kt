package org.cordis.hmr

private val fs: dynamic = js("require('node:fs')")
private val pathApi: dynamic = js("require('node:path')")
private val urlApi: dynamic = js("require('node:url')")
private val processApi: dynamic = js("process")

internal actual object PlatformHmr {
    actual fun resolveBase(configured: String?, baseUrl: String?): String {
        val base: String = when {
            baseUrl == null -> processApi.cwd() as String
            baseUrl.startsWith("file:") -> urlApi.fileURLToPath(baseUrl) as String
            else -> baseUrl
        }.let { if (fs.existsSync(it) as Boolean && fs.statSync(it).isDirectory() as Boolean) it else pathApi.dirname(it) as String }
        return pathApi.resolve(base, configured ?: ".") as String
    }
    actual fun normalize(path: String): String = pathApi.resolve(path) as String
    actual fun toFileUrl(path: String): String = urlApi.pathToFileURL(path).toString() as String
    actual fun relative(base: String, url: String): String = runCatching {
        pathApi.relative(base, if (url.startsWith("file:")) urlApi.fileURLToPath(url) else url) as String
    }.getOrDefault(url)
    actual fun readLines(path: String): List<String> = (fs.readFileSync(path, "utf8") as String).lines()
}

internal actual class PlatformWatcher actual constructor(
    private val baseDir: String,
    private val roots: List<String>,
    private val ignored: List<String>,
    private val onChange: (String) -> Unit,
) {
    private var watcher: dynamic = null
    actual fun start() {
        if (watcher != null) return
        val options = js("({ recursive: true })")
        watcher = fs.watch(baseDir, options) { _: dynamic, filename: dynamic ->
            if (filename != null) {
                val relative = filename.toString().replace('\\', '/')
                if (matchesWatchPath(relative, roots, ignored)) {
                    onChange(pathApi.resolve(baseDir, relative) as String)
                }
            }
        }
    }
    actual fun stop() {
        watcher?.close()
        watcher = null
    }
}
