package org.cordis.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageTargetTest {
    @Test
    fun eachSystemHasIndependentMinimumAndUnknownVersionsRejectConstrainedTargets() {
        for ((system, version) in listOf("windows" to "10.0.26100", "macos" to "14.2", "linux" to "6.10", "android" to "15", "ios" to "17.2")) {
            val host = PackageHost(system, "arm64", systemVersion = version)
            val target = PackageTarget(system, listOf("arm", "x86"), minSystemVersion = version)
            assertTrue(target.matches(host))
            assertFalse(target.matches(host.copy(systemVersion = "0")))
            assertFalse(target.matches(host.copy(systemVersion = null)))
            val bounded = target.copy(minSystemVersion = "0", maxSystemVersion = version)
            assertTrue(bounded.matches(host))
            assertFalse(bounded.matches(host.copy(systemVersion = "999")))
            assertFalse(bounded.copy(minSystemVersion = null).matches(host.copy(systemVersion = null)))
        }
        assertTrue(compareSystemVersions("6.10", "6.9") > 0)
        assertEquals(0, compareSystemVersions("22.04", "22.4.0"))
    }

    @Test
    fun architectureFamilyDoesNotConflateNativeBitness() {
        val target = PackageTarget("android", listOf("arm"), bits = listOf(64))
        val host = PackageHost("android", "arm64")
        assertTrue(target.matches(host))
        assertFalse(target.matches(host.copy(arch = "armv7")))
        assertFalse(target.matches(host.copy(arch = "x86_64")))
        assertTrue(PackageTarget("android", listOf("x86"), listOf(32)).matches(host.copy(arch = "x86")))
    }

    @Test
    fun nativeArtifactsAndSymbolsDoNotRequireJavaOrFileSuffixConventions() {
        for ((system, artifact) in listOf("windows" to "plugin.dll", "macos" to "plugin.dylib", "linux" to "plugin.so", "android" to "native.bin", "ios" to "plugin.framework.zip")) {
            val variant = PackageVariant("native", listOf(PackageTarget(system, listOf("arm"), listOf(64))), PackageRuntime("native-library", "kcode_plugin_init", "1"), artifact)
            val manifest = PluginPackageManifest(id = "example.native", version = "1.0.0", variants = listOf(variant), files = listOf(PackageFile(artifact, 1, "a".repeat(64))))
            assertEquals(manifest, PluginPackageCodec.decode(PluginPackageCodec.encode(manifest)))
            assertEquals(variant, manifest.select(PackageHost(system, "arm64", runtimes = mapOf("native-library" to "1"))))
            assertFailsWith<IllegalArgumentException> { manifest.select(PackageHost(system, "arm64", runtimes = mapOf("jvm" to "21"))) }
            assertFailsWith<IllegalArgumentException> { manifest.copy(formatVersion = 0).validate() }
        }
    }

    @Test
    fun kernelDistributionRuntimeAndFeaturesAreIndependentRequirements() {
        val target = PackageTarget("linux", listOf("x86"), listOf(64), "6.5", "6.8", PackageDistributionTarget("ubuntu", "22.04", "24.04"), setOf("linux.io-uring"))
        val host = PackageHost("linux", "x86_64", "6.8", PackageDistribution("ubuntu", "24.04"), setOf("linux.io-uring"), mapOf("native-library" to "2"))
        val variant = PackageVariant("native", listOf(target), PackageRuntime("native-library", "plugin_init", "2"), "plugin.so")
        assertTrue(variant.matches(host))
        for (ineligible in listOf(host.copy(systemVersion = "6.1"), host.copy(systemVersion = "6.9"), host.copy(distribution = PackageDistribution("ubuntu", "26.04")), host.copy(distribution = PackageDistribution("ubuntu", "20.04")), host.copy(distribution = PackageDistribution("ubuntu")), host.copy(distribution = PackageDistribution("debian", "12")), host.copy(distribution = null), host.copy(features = emptySet()), host.copy(runtimes = mapOf("native-library" to "1")), host.copy(runtimes = emptyMap()))) {
            assertFalse(variant.matches(ineligible))
        }
    }

    @Test
    fun boundedVariantsCanBeDisjointButTouchingInclusiveBoundsOverlap() {
        val older = PackageTarget("linux", listOf("arm"), minSystemVersion = "6", maxSystemVersion = "6.9")
        val newer = older.copy(minSystemVersion = "6.10", maxSystemVersion = null)
        assertFalse(older.overlaps(newer))
        assertTrue(older.overlaps(newer.copy(minSystemVersion = "6.9")))
        assertFailsWith<IllegalArgumentException> { older.copy(minSystemVersion = "7").validate() }
        assertFailsWith<IllegalArgumentException> { PackageDistributionTarget("ubuntu", "24.04", "22.04").validate() }
        val ubuntu = older.copy(minSystemVersion = null, maxSystemVersion = null, distribution = PackageDistributionTarget("ubuntu", "20.04", "22.04"))
        assertFalse(ubuntu.overlaps(ubuntu.copy(distribution = PackageDistributionTarget("ubuntu", "24.04"))))
        val first = PackageVariant("older", listOf(older), PackageRuntime("native-library", "init"), "older.so")
        val second = first.copy(id = "newer", targets = listOf(newer), artifact = "newer.so")
        val manifest = PluginPackageManifest(id = "example.range", version = "1.0.0", variants = listOf(first, second), files = listOf(first, second).map { PackageFile(it.artifact, 1, "a".repeat(64)) })
        assertEquals(first, manifest.select(PackageHost("linux", "arm64", "6.9", runtimes = mapOf("native-library" to "1"))))
        assertEquals(second, manifest.select(PackageHost("linux", "arm64", "6.10", runtimes = mapOf("native-library" to "1"))))
    }

    @Test
    fun overlapUsesRuntimeAndDistributionIdentityButNotMinimumsOrPositiveFeatures() {
        val target = PackageTarget("linux", listOf("x86"), distribution = PackageDistributionTarget("ubuntu", "22.04"))
        val variant = PackageVariant("native", listOf(target), PackageRuntime("native-library", "init"), "plugin.so")
        assertTrue(variant.overlaps(variant.copy(id = "new", targets = listOf(target.copy(minSystemVersion = "6.8")))))
        assertFalse(variant.overlaps(variant.copy(targets = listOf(target.copy(distribution = PackageDistributionTarget("fedora", "40"))))))
        assertFalse(variant.overlaps(variant.copy(runtime = PackageRuntime("wasm", "init"))))
        assertTrue(target.overlaps(target.copy(distribution = null)))
        assertTrue(target.overlaps(target.copy(requiredFeatures = setOf("linux.seccomp"))))
    }

    @Test
    fun invalidSelectorsAndUnknownFieldsAreRejected() {
        for (value in listOf("", "10.x", "10-beta", "1..2", "1.2.3.4.5", "9999999999")) {
            assertFailsWith<IllegalArgumentException> { validateSystemVersion(value) }
        }
        for (target in listOf(PackageTarget("mobile", listOf("arm")), PackageTarget("ios", listOf("any")), PackageTarget("linux", listOf("arm"), bits = listOf(16)), PackageTarget("ios", listOf("arm"), distribution = PackageDistributionTarget("ubuntu")))) {
            assertFailsWith<IllegalArgumentException> { target.validate() }
        }
        val source = """{"formatVersion":1,"id":"example.invalid","version":"1.0.0","variants":[{"id":"invalid","platform":"desktop"}],"files":[]}"""
        assertFailsWith<IllegalArgumentException> { PluginPackageCodec.decode(source.encodeToByteArray()) }
    }
}
