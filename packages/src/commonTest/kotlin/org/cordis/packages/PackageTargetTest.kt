package org.cordis.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageTargetTest {
    @Test
    fun eachSystemHasIndependentMinimumAndUnknownVersionsRejectConstrainedTargets() {
        val windows = PackageHost("desktop", "windows", "x86_64", javaVersion = 21, systemVersion = "10.0")
        val mac = PackageHost("desktop", "macos", "arm64", javaVersion = 21, systemVersion = "14.2")
        val linux = PackageHost("desktop", "linux", "x86_64", javaVersion = 21, systemVersion = "6.10")
        val android = PackageHost("android", "android", "arm64", androidApi = 35, systemVersion = "15")
        val ios = PackageHost("ios", "ios", "arm64", systemVersion = "17.2")
        for (host in listOf(windows, mac, linux, android, ios)) {
            val target = PackageTarget(host.os, listOf("arm", "x86"), minSystemVersion = host.systemVersion)
            assertTrue(target.matches(host))
            assertFalse(target.matches(host.copy(systemVersion = "0")))
            assertFalse(target.matches(host.copy(systemVersion = null)))
        }
        assertFalse(PackageTarget("macos", listOf("arm")).matches(windows))
        assertTrue(PackageTarget("linux", listOf("x86"), minSystemVersion = "6.9").matches(linux))
        assertEquals(0, compareSystemVersions("10", "10.0.0"))
    }

    @Test
    fun architectureFamilyDoesNotConflateNativeBitness() {
        val target = PackageTarget("android", listOf("arm"), bits = listOf(64))
        val arm = PackageHost("android", "android", "arm64", androidApi = 35)
        assertTrue(target.matches(arm))
        assertFalse(target.matches(arm.copy(arch = "armv7")))
        assertFalse(target.matches(arm.copy(arch = "x86_64")))
        assertTrue(PackageTarget("android", listOf("x86"), bits = listOf(32)).matches(arm.copy(arch = "x86")))
    }

    @Test
    fun v2RoundTripsAndRejectsMixedLegacySelectorsAndOverlaps() {
        val variant = PackageVariant(id = "desktop", artifact = "plugin.jar", entryClass = "example.Plugin",
            minJava = 17, targets = listOf(PackageTarget("windows", listOf("x86"), minSystemVersion = "10")))
        val manifest = PluginPackageManifest(formatVersion = 2, id = "example.plugin", version = "1.0.0",
            variants = listOf(variant), files = listOf(PackageFile("plugin.jar", 1, "a".repeat(64))))
        assertEquals(manifest, PluginPackageCodec.decode(PluginPackageCodec.encode(manifest)))
        assertEquals(variant, manifest.select(PackageHost("desktop", "windows", "x86_64", javaVersion = 21, systemVersion = "10.0")))
        assertFailsWith<IllegalArgumentException> { manifest.copy(variants = listOf(variant.copy(platform = "desktop"))).validate() }
        assertFailsWith<IllegalArgumentException> { manifest.copy(variants = listOf(variant, variant.copy(id = "other"))).validate() }
        assertFailsWith<IllegalArgumentException> { manifest.copy(formatVersion = 1).validate() }
        assertFailsWith<IllegalArgumentException> { variant.copy(targets = variant.targets + PackageTarget("android", listOf("arm"))).validate() }
    }

    @Test
    fun invalidTargetsAndSystemVersionsAreRejected() {
        listOf("", "10.x", "10-beta", "01.2", "1..2", "1.2.3.4.5", "9999999999").forEach {
            assertFailsWith<IllegalArgumentException> { validateSystemVersion(it) }
        }
        for (target in listOf(PackageTarget("mobile", listOf("arm")), PackageTarget("ios", listOf("any")),
            PackageTarget("windows", listOf("x86", "x86")), PackageTarget("linux", listOf("arm"), bits = listOf(16)))) {
            assertFailsWith<IllegalArgumentException> { target.validate() }
        }
    }
}
