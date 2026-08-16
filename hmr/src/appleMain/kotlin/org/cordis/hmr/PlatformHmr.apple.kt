@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.cordis.hmr

import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringByStandardizingPath
import platform.Foundation.stringWithContentsOfFile

internal actual object PlatformHmr {
    actual fun resolveBase(configured: String?, baseUrl: String?): String {
        val value = configured ?: baseUrl ?: NSFileManager.defaultManager.currentDirectoryPath
        val path = if (value.startsWith("file:")) NSURL.URLWithString(value)?.path ?: value else value
        return normalize(path)
    }

    actual fun normalize(path: String): String = (path as NSString).stringByStandardizingPath
    actual fun toFileUrl(path: String): String = checkNotNull(NSURL.fileURLWithPath(path).absoluteString)
    actual fun relative(base: String, url: String): String {
        val target = if (url.startsWith("file:")) NSURL.URLWithString(url)?.path ?: url else url
        return relativePath(normalize(base), normalize(target))
    }

    actual fun readLines(path: String): List<String> = NSString.stringWithContentsOfFile(
        path,
        NSUTF8StringEncoding,
        null,
    )?.lines() ?: error("cannot read $path")

    private fun relativePath(base: String, target: String): String {
        val baseParts = base.trim('/').split('/').filter(String::isNotEmpty)
        val targetParts = target.trim('/').split('/').filter(String::isNotEmpty)
        val shared = baseParts.zip(targetParts).takeWhile { (left, right) -> left == right }.size
        return List(baseParts.size - shared) { ".." }.plus(targetParts.drop(shared)).joinToString("/").ifEmpty { "." }
    }
}

internal actual class PlatformWatcher actual constructor(
    baseDir: String,
    roots: List<String>,
    ignored: List<String>,
    onChange: (String) -> Unit,
) {
    actual fun start() = Unit

    actual fun stop() = Unit
}
