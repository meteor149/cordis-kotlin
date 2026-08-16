package org.cordis.include

private val nodeFs: dynamic = js("require('node:fs')")
private val nodePath: dynamic = js("require('node:path')")
private val nodeOs: dynamic = js("require('node:os')")
private val nodeUrl: dynamic = js("require('node:url')")
private val nodeProcess: dynamic = js("process")

internal actual object PlatformFileSystem {
    actual fun resolve(path: String, baseUrl: String?): String {
        val candidate: String = when {
            path.startsWith("config:") -> nodePath.join(configDirectory(), path.removePrefix("config:").trimStart('/', '\\')) as String
            nodePath.isAbsolute(path) as Boolean -> path
            baseUrl != null -> nodePath.resolve(basePath(baseUrl), path) as String
            else -> nodePath.resolve(nodeProcess.cwd(), path) as String
        }
        return nodePath.normalize(candidate) as String
    }

    actual fun parent(path: String): String = nodePath.dirname(path) as String
    actual fun toFileUrl(path: String): String = nodeUrl.pathToFileURL(path).toString() as String
    actual fun extension(path: String): String = (nodePath.extname(path) as String).lowercase()
    actual fun exists(path: String): Boolean = nodeFs.existsSync(path) as Boolean
    actual fun isWritable(path: String): Boolean = try {
        if (exists(path)) nodeFs.accessSync(path, nodeFs.constants.W_OK)
        true
    } catch (_: dynamic) {
        false
    }
    actual fun readUtf8(path: String): String = nodeFs.readFileSync(path, "utf8") as String

    actual fun writeUtf8Atomic(path: String, content: String) {
        nodeFs.mkdirSync(parent(path), js("({ recursive: true })"))
        val temporary = "$path.tmp"
        nodeFs.writeFileSync(temporary, content, "utf8")
        nodeFs.renameSync(temporary, path)
    }

    private fun basePath(value: String): String {
        val path = if (value.startsWith("file:")) nodeUrl.fileURLToPath(value) as String else value
        return if (exists(path) && nodeFs.statSync(path).isDirectory() as Boolean) path else nodePath.dirname(path) as String
    }

    private fun configDirectory(): String {
        val platform = nodeProcess.platform as String
        val env = nodeProcess.env
        val home = nodeOs.homedir() as String
        return when (platform) {
            "win32" -> nodePath.join((env.APPDATA as String?) ?: nodePath.join(home, "AppData", "Roaming"), "cordis") as String
            "darwin" -> nodePath.join(home, "Library", "Application Support", "cordis") as String
            else -> nodePath.join((env.XDG_CONFIG_HOME as String?) ?: nodePath.join(home, ".config"), "cordis") as String
        }
    }
}
