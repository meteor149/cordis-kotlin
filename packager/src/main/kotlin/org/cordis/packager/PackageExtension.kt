package org.cordis.packager

import javax.inject.Inject
import org.cordis.packages.PackageDependency
import org.cordis.packages.PackageTarget
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/** Build declarations only. No application SDK, installation policy, or live plugin instances. */
abstract class CordisPackagesExtension @Inject constructor(objects: ObjectFactory) {
    /** D8 is independent of the SDK build-tools copy, which may predate the Kotlin compiler. */
    val androidD8Version: Property<String> = objects.property(String::class.java).convention("8.13.19")

    val releases: NamedDomainObjectContainer<PluginRelease> = objects.domainObjectContainer(PluginRelease::class.java) {
        objects.newInstance(PluginRelease::class.java, it)
    }

    fun releases(action: Action<NamedDomainObjectContainer<PluginRelease>>) = action.execute(releases)
}

abstract class PluginRelease @Inject constructor(private val releaseName: String, objects: ObjectFactory) : Named {
    override fun getName(): String = releaseName

    abstract val packageId: Property<String>
    abstract val packageVersion: Property<String>
    /** Append a digest of the resolved manifest/payload to distinguish immutable rebuilds. */
    abstract val contentVersion: Property<Boolean>
    abstract val displayName: Property<String>
    abstract val description: Property<String>
    abstract val license: Property<String>
    abstract val manifestExtensions: Property<String>
    abstract val extensionsFile: RegularFileProperty
    abstract val payloadDirectory: DirectoryProperty
    abstract val outputFile: RegularFileProperty
    abstract val dependencies: ListProperty<PackageDependency>

    val variants: NamedDomainObjectContainer<PluginVariant> = objects.domainObjectContainer(PluginVariant::class.java) {
        objects.newInstance(PluginVariant::class.java, it)
    }

    init {
        contentVersion.convention(false)
        manifestExtensions.convention("{}")
        dependencies.convention(emptyList())
    }

    fun variants(action: Action<NamedDomainObjectContainer<PluginVariant>>) = action.execute(variants)
    fun dependency(id: String, version: String) = dependencies.add(PackageDependency(id, version))
}

abstract class PluginVariant @Inject constructor(private val variantName: String) : Named {
    override fun getName(): String = variantName

    /** A prebuilt artifact, a KMP JVM target, or an Android build variant: choose exactly one. */
    abstract val artifact: RegularFileProperty
    abstract val jvmTarget: Property<String>
    abstract val androidVariant: Property<String>
    abstract val artifactName: Property<String>
    abstract val entryPoint: Property<String>
    abstract val runtimeId: Property<String>
    abstract val runtimeMinVersion: Property<String>
    abstract val runtimeMetadata: Property<String>
    abstract val manifestExtensions: Property<String>
    abstract val extensionsFile: RegularFileProperty
    abstract val targets: ListProperty<PackageTarget>

    /** Component identities excluded from the private runtime closure; use project::path for projects. */
    abstract val hostModules: SetProperty<String>
    abstract val sharedPackages: SetProperty<String>
    abstract val sharedClasses: SetProperty<String>

    /** Android library packaging inputs. Defaults come from the existing Android module. */
    abstract val androidPackageName: Property<String>
    abstract val androidManifest: RegularFileProperty
    abstract val androidMinSdk: Property<Int>
    abstract val androidTargetSdk: Property<Int>
    abstract val excludedPayloadPaths: SetProperty<String>
    /** Explicitly use dependency base classes, discarding META-INF/versions implementations. */
    abstract val ignoreMultiReleaseEntries: Property<Boolean>

    init {
        runtimeMetadata.convention("{}")
        manifestExtensions.convention("{}")
        targets.convention(emptyList())
        hostModules.set(setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
            "org.jetbrains.kotlin:kotlin-reflect",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
            "org.jetbrains.kotlinx:atomicfu",
            "org.jetbrains.kotlinx:atomicfu-jvm",
            "org.jetbrains.kotlinx:kotlinx-datetime",
            "org.jetbrains.kotlinx:kotlinx-datetime-jvm",
        ))
        sharedPackages.set(setOf(
            "java", "javax", "jdk", "sun", "com.sun", "android", "dalvik", "kotlin",
            "kotlinx.coroutines", "kotlinx.atomicfu", "kotlinx.datetime", "org.cordis",
        ))
        sharedClasses.convention(emptySet())
        excludedPayloadPaths.convention(emptySet())
        ignoreMultiReleaseEntries.convention(false)
    }

    fun target(value: PackageTarget) = targets.add(value)
}

