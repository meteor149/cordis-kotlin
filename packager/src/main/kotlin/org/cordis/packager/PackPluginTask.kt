package org.cordis.packager

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.cordis.packages.PackageFile
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.PluginPackageManifest
import org.cordis.packages.packageFileSha256
import org.cordis.packages.validatePackagePath
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VariantInput @Inject constructor() {
    @get:Input abstract val declaration: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val artifact: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val extensionsFile: RegularFileProperty
}

@CacheableTask
abstract class PackPluginTask : DefaultTask() {
    @get:Input abstract val manifestTemplate: Property<String>
    @get:Input abstract val contentVersion: Property<Boolean>
    @get:Nested abstract val variants: ListProperty<VariantInput>
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val extensionsFile: RegularFileProperty
    @get:Optional @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val payloadDirectory: DirectoryProperty
    @get:OutputFile abstract val archiveFile: RegularFileProperty
    @get:OutputFile abstract val checksumFile: RegularFileProperty

    init { contentVersion.convention(false) }

    @TaskAction
    fun pack() {
        val root = File(temporaryDir, "payload")
        root.deleteRecursively()
        root.mkdirs()
        val files = linkedMapOf<String, PackageFile>()
        fun copy(source: File, path: String) {
            validatePackagePath(path)
            require(path != "plugin.json" && !path.endsWith(".kplugin", true)) { "Reserved payload path: $path" }
            require(files.keys.none { it.equals(path, true) }) { "Duplicate package payload: $path" }
            require(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "Symlink in package payload: $source" }
            val destination = File(root, path)
            destination.parentFile.mkdirs()
            source.copyTo(destination)
            files[path] = PackageFile(path, destination.length(), packageFileSha256(destination))
        }
        payloadDirectory.orNull?.asFile?.let { directory ->
            directory.walkTopDown().forEach { source ->
                require(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "Symlink in package payload: $source" }
                if (source.isFile) copy(source, source.relativeTo(directory).invariantSeparatorsPath)
            }
        }
        val resolved = variants.get().map { input ->
            val variant = Json.decodeFromString<PackageVariant>(input.declaration.get())
            validateNativeTargets(input.artifact.get().asFile, variant)
            copy(input.artifact.get().asFile, variant.artifact)
            variant.copy(extensions = mergedExtensions(variant.extensions, input.extensionsFile.orNull?.asFile))
        }
        val template = Json.decodeFromString<PluginPackageManifest>(manifestTemplate.get())
        val resolvedManifest = template.copy(
            variants = resolved,
            files = files.values.sortedBy { it.path },
            extensions = mergedExtensions(template.extensions, extensionsFile.orNull?.asFile),
        )
        val manifest = if (contentVersion.get()) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(Json.encodeToString(resolvedManifest).encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            resolvedManifest.copy(version = "${template.version}+content.$digest")
        } else resolvedManifest
        val digest = PluginPackageArchive().pack(manifest, root, archiveFile.get().asFile)
        checksumFile.get().asFile.apply { parentFile.mkdirs(); writeText("$digest\n") }
    }
}

internal fun validateNativeTargets(artifact: File, variant: PackageVariant) {
    if (variant.runtime.id != "android-dex" || artifact.extension != "apk") return
    val abis = java.util.zip.ZipFile(artifact).use { zip -> zip.entries().asSequence().mapNotNull {
        Regex("lib/([^/]+)/[^/]+\\.so").matchEntire(it.name)?.groupValues?.get(1)
    }.toSet() }
    if (abis.isEmpty()) return
    val names = mapOf("arm" to mapOf(32 to "armeabi-v7a", 64 to "arm64-v8a"), "x86" to mapOf(32 to "x86", 64 to "x86_64"))
    require(variant.targets.all { target -> target.system == "android" && target.arch.all { arch ->
        target.bits.all { bits -> names.getValue(arch).getValue(bits) in abis }
    } }) { "Android targets advertise native ABIs absent from ${variant.id}: $abis" }
}

internal fun jsonObject(value: String): JsonObject = Json.parseToJsonElement(value) as? JsonObject
    ?: error("Expected a JSON object")

internal fun mergedExtensions(value: JsonObject, file: File?): JsonObject {
    if (file == null) return value
    val extra = jsonObject(file.readText())
    require(value.keys.intersect(extra.keys).isEmpty()) { "Duplicate extension keys in $file" }
    return JsonObject(value + extra)
}

