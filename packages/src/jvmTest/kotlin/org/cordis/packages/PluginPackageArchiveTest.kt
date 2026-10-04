package org.cordis.packages

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginPackageArchiveTest {
    private fun fixture(root: File): PluginPackageManifest {
        File(root, "desktop").mkdirs()
        File(root, "android").mkdirs()
        File(root, "desktop/plugin.jar").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "android/plugin.apk").writeBytes(byteArrayOf(4, 5, 6))
        return PluginPackageManifest(
            id = "example.plugin", version = "1.0.0",
            variants = listOf(
                PackageVariant("desktop", "desktop", listOf("windows"), listOf("any"), "desktop/plugin.jar", "example.Plugin", minJava = 17),
                PackageVariant("android", "android", listOf("android"), listOf("any"), "android/plugin.apk", "example.Plugin", "example.plugin", minAndroidApi = 35),
            ),
            files = listOf("desktop/plugin.jar", "android/plugin.apk").map { path ->
                val file = File(root, path)
                PackageFile(path, file.length(), packageFileSha256(file))
            },
        )
    }

    @Test
    fun deterministicPackingDeploymentReuseAndRestartVerification() {
        val root = Files.createTempDirectory("cordis-package-test").toFile()
        try {
            val payload = File(root, "source").also { it.mkdirs() }
            val manifest = fixture(payload)
            val archive = PluginPackageArchive()
            val first = File(root, "first.kplugin")
            val second = File(root, "second.kplugin")
            val sha = archive.pack(manifest, payload, first)
            assertEquals(sha, archive.pack(manifest, payload, second))
            assertEquals(manifest, archive.inspect(first, sha).manifest)
            val deployment = archive.deploy(first, sha, File(root, "installed"))
            assertTrue(deployment.created)
            assertEquals(manifest, archive.verifyDeployment(deployment.directory, sha).manifest)
            assertFalse(archive.deploy(first, sha, File(root, "installed")).created)
            assertEquals(listOf<Byte>(1, 2, 3), deployment.artifact(manifest.variants.first()).readBytes().toList())
            deployment.artifact(manifest.variants.last()).writeBytes(byteArrayOf(9))
            assertFailsWith<IllegalArgumentException> { archive.verifyDeployment(deployment.directory, sha) }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun verifiesUnselectedVariantAndLeavesNoStagingOnFailure() {
        val root = Files.createTempDirectory("cordis-package-corrupt").toFile()
        try {
            val payload = File(root, "source").also { it.mkdirs() }
            val manifest = fixture(payload)
            val target = File(root, "bad.kplugin")
            rawZip(target, mapOf(
                "plugin.json" to PluginPackageCodec.encode(manifest),
                "desktop/plugin.jar" to byteArrayOf(1, 2, 3),
                "android/plugin.apk" to byteArrayOf(7, 8, 9),
            ))
            val installed = File(root, "installed")
            assertFailsWith<IllegalArgumentException> { PluginPackageArchive().deploy(target, packageFileSha256(target), installed) }
            assertTrue(installed.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectsUnsafeUndeclaredCollidingAndSymlinkEntries() {
        val root = Files.createTempDirectory("cordis-package-unsafe").toFile()
        try {
            val source = File(root, "source").also { it.mkdirs() }
            val manifest = fixture(source)
            listOf("../escape", "C:/escape", "desktop/Plugin.jar", "extra").forEachIndexed { index, unsafe ->
                val target = File(root, "bad$index.kplugin")
                rawZip(target, mapOf(
                    "plugin.json" to PluginPackageCodec.encode(manifest),
                    "desktop/plugin.jar" to byteArrayOf(1, 2, 3),
                    "android/plugin.apk" to byteArrayOf(4, 5, 6),
                    unsafe to byteArrayOf(9),
                ))
                assertFailsWith<IllegalArgumentException> { PluginPackageArchive().inspect(target, packageFileSha256(target)) }
            }
            val target = File(root, "symlink.kplugin")
            PluginPackageArchive().pack(manifest, source, target)
            RandomAccessFile(target, "rw").use { file ->
                val bytes = ByteArray(file.length().toInt()).also { file.readFully(it) }
                val central = (0 until bytes.size - 46).first { offset ->
                    bytes.sliceArray(offset..offset + 3).contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
                }
                file.seek((central + 40).toLong())
                file.write(byteArrayOf(0xff.toByte(), 0xa1.toByte()))
            }
            assertFailsWith<IllegalArgumentException> { PluginPackageArchive().inspect(target, packageFileSha256(target)) }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun boundsExpandedPayloadAndRequiresExpectedDigest() {
        val root = Files.createTempDirectory("cordis-package-limits").toFile()
        try {
            val source = File(root, "source").also { it.mkdirs() }
            val target = File(root, "plugin.kplugin")
            val sha = PluginPackageArchive().pack(fixture(source), source, target)
            assertFailsWith<IllegalArgumentException> { PluginPackageArchive().inspect(target, "0".repeat(64)) }
            assertFailsWith<IllegalArgumentException> { PluginPackageArchive(PackageArchiveLimits(maxEntryBytes = 2)).inspect(target, sha) }
            assertFailsWith<IllegalArgumentException> { PluginPackageArchive(PackageArchiveLimits(maxEntries = 2)).inspect(target, sha) }
        } finally { root.deleteRecursively() }
    }

    private fun rawZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
