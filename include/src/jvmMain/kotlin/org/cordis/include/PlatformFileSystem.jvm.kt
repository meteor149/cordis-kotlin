package org.cordis.include

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal actual object PlatformFileSystem {
    actual fun resolve(path: String, baseUrl: String?): String {
        val candidate = when {
            path.startsWith("config:") -> configDirectory().resolve(path.removePrefix("config:").trimStart('/', '\\'))
            Path.of(path).isAbsolute -> Path.of(path)
            baseUrl != null -> basePath(baseUrl).resolve(path)
            else -> Path.of("").toAbsolutePath().resolve(path)
        }
        return candidate.toAbsolutePath().normalize().toString()
    }

    actual fun parent(path: String): String = Path.of(path).parent.toString()
    actual fun toFileUrl(path: String): String = Path.of(path).toUri().toString()
    actual fun extension(path: String): String {
        val name = Path.of(path).fileName.toString()
        return name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }.lowercase()
    }
    actual fun exists(path: String): Boolean = Files.exists(Path.of(path))
    actual fun isWritable(path: String): Boolean = !exists(path) || Files.isWritable(Path.of(path))
    actual fun readUtf8(path: String): String = String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    actual fun writeUtf8Atomic(path: String, content: String) {
        val target = Path.of(path)
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(
            temporary,
            content.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun basePath(value: String): Path = runCatching {
        val uri = URI.create(value)
        if (uri.scheme == "file") Path.of(uri) else Path.of(value)
    }.getOrElse { Path.of(value) }.let { if (Files.isDirectory(it)) it else it.parent ?: it }

    private fun configDirectory(): Path {
        System.getProperty("cordis.config.dir")?.takeIf(String::isNotBlank)?.let { return Path.of(it) }
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> Path.of(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString(), "cordis")
            os.contains("mac") -> home.resolve("Library/Application Support/cordis")
            else -> Path.of(System.getenv("XDG_CONFIG_HOME") ?: home.resolve(".config").toString(), "cordis")
        }
    }
}
