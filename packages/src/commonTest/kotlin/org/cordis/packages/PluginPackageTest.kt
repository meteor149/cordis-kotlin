package org.cordis.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginPackageTest {
    private val desktop = PackageVariant("desktop", "desktop", listOf("windows", "linux", "macos"), listOf("any"), "desktop/plugin.jar", "example.Plugin", minJava = 17)
    private val android = PackageVariant("android", "android", listOf("android"), listOf("any"), "android/plugin.apk", "example.Plugin", "example.plugin", minAndroidApi = 35)
    private fun manifest(variants: List<PackageVariant> = listOf(desktop, android)) = PluginPackageManifest(
        id = "example.plugin", version = "1.0.0", variants = variants,
        files = variants.map { PackageFile(it.artifact, 1, "a".repeat(64)) },
    )

    @Test
    fun dualTargetAndSingleTargetSelection() {
        val windows = PackageHost("desktop", "windows", "x86_64", javaVersion = 21)
        val mobile = PackageHost("android", "android", "arm64", androidApi = 35)
        assertEquals(desktop, manifest().select(windows))
        assertEquals(android, manifest().select(mobile))
        assertFailsWith<IllegalArgumentException> { manifest(listOf(android)).select(windows) }
        assertFailsWith<IllegalArgumentException> { manifest().select(mobile.copy(androidApi = 34)) }
        assertFailsWith<IllegalArgumentException> { manifest().select(windows) { false } }
    }

    @Test
    fun overlappingAndDisjointVariants() {
        assertFailsWith<IllegalArgumentException> { manifest(listOf(desktop, desktop.copy(id = "other", arch = listOf("arm64")))).validate() }
        val split = manifest(listOf(desktop.copy(os = listOf("windows")), desktop.copy(id = "linux", os = listOf("linux"), artifact = "linux/plugin.jar")))
        split.validate()
    }

    @Test
    fun codecRejectsDuplicatesUnknownsAndMalformedUtf8() {
        val bytes = PluginPackageCodec.encode(manifest())
        assertEquals(manifest(), PluginPackageCodec.decode(bytes))
        val source = bytes.decodeToString()
        assertFailsWith<IllegalArgumentException> { PluginPackageCodec.decode(source.replaceFirst("{", "{\"id\":\"other.plugin\",").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { PluginPackageCodec.decode(source.replaceFirst("{", "{\"extra\":true,").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { PluginPackageCodec.decode(byteArrayOf(0xc3.toByte())) }
    }

    @Test
    fun exactDependenciesAndCycles() {
        val first = manifest().copy(id = "example.first")
        val second = manifest().copy(id = "example.second", dependencies = listOf(PackageDependency(first.id, first.version)))
        assertEquals(listOf(first, second), resolvePackageGraph(listOf(second, first)))
        assertFailsWith<IllegalArgumentException> { resolvePackageGraph(listOf(second)) }
        assertFailsWith<IllegalArgumentException> { resolvePackageGraph(listOf(first, second.copy(dependencies = listOf(PackageDependency(first.id, "2.0.0"))))) }
        assertFailsWith<IllegalStateException> { resolvePackageGraph(listOf(first.copy(dependencies = listOf(PackageDependency(second.id, second.version))), second)) }
    }

    @Test
    fun packageIdsPreserveCaseAndDependencyIdentity() {
        val provider = manifest().copy(id = "provider.llm.koog.OpenAI")
        val consumer = manifest().copy(id = "example.Consumer", dependencies = listOf(PackageDependency(provider.id, provider.version)))
        assertEquals(provider, PluginPackageCodec.decode(PluginPackageCodec.encode(provider)))
        assertEquals(listOf(provider, consumer), resolvePackageGraph(listOf(consumer, provider)))
        assertFailsWith<IllegalArgumentException> {
            resolvePackageGraph(listOf(consumer, provider.copy(id = provider.id.lowercase())))
        }
        listOf("example..Plugin", "example.Plugin/name", "example.Plugin_Name", "example.Plug\u0131n", "example.-Plugin").forEach { id ->
            assertFailsWith<IllegalArgumentException> { validatePackageId(id) }
        }
    }

    @Test
    fun unsafePathsAndSemverAreRejected() {
        listOf("../a", "/a", "C:/a", "a\\b", "a//b", "a/./b", "a/CON.txt", "a/b.", "a/b ").forEach { path ->
            assertFailsWith<IllegalArgumentException> { validatePackagePath(path) }
        }
        listOf("1.0", "01.0.0", "1.0.0-01", "latest").forEach { version ->
            assertFailsWith<IllegalArgumentException> { validatePackageVersion(version) }
        }
        validatePackageVersion("1.2.0-rc.1+build.2")
    }
}
