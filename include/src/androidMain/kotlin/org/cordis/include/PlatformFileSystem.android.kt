package org.cordis.include

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal actual object PlatformFileSystem {
    actual fun resolve(path: String, baseUrl: String?): String {
        val candidate = when {
            path.startsWith("config:") -> configDirectory().resolve(path.removePrefix("config:").trimStart('/', '\\'))
            Paths.get(path).isAbsolute -> Paths.get(path)
            baseUrl != null -> basePath(baseUrl).resolve(path)
            else -> Paths.get("").toAbsolutePath().resolve(path)
        }
        return candidate.toAbsolutePath().normalize().toString()
    }

    actual fun parent(path: String): String = Paths.get(path).parent.toString()
    actual fun toFileUrl(path: String): String = Paths.get(path).toUri().toString()
    actual fun extension(path: String): String {
        val name = Paths.get(path).fileName.toString()
        return name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }.lowercase()
    }
    actual fun exists(path: String): Boolean = Files.exists(Paths.get(path))
    actual fun isWritable(path: String): Boolean = !exists(path) || Files.isWritable(Paths.get(path))
    actual fun readUtf8(path: String): String = String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)

    actual fun writeUtf8Atomic(path: String, content: String) {
        val target = Paths.get(path)
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
        if (uri.scheme == "file") Paths.get(uri) else Paths.get(value)
    }.getOrElse { Paths.get(value) }.let { if (Files.isDirectory(it)) it else it.parent ?: it }

    private fun configDirectory(): Path {
        System.getProperty("cordis.config.dir")?.takeIf(String::isNotBlank)?.let { return Paths.get(it) }
        val home = Paths.get(System.getProperty("user.home"))
        return Paths.get(System.getenv("XDG_CONFIG_HOME") ?: home.resolve(".config").toString(), "cordis")
    }
}
