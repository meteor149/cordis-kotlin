package org.cordis.packager

import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.packageFileSha256
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagingFunctionalTest {
    @TempDir lateinit var directory: File

    private fun fixture(extra: String = "") {
        File(directory, "settings.gradle").writeText("rootProject.name = 'packaging-fixture'\n")
        writeZip(File(directory, "input.jar"), mapOf("example/Entry.class" to byteArrayOf(1)))
        File(directory, "build.gradle").writeText("""
            import org.cordis.packages.PackageTarget
            import org.cordis.packager.PluginCatalogTask
            plugins { id 'io.github.meteor149.cordis.packager' }
            cordisPackages {
                releases {
                    demo {
                        packageId = 'example.demo'
                        packageVersion = '1.2.3'
                        extensionsFile = layout.projectDirectory.file('extensions.json')
                        variants {
                            desktop {
                                artifact = layout.projectDirectory.file('input.jar')
                                entryPoint = 'example.Entry'
                                runtimeId = 'jvm'
                                target(new PackageTarget('linux', ['x86'], [64], null, null, null, [] as Set))
                            }
                        }
                    }
                }
            }
            $extra
        """.trimIndent())
        File(directory, "extensions.json").writeText("""{"example":{"abi":"first"}}""")
    }

    private fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace", "--offline").build()

    @Test
    fun packsWithoutKotlinOrAndroidAndReusesConfigurationCache() {
        fixture()
        val first = run("packagePlugins")
        assertEquals(TaskOutcome.SUCCESS, first.task(":packageDemo")?.outcome)
        val file = File(directory, "build/cordis/packages/example.demo-1.2.3.kplugin")
        val digest = packageFileSha256(file)
        assertEquals(digest, File(file.path + ".sha256").readText().trim())
        val manifest = PluginPackageArchive().inspect(file, digest).manifest
        assertEquals("example.demo", manifest.id)
        assertEquals("first", (manifest.extensions["example"] as kotlinx.serialization.json.JsonObject)["abi"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        val second = run("packagePlugins")
        assertTrue(second.output.contains("Reusing configuration cache"))
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":packageDemo")?.outcome)
        File(directory, "extensions.json").writeText("""{"example":{"abi":"second"}}""")
        val third = run("packagePlugins")
        assertEquals(TaskOutcome.SUCCESS, third.task(":packageDemo")?.outcome)
        assertTrue(digest != packageFileSha256(file))
    }

    @Test
    fun bundleConsumesPublishedPackageAndRemovesStaleFiles() {
        fixture("""
            def incoming = configurations.create('bundled') {
                canBeConsumed = false
                canBeResolved = true
            }
            dependencies { bundled project(path: ':', configuration: 'cordisDemoElements') }
            tasks.register('stage', PluginCatalogTask) {
                archives.from(incoming)
                destinationDirectory = layout.buildDirectory.dir('catalog')
            }
        """.trimIndent())
        run("stage")
        val catalog = File(directory, "build/catalog")
        val records = Json.parseToJsonElement(File(catalog, "index.json").readText()) as kotlinx.serialization.json.JsonArray
        assertEquals(1, records.size)
        assertTrue(File(catalog, "example.demo-1.2.3.kplugin").isFile)
        File(catalog, "stale.kplugin").writeText("stale")
        run("stage", "--rerun-tasks")
        assertFalse(File(catalog, "stale.kplugin").exists())
    }

    @Test
    fun privateJarMergesServicesAndExcludesSharedClasses() {
        fixture("""
            tasks.register('prepare', org.cordis.packager.PrepareJvmPluginTask) {
                jars.from('own.jar', 'dependency.jar')
                sharedPackages = ['kotlin'] as Set
                sharedClasses = ['example.Api'] as Set
                entryPoint = 'example.Entry'
                outputFile = layout.buildDirectory.file('prepared.jar')
            }
        """.trimIndent())
        writeZip(File(directory, "own.jar"), mapOf(
            "example/Entry.class" to byteArrayOf(1), "example/Api.class" to byteArrayOf(2),
            "META-INF/services/example.Service" to "example.First\n".encodeToByteArray(),
        ))
        writeZip(File(directory, "dependency.jar"), mapOf(
            "kotlin/Unit.class" to byteArrayOf(3), "example/Private.class" to byteArrayOf(4),
            "META-INF/services/example.Service" to "example.Second\nexample.First # duplicate\n".encodeToByteArray(),
        ))
        run("prepare")
        ZipFile(File(directory, "build/prepared.jar")).use { zip ->
            assertEquals(null, zip.getEntry("kotlin/Unit.class"))
            assertEquals(null, zip.getEntry("example/Api.class"))
            assertTrue(zip.getEntry("example/Private.class") != null)
            assertEquals("example.First\nexample.Second\n", zip.getInputStream(zip.getEntry("META-INF/services/example.Service")).reader().readText())
        }
    }

    @Test
    fun invalidMetadataPreservesPreviouslyBuiltRelease() {
        fixture()
        run("packagePlugins")
        val archive = File(directory, "build/cordis/packages/example.demo-1.2.3.kplugin")
        val original = archive.readBytes()
        File(directory, "extensions.json").writeText("[]")
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("packagePlugins", "--configuration-cache", "--offline").buildAndFail()
        assertTrue(original.contentEquals(archive.readBytes()))
        assertEquals(packageFileSha256(archive), File(archive.path + ".sha256").readText().trim())
    }

    @Test
    fun rejectsNativeTargetsWithoutMatchingPayloadAbi() {
        val apk = File(directory, "native.apk")
        writeZip(apk, mapOf("lib/arm64-v8a/libprivate.so" to byteArrayOf(1)))
        val variant = org.cordis.packages.PackageVariant(
            "android", listOf(org.cordis.packages.PackageTarget("android", listOf("arm"), listOf(64))),
            org.cordis.packages.PackageRuntime("android-dex", "example.Entry"), "plugin.apk",
        )
        validateNativeTargets(apk, variant)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            validateNativeTargets(apk, variant.copy(targets = listOf(org.cordis.packages.PackageTarget("android", listOf("arm"), listOf(32, 64)))))
        }
    }
}
