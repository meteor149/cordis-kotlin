package org.cordis.hmr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

internal actual object PlatformHmr {
    actual fun resolveBase(configured: String?, baseUrl: String?): String {
        val value = configured ?: baseUrl ?: "."
        return normalize(if (value.startsWith("file:")) Path.of(URI.create(value)).toString() else value)
    }

    actual fun normalize(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()
    actual fun toFileUrl(path: String): String = Path.of(path).toUri().toString()
    actual fun relative(base: String, url: String): String = runCatching {
        val target = if (url.startsWith("file:")) Path.of(URI.create(url)) else Path.of(url)
        Path.of(base).relativize(target).toString()
    }.getOrElse { url }
    actual fun readLines(path: String): List<String> = Files.readAllLines(Path.of(path))
}

internal actual class PlatformWatcher actual constructor(
    private val baseDir: String,
    private val roots: List<String>,
    private val ignored: List<String>,
    private val onChange: (String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var watcher: WatchService? = null
    private var job: Job? = null
    private val keys = mutableMapOf<WatchKey, Path>()

    actual fun start() {
        if (job != null) return
        val service = FileSystems.getDefault().newWatchService()
        watcher = service
        val base = Path.of(baseDir)
        registerTree(base, service)
        job = scope.launch {
            while (isActive) {
                val key = try {
                    runInterruptible { service.take() }
                } catch (_: InterruptedException) {
                    break
                }
                val directory = keys[key] ?: continue
                key.pollEvents().forEach { event ->
                    val relative = event.context() as? Path ?: return@forEach
                    val absolute = directory.resolve(relative)
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(absolute)) {
                        registerTree(absolute, service)
                    }
                    val normalized = base.relativize(absolute).toString().replace('\\', '/')
                    if (matchesWatchPath(normalized, roots, ignored)) onChange(absolute.toString())
                }
                if (!key.reset()) keys.remove(key)
            }
        }
    }

    actual fun stop() {
        job?.cancel()
        job = null
        watcher?.close()
        watcher = null
        keys.clear()
    }

    private fun registerTree(root: Path, service: WatchService) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.filter(Files::isDirectory).forEach { directory ->
                val key = directory.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
                keys[key] = directory
            }
        }
    }
}
