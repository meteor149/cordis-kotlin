package org.cordis.packager

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.packageFileSha256
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** A consumer-owned offline catalog. Choosing packages/configuration remains product policy. */
@CacheableTask
abstract class PluginCatalogTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val archives: ConfigurableFileCollection
    @get:OutputDirectory abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val archive = PluginPackageArchive()
        val releases = archives.files.map { source ->
            val digest = packageFileSha256(source)
            Triple(source, digest, archive.inspect(source, digest).manifest)
        }.sortedBy { it.third.id }
        require(releases.isNotEmpty()) { "Empty plugin catalog" }
        require(releases.map { it.third.id }.distinct().size == releases.size) { "Duplicate package identity in catalog" }
        val stage = File(temporaryDir, "catalog")
        stage.deleteRecursively()
        stage.mkdirs()
        val index = releases.map { (source, digest, manifest) ->
            val name = "${manifest.id}-${manifest.version}.kplugin"
            source.copyTo(File(stage, name))
            buildJsonObject {
                put("id", manifest.id)
                put("file", name)
                put("sha256", digest)
                put("variants", Json.encodeToJsonElement(manifest.variants))
            }
        }
        File(stage, "index.json").writeText(Json.encodeToString(JsonArray(index)) + "\n")
        val destination = destinationDirectory.get().asFile
        destination.deleteRecursively()
        stage.copyRecursively(destination)
    }
}

