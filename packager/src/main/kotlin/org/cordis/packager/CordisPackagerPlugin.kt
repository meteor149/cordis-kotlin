package org.cordis.packager

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cordis.packages.PackageRuntime
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageManifest
import org.cordis.packages.validatePackagePath
import org.cordis.packages.validatePackageId
import org.cordis.packages.validatePackageVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

class CordisPackagerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("cordisPackages", CordisPackagesExtension::class.java)
        val adapters = mutableMapOf<String, ArtifactAdapter>()
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            adapters["jvm"] = JvmArtifactAdapter(project)
        }
        listOf("com.android.library", "com.android.application").forEach { id ->
            project.pluginManager.withPlugin(id) {
                adapters.getOrPut("android") { AndroidArtifactAdapter(project) }
            }
        }
        val all = project.tasks.register("packagePlugins") {
            group = "distribution"
            description = "Build all declared Cordis plugin releases"
        }
        // Read the complete declarations once; task actions retain only typed inputs/providers.
        project.gradle.projectsEvaluated {
            val taskNames = mutableSetOf<String>()
            val packageIds = mutableSetOf<String>()
            extension.releases.forEach { release ->
                val suffix = taskSuffix(release.name)
                require(taskNames.add(suffix)) { "Release task name collision: ${release.name}" }
                release.packageVersion.convention(project.version.toString())
                validatePackageId(release.packageId.get())
                validatePackageVersion(release.packageVersion.get())
                require(packageIds.add(release.packageId.get())) { "Duplicate release package identity: ${release.packageId.get()}" }
                require(release.variants.isNotEmpty()) { "Release ${release.name} declares no variants" }
                val inputs = release.variants.map { variant ->
                    val input = project.objects.newInstance(VariantInput::class.java)
                    input.artifact.set(connectArtifact(release, variant, adapters))
                    input.extensionsFile.set(variant.extensionsFile)
                    val name = variant.artifactName.get()
                    validatePackagePath(name)
                    require('/' !in name) { "artifactName must be a filename" }
                    val descriptor = PackageVariant(
                        id = variant.name,
                        targets = variant.targets.get(),
                        runtime = PackageRuntime(variant.runtimeId.get(), variant.entryPoint.get(), variant.runtimeMinVersion.orNull, jsonObject(variant.runtimeMetadata.get())),
                        artifact = "targets/${variant.name}/$name",
                        extensions = jsonObject(variant.manifestExtensions.get()),
                    )
                    descriptor.validate()
                    input.declaration.set(Json.encodeToString(descriptor))
                    input
                }
                release.outputFile.convention(project.layout.buildDirectory.file("cordis/packages/${release.packageId.get()}-${release.packageVersion.get()}.kplugin"))
                val checksum = project.layout.file(release.outputFile.map { File(it.asFile.absolutePath + ".sha256") })
                val task = project.tasks.register("package$suffix", PackPluginTask::class.java) {
                    group = "distribution"
                    description = "Build ${release.packageId.get()} as an independent .kplugin"
                    manifestTemplate.set(Json.encodeToString(PluginPackageManifest(
                        id = release.packageId.get(), version = release.packageVersion.get(),
                        displayName = release.displayName.orNull, description = release.description.orNull, license = release.license.orNull,
                        dependencies = release.dependencies.get(), variants = emptyList(), files = emptyList(), extensions = jsonObject(release.manifestExtensions.get()),
                    )))
                    variants.set(inputs)
                    contentVersion.set(release.contentVersion)
                    extensionsFile.set(release.extensionsFile)
                    payloadDirectory.set(release.payloadDirectory)
                    archiveFile.set(release.outputFile)
                    checksumFile.set(checksum)
                }
                all.configure { dependsOn(task) }
                project.configurations.create("cordis${suffix}Elements") {
                    isCanBeConsumed = true
                    isCanBeResolved = false
                    attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "cordis-package"))
                    attributes.attribute(PackageIdentity, release.packageId.get())
                    outgoing.artifact(task.flatMap { it.archiveFile }) { builtBy(task) }
                }
            }
        }
    }


    private fun connectArtifact(release: PluginRelease, variant: PluginVariant, adapters: Map<String, ArtifactAdapter>): Provider<RegularFile> {
        require(listOf(variant.artifact.isPresent, variant.jvmTarget.isPresent, variant.androidVariant.isPresent).count { it } == 1) {
            "${release.name}/${variant.name}: choose exactly one of artifact, jvmTarget, androidVariant"
        }
        if (variant.artifact.isPresent) {
            variant.artifactName.convention(variant.artifact.map { it.asFile.name })
            return variant.artifact
        }
        val kind = if (variant.jvmTarget.isPresent) "jvm" else "android"
        val adapter = requireNotNull(adapters[kind]) { "${release.name}/${variant.name}: missing $kind build plugin" }
        return adapter.artifact(release, variant, taskSuffix(release.name) + taskSuffix(variant.name))
    }
}
internal val PackageIdentity: Attribute<String> = Attribute.of("org.cordis.package.id", String::class.java)
internal fun taskSuffix(name: String): String {
    require(name.matches(Regex("[A-Za-z][A-Za-z0-9-]*"))) { "Invalid declaration name: $name" }
    return name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}

