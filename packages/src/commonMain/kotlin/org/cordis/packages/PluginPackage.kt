package org.cordis.packages

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Distribution metadata only. Hosts own publisher trust, configuration and installation state. */
@Serializable
data class PluginPackageManifest(
    val formatVersion: Int = 1,
    val id: String,
    val version: String,
    val displayName: String? = null,
    val description: String? = null,
    val license: String? = null,
    val dependencies: List<PackageDependency> = emptyList(),
    val variants: List<PackageVariant>,
    val files: List<PackageFile>,
    val extensions: JsonObject = JsonObject(emptyMap()),
) {
    fun validate() {
        require(formatVersion == 1) { "Unsupported package format: $formatVersion" }
        validatePackageId(id)
        validatePackageVersion(version)
        require(listOfNotNull(displayName, description, license).all { it.isNotBlank() }) { "Empty package label" }
        require(dependencies.map { it.id }.distinct().size == dependencies.size) { "Duplicate package dependency" }
        dependencies.forEach {
            validatePackageId(it.id)
            validatePackageVersion(it.version)
            require(it.id != id) { "Package cannot depend on itself: $id" }
        }
        require(variants.isNotEmpty() && variants.map { it.id }.distinct().size == variants.size) { "Invalid package variants" }
        variants.forEach { it.validate() }
        require(files.isNotEmpty() && files.map { it.path.lowercase() }.distinct().size == files.size) { "Duplicate package file" }
        files.forEach {
            validatePackagePath(it.path)
            require(it.path != "plugin.json" && !it.path.endsWith(".kplugin", ignoreCase = true)) { "Invalid package payload: ${it.path}" }
            require(it.size >= 0 && it.sha256.matches(Sha256Pattern)) { "Invalid file metadata: ${it.path}" }
        }
        val paths = files.map { it.path }.toSet()
        require(variants.all { it.artifact in paths }) { "Variant artifact is not in files" }
        variants.forEachIndexed { index, left ->
            variants.drop(index + 1).forEach { right ->
                require(!left.overlaps(right)) { "Overlapping package variants: ${left.id}, ${right.id}" }
            }
        }
    }

    /** Exactly one variant, with host-specific compatibility checked before selection. */
    fun select(host: PackageHost, compatible: (PackageVariant) -> Boolean = { true }): PackageVariant {
        validate()
        host.validate()
        val matches = variants.filter { it.matches(host) && compatible(it) }
        require(matches.size == 1) {
            if (matches.isEmpty()) "Package '$id' has no compatible variant for ${host.platform}/${host.os}/${host.arch}"
            else "Package '$id' has ambiguous variants: ${matches.map { it.id }}"
        }
        return matches.single()
    }
}

@Serializable
data class PackageDependency(val id: String, val version: String)

@Serializable
data class PackageFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class PackageVariant(
    val id: String,
    val platform: String,
    val os: List<String>,
    val arch: List<String>,
    val artifact: String,
    val entryClass: String,
    val packageName: String? = null,
    val minJava: Int? = null,
    val minAndroidApi: Int? = null,
    val extensions: JsonObject = JsonObject(emptyMap()),
) {
    fun validate() {
        require(id.matches(SegmentPattern)) { "Invalid variant id: $id" }
        require(os.isNotEmpty() && os.distinct().size == os.size) { "Invalid variant OS list" }
        require(arch.isNotEmpty() && arch.distinct().size == arch.size) { "Invalid variant architecture list" }
        require("any" !in arch || arch == listOf("any")) { "Architecture any must be exclusive" }
        validatePackagePath(artifact)
        require(entryClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) { "Invalid entry class" }
        when (platform) {
            "desktop" -> {
                require(os.all { it in DesktopOs } && arch.all { it in DesktopArch || it == "any" }) { "Invalid desktop selector" }
                require(artifact.endsWith(".jar") && packageName == null && minAndroidApi == null && minJava != null && minJava > 0) { "Invalid desktop artifact/runtime" }
            }
            "android" -> {
                require(os == listOf("android") && arch.all { it in AndroidArch || it == "any" }) { "Invalid Android selector" }
                require(artifact.endsWith(".apk") && packageName != null && packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) { "Invalid Android artifact/package" }
                require(minJava == null && minAndroidApi != null && minAndroidApi > 0) { "Invalid Android runtime" }
            }
            else -> error("Unsupported package platform: $platform")
        }
    }

    internal fun overlaps(other: PackageVariant): Boolean = platform == other.platform &&
        os.any { it in other.os } && ("any" in arch || "any" in other.arch || arch.any { it in other.arch })

    internal fun matches(host: PackageHost): Boolean = platform == host.platform && host.os in os &&
        ("any" in arch || host.arch in arch) &&
        (minJava == null || host.javaVersion?.let { it >= minJava } == true) &&
        (minAndroidApi == null || host.androidApi?.let { it >= minAndroidApi } == true)
}

data class PackageHost(
    val platform: String,
    val os: String,
    val arch: String,
    val javaVersion: Int? = null,
    val androidApi: Int? = null,
) {
    fun validate() {
        when (platform) {
            "desktop" -> require(os in DesktopOs && arch in DesktopArch && javaVersion != null && javaVersion > 0 && androidApi == null) { "Invalid desktop host" }
            "android" -> require(os == "android" && arch in AndroidArch && androidApi != null && androidApi > 0 && javaVersion == null) { "Invalid Android host" }
            else -> error("Unsupported host platform: $platform")
        }
    }
}

/** Pure resolution: no downloads, no private class linkage and no automatic enablement. */
fun resolvePackageGraph(manifests: List<PluginPackageManifest>): List<PluginPackageManifest> {
    manifests.forEach { it.validate() }
    require(manifests.map { it.id }.distinct().size == manifests.size) { "Conflicting package identities" }
    val byId = manifests.associateBy { it.id }
    manifests.forEach { manifest ->
        manifest.dependencies.forEach { dependency ->
            require(byId[dependency.id]?.version == dependency.version) { "Missing/conflicting package dependency ${dependency.id}@${dependency.version}" }
        }
    }
    val remaining = manifests.toMutableList()
    val ordered = mutableListOf<PluginPackageManifest>()
    while (remaining.isNotEmpty()) {
        val ready = remaining.firstOrNull { item -> item.dependencies.all { dep -> ordered.any { it.id == dep.id } } }
            ?: error("Cyclic package dependency graph")
        ordered += ready
        remaining.remove(ready)
    }
    return ordered
}

object PluginPackageCodec {
    const val MaxManifestBytes = 1_048_576
    private val json = Json { prettyPrint = true }

    fun decode(bytes: ByteArray): PluginPackageManifest {
        require(bytes.size <= MaxManifestBytes) { "Package manifest exceeds limit" }
        val source = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (error: Exception) {
            throw IllegalArgumentException("Package manifest is not valid UTF-8", error)
        }
        rejectDuplicateJsonKeys(source)
        return json.decodeFromString<PluginPackageManifest>(source).also { it.validate() }
    }

    fun encode(manifest: PluginPackageManifest): ByteArray {
        manifest.validate()
        return json.encodeToString(manifest).encodeToByteArray().also {
            require(it.size <= MaxManifestBytes) { "Package manifest exceeds limit" }
        }
    }
}

fun validatePackageId(id: String) {
    require(id.split('.').size > 1 && id.split('.').all { it.matches(PackageIdSegmentPattern) }) { "Invalid package id: $id" }
}

fun validatePackageVersion(version: String) {
    require(version.matches(VersionPattern)) { "Invalid package SemVer: $version" }
}

fun validatePackagePath(path: String) {
    require(path.isNotEmpty() && path.length <= 1024 && path.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) { "Invalid package path" }
    require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') }) { "Unsafe package path: $path" }
    require(path.split('/').none { it.substringBefore('.').uppercase() in WindowsDevices }) { "Reserved package path: $path" }
}

internal val Sha256Pattern = Regex("[a-f0-9]{64}")
private val PackageIdSegmentPattern = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")
private val SegmentPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
private val VersionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
private val DesktopOs = setOf("windows", "linux", "macos")
private val DesktopArch = setOf("x86_64", "arm64")
private val AndroidArch = setOf("x86_64", "arm64", "x86", "armv7")
private val WindowsDevices = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
