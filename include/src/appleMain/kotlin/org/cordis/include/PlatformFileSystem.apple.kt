@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.cordis.include

import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.pathExtension
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringByStandardizingPath
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

internal actual object PlatformFileSystem {
    private val manager: NSFileManager get() = NSFileManager.defaultManager

    actual fun resolve(path: String, baseUrl: String?): String {
        val candidate = when {
            path.startsWith("config:") -> "$configDirectory/${path.removePrefix("config:").trimStart('/', '\\')}"
            path.startsWith('/') -> path
            baseUrl != null -> "${basePath(baseUrl)}/$path"
            else -> "${manager.currentDirectoryPath}/$path"
        }
        return normalize(candidate)
    }

    actual fun parent(path: String): String = (path as NSString).stringByDeletingLastPathComponent
    actual fun toFileUrl(path: String): String = checkNotNull(NSURL.fileURLWithPath(path).absoluteString)
    actual fun extension(path: String): String = (path as NSString).pathExtension
        .takeIf(String::isNotEmpty)
        ?.let { ".$it" }
        .orEmpty()
        .lowercase()
    actual fun exists(path: String): Boolean = manager.fileExistsAtPath(path)
    actual fun isWritable(path: String): Boolean = manager.isWritableFileAtPath(if (exists(path)) path else parent(path))
    actual fun readUtf8(path: String): String = NSString.stringWithContentsOfFile(
        path,
        NSUTF8StringEncoding,
        null,
    ) ?: error("cannot read $path")

    actual fun writeUtf8Atomic(path: String, content: String) {
        check(manager.createDirectoryAtPath(parent(path), true, null, null)) {
            "cannot create parent directory for $path"
        }
        check((content as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)) {
            "cannot write $path"
        }
    }

    private fun basePath(value: String): String {
        val path = if (value.startsWith("file:")) NSURL.URLWithString(value)?.path ?: value else value
        return if (value.endsWith('/')) normalize(path) else parent(path)
    }

    private fun normalize(path: String): String = (path as NSString).stringByStandardizingPath
    private val configDirectory: String get() = normalize("${NSHomeDirectory()}/Library/Application Support/cordis")
}
