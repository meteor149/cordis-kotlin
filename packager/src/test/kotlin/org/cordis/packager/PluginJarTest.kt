package org.cordis.packager

import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PluginJarTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun mergesDistinctLicenseNoticesAndDeduplicatesIdenticalCopies() {
        val first = File(directory, "first.jar")
        val second = File(directory, "second.jar")
        val output = File(directory, "plugin.jar")
        writeZip(first, mapOf("META-INF/NOTICE" to "Koog notice\n".encodeToByteArray()))
        writeZip(second, mapOf("META-INF/NOTICE" to "Ktor notice\n".encodeToByteArray()))

        val contents = JarContents(emptySet(), emptySet())
        contents.addJar(first)
        contents.addJar(second)
        contents.addJar(first)
        contents.write(output)

        val notice = ZipFile(output).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/NOTICE")).bufferedReader().readText()
        }
        assertContains(notice, "Koog notice")
        assertContains(notice, "Ktor notice")
        assertEquals(1, Regex("Koog notice").findAll(notice).count())
    }

    @Test
    fun mergesDependencyMetadataFromPrivateDependencies() {
        val first = File(directory, "first.jar")
        val second = File(directory, "second.jar")
        val output = File(directory, "plugin.jar")
        writeZip(first, mapOf("META-INF/DEPENDENCIES" to "Dependency: kotlin-stdlib\n".encodeToByteArray()))
        writeZip(second, mapOf("META-INF/DEPENDENCIES" to "Dependency: ktor-client\n".encodeToByteArray()))

        val contents = JarContents(emptySet(), emptySet())
        contents.addJar(first)
        contents.addJar(second)
        contents.write(output)

        val dependencies = ZipFile(output).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/DEPENDENCIES")).bufferedReader().readText()
        }
        assertContains(dependencies, "kotlin-stdlib")
        assertContains(dependencies, "ktor-client")
    }

    @Test
    fun pluginArchiveDropsCompilerOnlyEntries() {
        val input = File(directory, "multi-release.jar")
        val output = File(directory, "plugin.jar")
        val versionedClass = "META-INF/versions/11/example/Versioned.class"
        val moduleMetadata = "META-INF/core_release.kotlin_module"
        writeZip(input, mapOf(
            "example/Entry.class" to byteArrayOf(1),
            versionedClass to byteArrayOf(2),
            moduleMetadata to byteArrayOf(3),
        ))

        val contents = JarContents(
            emptySet(),
            emptySet(),
            ignoreMultiReleaseEntries = true,
        )
        contents.addJar(input)
        contents.write(output)

        ZipFile(output).use { zip ->
            assertEquals(null, zip.getEntry(versionedClass))
            assertEquals(null, zip.getEntry(moduleMetadata))
            assertEquals(1, zip.getInputStream(zip.getEntry("example/Entry.class")).read())
        }
    }
}
