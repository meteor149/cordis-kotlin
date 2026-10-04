package org.cordis.packages

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class PackageArchiveLimits(
    val maxArchiveBytes: Long = 512L * 1024 * 1024,
    val maxExpandedBytes: Long = 1024L * 1024 * 1024,
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxEntries: Int = 8192,
    val maxExpansionRatio: Int = 200,
) {
    init {
        require(maxArchiveBytes > 0 && maxExpandedBytes > 0 && maxEntryBytes > 0 && maxEntries in 1..65534 && maxExpansionRatio > 0)
    }
}

data class InspectedPluginPackage(val manifest: PluginPackageManifest, val archiveSha256: String)

/** Files remain owned by the application after deployment; no class loader is created here. */
data class DeployedPluginPackage(
    val manifest: PluginPackageManifest,
    val archiveSha256: String,
    val directory: File,
    val created: Boolean,
) {
    val archive: File get() = File(directory, "release.kplugin")
    fun artifact(variant: PackageVariant): File {
        require(variant in manifest.variants) { "Variant does not belong to this package" }
        return File(File(directory, "payload"), variant.artifact)
    }
}

/** Safe offline archive processing. Callers supply expected digests and their own trust policy. */
class PluginPackageArchive(private val limits: PackageArchiveLimits = PackageArchiveLimits()) {
    fun inspect(archive: File, expectedSha256: String): InspectedPluginPackage {
        require(expectedSha256.matches(Sha256Pattern)) { "Expected archive SHA-256 is required" }
        require(Files.isRegularFile(archive.toPath(), LinkOption.NOFOLLOW_LINKS) && archive.length() <= limits.maxArchiveBytes) { "Invalid/oversized package archive" }
        require(packageFileSha256(archive) == expectedSha256) { "Package archive checksum mismatch" }
        val manifest = read(archive, null)
        require(packageFileSha256(archive) == expectedSha256) { "Package archive changed during inspection" }
        return InspectedPluginPackage(manifest, expectedSha256)
    }

    /** Snapshot first, then verify and extract. Atomic immutable generation publication. */
    fun deploy(archive: File, expectedSha256: String, root: File): DeployedPluginPackage {
        require(expectedSha256.matches(Sha256Pattern)) { "Expected archive SHA-256 is required" }
        val canonicalRoot = Files.createDirectories(root.toPath()).toRealPath()
        val target = canonicalRoot.resolve(expectedSha256)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return verifyDeployment(target.toFile(), expectedSha256).copy(created = false)
        val stage = Files.createTempDirectory(canonicalRoot, ".package-")
        try {
            val snapshot = stage.resolve("release.kplugin").toFile()
            require(Files.isRegularFile(archive.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid package archive" }
            archive.inputStream().use { input ->
                FileOutputStream(snapshot).use { output ->
                    copyBounded(input, output, limits.maxArchiveBytes)
                    output.fd.sync()
                }
            }
            val manifest = inspect(snapshot, expectedSha256).manifest
            val payload = Files.createDirectory(stage.resolve("payload")).toFile()
            read(snapshot, payload)
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
            return DeployedPluginPackage(manifest, expectedSha256, target.toFile(), true)
        } finally {
            // stage is a freshly allocated child of this root, never a caller-provided path.
            stage.toFile().deleteRecursively()
        }
    }

    fun verifyDeployment(directory: File, expectedSha256: String): DeployedPluginPackage {
        require(!Files.isSymbolicLink(directory.toPath()) && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid deployment directory" }
        val archive = File(directory, "release.kplugin")
        val manifest = inspect(archive, expectedSha256).manifest
        val payload = File(directory, "payload").toPath()
        require(!Files.isSymbolicLink(payload) && Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS)) { "Invalid deployment payload" }
        val expected = manifest.files.associateBy { it.path }
        val actual = mutableSetOf<String>()
        Files.walk(payload).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Symlink in deployment" }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    val relative = payload.relativize(path).toString().replace('\\', '/')
                    val record = expected[relative] ?: error("Undeclared deployment file: $relative")
                    require(Files.size(path) == record.size && packageFileSha256(path.toFile()) == record.sha256) { "Deployment payload checksum mismatch: $relative" }
                    actual += relative
                } else require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid deployment entry" }
            }
        }
        require(actual == expected.keys) { "Missing deployment payload" }
        return DeployedPluginPackage(manifest, expectedSha256, directory, false)
    }

    /** A deterministic packer for Gradle/CLI integrations; manifest hashes are checked, not invented. */
    fun pack(manifest: PluginPackageManifest, payload: File, output: File): String {
        manifest.validate()
        require(manifest.files.size + 1 <= limits.maxEntries) { "Too many package entries" }
        require(manifest.files.sumOf { it.size } <= limits.maxExpandedBytes) { "Package exceeds expanded limit" }
        val root = payload.toPath().toRealPath()
        val outputPath = output.toPath().toAbsolutePath().normalize()
        require(!outputPath.startsWith(root)) { "Package output cannot be inside payload" }
        Files.createDirectories(outputPath.parent)
        val temporary = Files.createTempFile(outputPath.parent, ".package-${UUID.randomUUID()}-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(canonicalZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("plugin.json", PluginPackageCodec.encode(manifest))
                manifest.files.sortedBy { it.path }.forEach { record ->
                    val source = root.resolve(record.path)
                    var parent = source
                    while (parent != root) {
                        require(!Files.isSymbolicLink(parent)) { "Symlink in package payload" }
                        parent = parent.parent
                    }
                    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && Files.size(source) == record.size && record.size <= limits.maxEntryBytes) { "Invalid package payload: ${record.path}" }
                    require(packageFileSha256(source.toFile()) == record.sha256) { "Payload checksum mismatch: ${record.path}" }
                    zip.putNextEntry(canonicalZipEntry(record.path))
                    Files.newInputStream(source).use { copyBounded(it, zip, record.size) }
                    zip.closeEntry()
                }
            }
            val digest = packageFileSha256(temporary.toFile())
            inspect(temporary.toFile(), digest)
            Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return digest
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun read(archive: File, destination: File?): PluginPackageManifest {
        val centralNames = checkCentralDirectory(archive)
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.map { it.name }.toSet() == centralNames && entries.size == centralNames.size) { "Inconsistent ZIP directory" }
            val manifestEntry = entries.singleOrNull { it.name == "plugin.json" } ?: error("Missing plugin.json")
            val manifestBytes = zip.getInputStream(manifestEntry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                copyBounded(input, output, PluginPackageCodec.MaxManifestBytes.toLong())
                output.toByteArray()
            }
            val manifest = PluginPackageCodec.decode(manifestBytes)
            val files = manifest.files.associateBy { it.path }
            require(centralNames == files.keys + "plugin.json") { "Undeclared/missing archive files" }
            var expanded = manifestBytes.size.toLong()
            for (entry in entries.filter { it.name != "plugin.json" }) {
                val record = files.getValue(entry.name)
                require(entry.size == record.size && record.size <= limits.maxEntryBytes) { "Invalid archive file size: ${entry.name}" }
                require(entry.compressedSize >= 0 && record.size.toDouble() <= maxOf(1L, entry.compressedSize).toDouble() * limits.maxExpansionRatio) { "Archive expansion ratio exceeds limit" }
                expanded += record.size
                require(expanded <= limits.maxExpandedBytes) { "Archive expansion exceeds limit" }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val target = destination?.let { File(it, entry.name) }
                    target?.parentFile?.mkdirs()
                    val output = target?.let { FileOutputStream(it) }
                    try {
                        val count = copyBounded(input, output, record.size, digest)
                        require(count == record.size && digest.digest().hex() == record.sha256) { "Payload checksum mismatch: ${entry.name}" }
                        output?.fd?.sync()
                    } finally { output?.close() }
                }
            }
            return manifest
        }
    }

    /** Reject symlinks and encryption from ZIP metadata unavailable through java.util.zip. */
    private fun checkCentralDirectory(archive: File): Set<String> = RandomAccessFile(archive, "r").use { file ->
        require(file.length() in 22..limits.maxArchiveBytes) { "Invalid ZIP length" }
        val tailSize = minOf(file.length(), 65557).toInt()
        val tail = ByteArray(tailSize)
        file.seek(file.length() - tailSize)
        file.readFully(tail)
        val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
            tail.u32(offset) == 0x06054b50L && offset + 22 + tail.u16(offset + 20) == tail.size
        } ?: error("Missing ZIP end record")
        require(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0) { "Multi-disk ZIP is unsupported" }
        val count = tail.u16(end + 10)
        require(count == tail.u16(end + 8) && count in 1..limits.maxEntries && count < 65535) { "Invalid ZIP entry count" }
        val centralSize = tail.u32(end + 12)
        val centralOffset = tail.u32(end + 16)
        require(centralOffset + centralSize == file.length() - tailSize + end && centralSize <= limits.maxArchiveBytes) { "ZIP64/inconsistent central directory is unsupported" }
        file.seek(centralOffset)
        val names = mutableSetOf<String>()
        val folded = mutableSetOf<String>()
        repeat(count) {
            val header = ByteArray(46)
            file.readFully(header)
            require(header.u32(0) == 0x02014b50L) { "Invalid ZIP central record" }
            require(header.u16(8) and 1 == 0 && header.u16(10) in setOf(0, 8)) { "Encrypted/unsupported ZIP entry" }
            val unixMode = (header.u32(38) ushr 16).toInt() and 0xf000
            require(unixMode == 0 || unixMode == 0x8000) { "Non-regular ZIP entry" }
            val nameBytes = ByteArray(header.u16(28))
            file.readFully(nameBytes)
            val name = nameBytes.decodeToString(throwOnInvalidSequence = true)
            validatePackagePath(name)
            require(names.add(name) && folded.add(name.lowercase())) { "Duplicate/colliding ZIP entry" }
            file.seek(file.filePointer + header.u16(30) + header.u16(32))
            require(file.filePointer <= centralOffset + centralSize) { "ZIP directory exceeds bounds" }
        }
        require(file.filePointer == centralOffset + centralSize) { "Trailing ZIP central records" }
        names
    }
}

fun packageFileSha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Package hashing interrupted")
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().hex()
}

private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream?, limit: Long, digest: MessageDigest? = null): Long {
    val buffer = ByteArray(65536)
    var count = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Package processing interrupted")
        val read = input.read(buffer)
        if (read < 0) return count
        require(count <= limit - read) { "Package stream exceeds limit" }
        count += read
        digest?.update(buffer, 0, read)
        output?.write(buffer, 0, read)
    }
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

// ZIP's DOS timestamp is local time. A fixed UTC epoch would produce different bytes across
// publisher time zones (and extended timestamps before 1980). Use the same local DOS date.
private fun canonicalZipEntry(name: String): ZipEntry = ZipEntry(name).also {
    it.time = java.util.GregorianCalendar(1980, 0, 1, 0, 0, 0).apply {
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
