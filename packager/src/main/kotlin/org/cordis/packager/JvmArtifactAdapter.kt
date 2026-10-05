package org.cordis.packager

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal interface ArtifactAdapter {
    fun artifact(release: PluginRelease, variant: PluginVariant, suffix: String): Provider<RegularFile>
}

internal class JvmArtifactAdapter(private val project: Project) : ArtifactAdapter {
    override fun artifact(release: PluginRelease, variant: PluginVariant, suffix: String): Provider<RegularFile> {
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
            ?: error("jvmTarget requires the Kotlin Multiplatform plugin")
        val target = kotlin.targets.findByName(variant.jvmTarget.get()) as? KotlinJvmTarget
            ?: error("Unknown KMP JVM target: ${variant.jvmTarget.get()}")
        val compilation = target.compilations.getByName("main")
        variant.runtimeId.convention("jvm")
        variant.artifactName.convention("plugin.jar")
        val bytecodeTarget = target.compilerOptions.jvmTarget.orNull?.target ?: "1.8"
        variant.runtimeMinVersion.convention(if (bytecodeTarget == "1.8") "8" else bytecodeTarget)
        val task = project.tasks.register("prepare${suffix}Jar", PrepareJvmPluginTask::class.java) {
            jars.from(project.tasks.named(target.artifactsTaskName, Jar::class.java).flatMap { it.archiveFile })
            jars.from(privateClosure(project, compilation.runtimeDependencyConfigurationName, variant))
            sharedPackages.set(variant.sharedPackages)
            sharedClasses.set(variant.sharedClasses)
            ignoreMultiReleaseEntries.set(variant.ignoreMultiReleaseEntries)
            entryPoint.set(variant.entryPoint)
            runtimeMinVersion.set(variant.runtimeMinVersion)
            outputFile.set(project.layout.buildDirectory.file("cordis/artifacts/${release.name}/${variant.name}/plugin.jar"))
        }
        return task.flatMap { it.outputFile }
    }
}

internal fun privateClosure(project: Project, configuration: String, variant: PluginVariant, android: Boolean = false) =
    privateArtifacts(project, configuration, variant, android).artifactFiles

internal fun privateArtifacts(project: Project, configuration: String, variant: PluginVariant, android: Boolean = false) =
        project.configurations.getByName(configuration).incoming.artifactView {
            if (android) attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "cordis-android-archive")
            val excluded = variant.hostModules.get()
            componentFilter { id ->
                val identity = when (id) {
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}"
                    is ProjectComponentIdentifier -> "project:${id.projectPath}"
                    else -> id.displayName
                }
                identity !in excluded
            }
        }.artifacts

