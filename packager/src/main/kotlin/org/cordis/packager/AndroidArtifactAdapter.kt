package org.cordis.packager

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.LibraryVariant
import com.android.build.api.variant.ApplicationVariant
import com.android.build.gradle.LibraryExtension
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

internal class AndroidArtifactAdapter(private val project: Project) : ArtifactAdapter {
    private val androidLibraries = mutableMapOf<String, LibraryVariant>()
    private val androidApplications = mutableMapOf<String, ApplicationVariant>()
    private val d8 = project.configurations.maybeCreate("cordisD8").apply {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

    init {
        project.dependencies.attributesSchema.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).apply {
            compatibilityRules.add(RawAndroidArtifactCompatibility::class.java)
            disambiguationRules.add(RawAndroidArtifactRule::class.java)
        }
        project.pluginManager.withPlugin("com.android.library") {
            project.extensions.getByType(LibraryAndroidComponentsExtension::class.java).onVariants { selected ->
                androidLibraries[selected.name] = selected
                // AGP's local runtime variants normally expose classes/resources separately.
                // Publish the complete AAR as an additional variant owned by this module.
                project.configurations.getByName("${selected.name}RuntimeElements").outgoing.variants.create("cordisArchive") {
                    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
                    artifact(selected.artifacts.get(SingleArtifact.AAR))
                }
            }
        }
        project.pluginManager.withPlugin("com.android.application") {
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { selected -> androidApplications[selected.name] = selected }
        }
    }

    override fun artifact(release: PluginRelease, variant: PluginVariant, suffix: String): Provider<RegularFile> {
        val variantName = variant.androidVariant.get()
        variant.runtimeId.convention("android-dex")
        variant.artifactName.convention("plugin.apk")
        val library = project.extensions.findByType(LibraryAndroidComponentsExtension::class.java)
        if (library != null) {
            val android = project.extensions.getByType(LibraryExtension::class.java)
            variant.androidPackageName.convention(requireNotNull(android.namespace))
            variant.androidMinSdk.convention(android.defaultConfig.minSdk ?: 26)
            variant.androidTargetSdk.convention(android.defaultConfig.targetSdk ?: requireNotNull(android.compileSdk))
            val buildToolsVersion = android.buildToolsVersion
            val compileSdk = requireNotNull(android.compileSdk)
            val tools = library.sdkComponents.sdkDirectory.map { it.dir("build-tools/$buildToolsVersion") }
            variant.runtimeMinVersion.convention(variant.androidMinSdk.map(Int::toString))
            val java = project.extensions.getByType(JavaToolchainService::class.java)
            val launcher = java.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            val compiler = java.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            if (d8.dependencies.isEmpty()) {
                val version = project.extensions.getByType(CordisPackagesExtension::class.java).androidD8Version.get()
                d8.dependencies.add(project.dependencies.create("com.android.tools:r8:$version"))
            }
            val privateArtifacts = privateArtifacts(project, "${variantName}RuntimeClasspath", variant, android = true)
            val task = project.tasks.register("prepare${suffix}Apk", PrepareAndroidPluginTask::class.java) {
                privateLibraries.from(privateArtifacts.artifactFiles)
                incompleteAndroidDependencies.set(privateArtifacts.resolvedArtifacts.map { artifacts ->
                    artifacts.filter { artifact ->
                        artifact.id.componentIdentifier is ProjectComponentIdentifier && artifact.file.extension != "aar" &&
                            artifact.variant.attributes.keySet().any { it.name == "com.android.build.api.attributes.BuildTypeAttr" }
                    }.map { it.id.componentIdentifier.displayName }.sorted()
                })
                compileClasspath.from(project.configurations.getByName("${variantName}CompileClasspath").incoming.artifactView {
                    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "cordis-android-archive")
                }.files)
                packageName.set(variant.androidPackageName)
                minSdk.set(variant.androidMinSdk)
                targetSdk.set(variant.androidTargetSdk)
                entryPoint.set(variant.entryPoint)
                sharedPackages.set(variant.sharedPackages)
                sharedClasses.set(variant.sharedClasses)
                excludedPayloadPaths.set(variant.excludedPayloadPaths)
                manifest.set(variant.androidManifest)
                androidJar.set(library.sdkComponents.sdkDirectory.map { it.file("platforms/android-$compileSdk/android.jar") })
                aapt2.set(tools.map { it.file(if (System.getProperty("os.name").startsWith("Windows")) "aapt2.exe" else "aapt2") })
                zipalign.set(tools.map { it.file(if (System.getProperty("os.name").startsWith("Windows")) "zipalign.exe" else "zipalign") })
                d8Classpath.from(d8)
                javaExecutable.set(launcher.map { it.executablePath })
                javacExecutable.set(compiler.map { it.executablePath })
                outputFile.set(project.layout.buildDirectory.file("cordis/artifacts/${release.name}/${variant.name}/plugin.apk"))
            }
            val selected = requireNotNull(androidLibraries[variantName]) { "Unknown Android library variant: $variantName" }
            task.configure { this.library.set(selected.artifacts.get(SingleArtifact.AAR)) }
            variant.runtimeMetadata.set(Json.encodeToString(JsonObject(jsonObject(variant.runtimeMetadata.get()) + ("packageName" to JsonPrimitive(variant.androidPackageName.get())))))
            return task.flatMap { it.outputFile }
        }
        val application = project.extensions.findByType(ApplicationAndroidComponentsExtension::class.java)
            ?: error("androidVariant requires com.android.library or com.android.application")
        val selected = requireNotNull(androidApplications[variantName]) { "Unknown Android application variant: $variantName" }
        variant.androidPackageName.convention(selected.applicationId)
        variant.runtimeMinVersion.convention(selected.minSdk.apiLevel.toString())
        // AGP's built-artifact loader selects actual outputs rather than assuming filenames.
        val loader = selected.artifacts.getBuiltArtifactsLoader()
        val apk = selected.artifacts.get(SingleArtifact.APK).map { directory ->
            val outputs = requireNotNull(loader.load(directory)).elements
            require(outputs.size == 1 && outputs.single().filters.isEmpty()) { "Split APKs require an explicit artifact declaration" }
            File(outputs.single().outputFile)
        }
        variant.runtimeMetadata.set(Json.encodeToString(JsonObject(jsonObject(variant.runtimeMetadata.get()) + ("packageName" to JsonPrimitive(variant.androidPackageName.get())))))
        return project.layout.file(apk)
    }
}

/** Select complete archives when a consumer has not requested an AGP transform. */
abstract class RawAndroidArtifactRule : AttributeDisambiguationRule<String> {
    override fun execute(details: MultipleCandidatesDetails<String>) {
        if (details.consumerValue != "cordis-android-archive") return
        when {
            "aar" in details.candidateValues -> details.closestMatch("aar")
            "jar" in details.candidateValues -> details.closestMatch("jar")
        }
    }
}

abstract class RawAndroidArtifactCompatibility : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        if (details.consumerValue == "cordis-android-archive" && details.producerValue in setOf("aar", "jar")) details.compatible()
    }
}

