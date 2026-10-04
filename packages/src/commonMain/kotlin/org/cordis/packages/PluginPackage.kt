package org.cordis.packages

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Distribution metadata only. Hosts own publisher trust, configuration and installation state. */
@Serializable
data class PluginPackageManifest(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
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
            if (matches.isEmpty()) "Package '$id' has no compatible variant for ${host.system}/${host.arch}"
            else "Package '$id' has ambiguous variants: ${matches.map { it.id }}"
        }
        return matches.single()
    }
}

@Serializable
data class PackageDependency(val id: String, val version: String)

@Serializable
data class PackageFile(val path: String, val size: Long, val sha256: String)

/** Loader identities and entry points are interpreted by the host, never by the archive layer. */
@Serializable
data class PackageRuntime(
    val id: String,
    val entryPoint: String,
    val minVersion: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
) {
    fun validate() {
        require(id.matches(IdentityPattern)) { "Invalid runtime identity" }
        require(entryPoint.isNotBlank() && entryPoint.length <= 1024 && entryPoint.none { it.code < 32 }) { "Invalid entry point" }
        minVersion?.let { validateSystemVersion(it) }
    }
}

@Serializable
data class PackageVariant(
    val id: String,
    val targets: List<PackageTarget>,
    val runtime: PackageRuntime,
    val artifact: String,
    val extensions: JsonObject = JsonObject(emptyMap()),
) {
    fun validate() {
        require(id.matches(SegmentPattern)) { "Invalid variant id: $id" }
        require(targets.isNotEmpty()) { "Empty variant targets" }
        targets.forEach { it.validate() }
        targets.forEachIndexed { index, left ->
            require(targets.drop(index + 1).none { left.overlaps(it) }) { "Overlapping variant targets" }
        }
        runtime.validate()
        validatePackagePath(artifact)
    }

    fun overlaps(other: PackageVariant): Boolean = runtime.id == other.runtime.id &&
        targets.any { left -> other.targets.any { left.overlaps(it) } }

    fun matches(host: PackageHost): Boolean {
        host.validate()
        val runtimeVersion = host.runtimes[runtime.id] ?: return false
        return targets.any { it.matches(host) } && (runtime.minVersion == null ||
            compareSystemVersions(runtimeVersion, runtime.minVersion) >= 0)
    }
}

/** Actual process architecture, system facts and available loaders; no JVM dependency. */
data class PackageHost(
    val system: String,
    val arch: String,
    val systemVersion: String? = null,
    val distribution: PackageDistribution? = null,
    val features: Set<String> = emptySet(),
    val runtimes: Map<String, String> = emptyMap(),
) {
    fun validate() {
        require(system in PackageSystems) { "Unsupported host system: $system" }
        packageArchitectureFamily(arch)
        systemVersion?.let { validateSystemVersion(it) }
        distribution?.let {
            require(system == "linux") { "Distribution facts require Linux" }
            it.validate()
        }
        require(features.all { it.matches(IdentityPattern) }) { "Invalid system feature identity" }
        runtimes.forEach { (id, version) ->
            require(id.matches(IdentityPattern)) { "Invalid host runtime identity" }
            validateSystemVersion(version)
        }
    }
}

data class PackageDistribution(val id: String, val version: String? = null) {
    fun validate() {
        require(id.matches(SegmentPattern)) { "Invalid distribution identity" }
        version?.let { validateSystemVersion(it) }
    }
}

@Serializable
data class PackageDistributionTarget(val id: String, val minVersion: String? = null, val maxVersion: String? = null) {
    fun validate() {
        require(id.matches(SegmentPattern)) { "Invalid distribution identity" }
        validateSystemVersionRange(minVersion, maxVersion)
    }
}

@Serializable
data class PackageTarget(
    val system: String,
    val arch: List<String>,
    val bits: List<Int> = listOf(32, 64),
    val minSystemVersion: String? = null,
    val maxSystemVersion: String? = null,
    val distribution: PackageDistributionTarget? = null,
    val requiredFeatures: Set<String> = emptySet(),
) {
    fun validate() {
        require(system in PackageSystems) { "Unsupported package system: $system" }
        require(arch.isNotEmpty() && arch.distinct().size == arch.size && arch.all { it in setOf("arm", "x86") }) { "Invalid architecture families" }
        require(bits.isNotEmpty() && bits.distinct().size == bits.size && bits.all { it == 32 || it == 64 }) { "Invalid architecture bitness" }
        validateSystemVersionRange(minSystemVersion, maxSystemVersion)
        distribution?.let {
            require(system == "linux") { "Distribution constraints require Linux" }
            it.validate()
        }
        require(requiredFeatures.all { it.matches(IdentityPattern) }) { "Invalid required system feature" }
    }

    fun overlaps(other: PackageTarget): Boolean = system == other.system && arch.any { it in other.arch } &&
        bits.any { it in other.bits } && systemVersionRangesOverlap(minSystemVersion, maxSystemVersion, other.minSystemVersion, other.maxSystemVersion) &&
        (distribution == null || other.distribution == null || (distribution.id == other.distribution.id &&
            systemVersionRangesOverlap(distribution.minVersion, distribution.maxVersion, other.distribution.minVersion, other.distribution.maxVersion)))

    fun matches(host: PackageHost): Boolean {
        validate()
        host.validate()
        return system == host.system && packageArchitectureFamily(host.arch) in arch &&
            packageArchitectureBits(host.arch) in bits && systemVersionInRange(host.systemVersion, minSystemVersion, maxSystemVersion) &&
            (distribution == null || host.distribution?.let { actual -> actual.id == distribution.id &&
                systemVersionInRange(actual.version, distribution.minVersion, distribution.maxVersion) } == true) &&
            host.features.containsAll(requiredFeatures)
    }
}

private val PackageSystems = setOf("windows", "macos", "linux", "android", "ios")

fun packageArchitectureFamily(architecture: String): String = when (architecture) {
    "armv7", "arm64" -> "arm"
    "x86", "x86_64" -> "x86"
    else -> error("Unsupported process architecture: $architecture")
}

fun packageArchitectureBits(architecture: String): Int = when (architecture) {
    "armv7", "x86" -> 32
    "arm64", "x86_64" -> 64
    else -> error("Unsupported process architecture: $architecture")
}

fun validateSystemVersion(version: String) {
    require(version.matches(Regex("[0-9]{1,9}(?:\\.[0-9]{1,9}){0,3}"))) { "Invalid numeric system version: $version" }
}

fun validateSystemVersionRange(minimum: String?, maximum: String?) {
    minimum?.let { validateSystemVersion(it) }
    maximum?.let { validateSystemVersion(it) }
    require(minimum == null || maximum == null || compareSystemVersions(minimum, maximum) <= 0) { "Inverted system version range" }
}

fun systemVersionInRange(version: String?, minimum: String?, maximum: String?): Boolean {
    validateSystemVersionRange(minimum, maximum)
    if (minimum == null && maximum == null) return true
    if (version == null) return false
    return (minimum == null || compareSystemVersions(version, minimum) >= 0) &&
        (maximum == null || compareSystemVersions(version, maximum) <= 0)
}

private fun systemVersionRangesOverlap(leftMin: String?, leftMax: String?, rightMin: String?, rightMax: String?): Boolean =
    (leftMax == null || rightMin == null || compareSystemVersions(leftMax, rightMin) >= 0) &&
        (rightMax == null || leftMin == null || compareSystemVersions(rightMax, leftMin) >= 0)

/** Missing numeric components are zero; version strings are never compared lexically. */
fun compareSystemVersions(left: String, right: String): Int {
    validateSystemVersion(left)
    validateSystemVersion(right)
    val leftParts = left.split('.').map { it.toInt() }
    val rightParts = right.split('.').map { it.toInt() }
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val comparison = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
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
private val IdentityPattern = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")
private val VersionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
private val WindowsDevices = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
