package org.cordis.packager

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class PrepareJvmPluginTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val jars: ConfigurableFileCollection
    @get:Input abstract val sharedPackages: SetProperty<String>
    @get:Input abstract val sharedClasses: SetProperty<String>
    @get:Input abstract val entryPoint: Property<String>
    @get:Optional @get:Input abstract val runtimeMinVersion: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val contents = JarContents(sharedPackages.get(), sharedClasses.get())
        jars.files.sortedBy { it.name }.forEach { contents.addJar(it) }
        contents.requireEntry(entryPoint.get())
        runtimeMinVersion.orNull?.let { minimum ->
            val advertised = minimum.toIntOrNull() ?: error("JVM runtimeMinVersion must be an integer Java version")
            contents.entries.filterKeys { it.endsWith(".class") }.forEach { (path, bytes) ->
                require(bytes.size >= 8 && bytes.take(4).toByteArray().contentEquals(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()))) { "Invalid JVM class: $path" }
                val version = ((bytes[6].toInt() and 255) shl 8) + (bytes[7].toInt() and 255) - 44
                require(version <= advertised) { "$path requires Java $version; runtimeMinVersion advertises $advertised" }
            }
        }
        contents.write(outputFile.get().asFile)
    }
}

/** One private class closure, with deterministic resources and explicit duplicate handling. */
internal class JarContents(private val sharedPackages: Set<String>, private val sharedClasses: Set<String>) {
    val entries = sortedMapOf<String, ByteArray>()
    private val services = sortedMapOf<String, LinkedHashSet<String>>()

    fun isShared(path: String): Boolean {
        if (!path.endsWith(".class")) return false
        val name = path.removeSuffix(".class").replace('/', '.')
        return sharedPackages.any { name.startsWith("$it.") } ||
            sharedClasses.any { name == it || name.startsWith("$it$") }
    }

    fun addJar(file: File, exclude: (String) -> Boolean = { false }) {
        require(file.extension == "jar" || file.extension == "zip") { "Expected a JAR: $file" }
        ZipFile(file).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.forEach { entry ->
                if (!exclude(entry.name)) add(entry.name, zip.getInputStream(entry).use { it.readBytes() })
            }
        }
    }

    fun add(path: String, bytes: ByteArray) {
        org.cordis.packages.validatePackagePath(path)
        if (isShared(path) || path == "META-INF/MANIFEST.MF" || path.endsWith("module-info.class") ||
            Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)", RegexOption.IGNORE_CASE).matches(path)) return
        require(!path.startsWith("META-INF/versions/")) { "Multi-release JAR requires explicit preprocessing: $path" }
        if (path.startsWith("META-INF/services/")) {
            services.getOrPut(path) { linkedSetOf() }.addAll(bytes.decodeToString().lineSequence()
                .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList())
            return
        }
        val existing = entries[path]
        require(existing == null || (!path.endsWith(".class") && existing.contentEquals(bytes))) { "Conflicting private entry: $path" }
        entries[path] = bytes
    }

    fun requireEntry(entryPoint: String) {
        require("${entryPoint.replace('.', '/')}.class" in entries) { "Entry class missing from private artifact: $entryPoint" }
    }

    fun write(file: File) {
        val complete = entries + services.mapValues { (_, lines) -> (lines.sorted().joinToString("\n") + "\n").encodeToByteArray() }
        writeZip(file, complete)
    }
}

internal fun writeZip(file: File, entries: Map<String, ByteArray>, stored: Set<String> = emptySet()) {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.toSortedMap().forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path).apply {
                setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0))
                if (path in stored) {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
            })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

