package org.cordis.packager

import java.io.File
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.packageFileSha256
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.io.CleanupMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformFunctionalTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) lateinit var directory: File

    private fun write(path: String, contents: String) = File(directory, path).apply {
        parentFile.mkdirs()
        writeText(contents.trimIndent())
    }

    private fun run(vararg arguments: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*arguments, "--configuration-cache", "--max-workers=2", "--stacktrace", "--offline",
            "--gradle-user-home", System.getProperty("cordis.test.gradleUserHome")).build()

    @Test
    fun kmpJvmTargetProducesLoadablePrivateArtifact() {
        write("settings.gradle", """
            pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral(); google() } }
            rootProject.name = 'kmp-package'
        """)
        write("build.gradle", """
            import org.cordis.packages.PackageTarget
            plugins {
                id 'org.jetbrains.kotlin.multiplatform'
                id 'io.github.meteor149.cordis.packager'
            }
            kotlin { jvm('desktop') { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } } }
            cordisPackages.releases.register('demo') {
                packageId = 'example.kmp'
                packageVersion = '1.0.0'
                variants.register('desktop') {
                    jvmTarget = 'desktop'
                    entryPoint = 'example.Entry'
                    target(new PackageTarget('linux', ['x86'], [64], null, null, null, [] as Set))
                }
            }
        """)
        write("src/commonMain/kotlin/example/Entry.kt", """
            package example
            class Entry { fun value(): String = "from-commonMain" }
        """)
        run("packagePlugins")
        val archive = File(directory, "build/cordis/packages/example.kmp-1.0.0.kplugin")
        val manifest = PluginPackageArchive().inspect(archive, packageFileSha256(archive)).manifest
        assertEquals("jvm", manifest.variants.single().runtime.id)
        val jar = File(directory, "build/cordis/artifacts/demo/desktop/plugin.jar")
        ZipFile(jar).use { zip -> assertFalse(zip.entries().asSequence().any { it.name.startsWith("kotlin/") }) }
        URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("example.Entry")
            assertEquals("from-commonMain", type.getMethod("value").invoke(type.getConstructor().newInstance()))
        }
        assertTrue(run("packagePlugins").output.contains("Reusing configuration cache"))
    }

    @Test
    fun kmpAndroidLibraryBuildsIndependentResourcesDexAssetsAndPrivateDependencies() {
        val sdk = System.getProperty("cordis.test.androidSdk", "")
        assumeTrue(sdk.isNotBlank() && File(sdk, "platforms/android-35/android.jar").isFile, "Android SDK 35 required")
        write("local.properties", "sdk.dir=${sdk.replace('\\', '/')}\n")
        write("gradle.properties", "android.useAndroidX=true\nkotlin.compiler.execution.strategy=in-process\n")
        write("settings.gradle", """
            pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = 'android-package'
            include ':privateAndroid', ':hostApi'
        """)
        write("build.gradle", """
            import org.cordis.packages.PackageTarget
            plugins {
                id 'org.jetbrains.kotlin.multiplatform'
                id 'com.android.library'
                id 'io.github.meteor149.cordis.packager'
            }
            kotlin {
                androidTarget { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
                sourceSets {
                    androidMain.dependencies {
                        implementation project(':privateAndroid')
                        implementation project(':hostApi')
                    }
                }
            }
            android {
                namespace = 'example.library'
                compileSdk = 35
                defaultConfig { minSdk = 26 }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            }
            cordisPackages.releases.register('demo') {
                packageId = 'example.android'
                packageVersion = '1.0.0'
                variants.register('android') {
                    androidVariant = 'debug'
                    androidPackageName = 'example.plugin'
                    entryPoint = 'example.AndroidEntry'
                    hostModules.add('project::hostApi')
                    sharedPackages.add('example.host')
                    target(new PackageTarget('android', ['arm', 'x86'], [32, 64], '8', null, null, [] as Set))
                }
            }
        """)
        write("src/commonMain/kotlin/example/Message.kt", "package example\nfun message() = \"common\"\n")
        write("src/androidMain/kotlin/example/AndroidEntry.kt", """
            package example
            class AndroidEntry : example.host.Contract {
                override fun value(): String = message()
                fun ownResource(): Int = example.library.R.string.own_label
                fun privateResource(): Int = example.privateimpl.Helper.resource()
            }
        """)
        write("src/androidMain/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET" /></manifest>""")
        write("src/androidMain/res/values/strings.xml", """<resources><string name="own_label">own</string></resources>""")
        write("src/androidMain/assets/own.txt", "own asset")
        listOf("privateAndroid" to "example.privateimpl", "hostApi" to "example.host").forEach { (module, namespace) ->
            write("$module/build.gradle", """
                plugins { id 'com.android.library'; id 'io.github.meteor149.cordis.packager' }
                android {
                    namespace = '$namespace'
                    compileSdk = 35
                    defaultConfig { minSdk = 26 }
                    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                }
            """)
            write("$module/src/main/AndroidManifest.xml", "<manifest />")
        }
        write("hostApi/src/main/java/example/host/Contract.java", "package example.host; public interface Contract { String value(); }")
        write("hostApi/src/main/assets/host.txt", "must not ship")
        write("privateAndroid/src/main/java/example/privateimpl/Helper.java", "package example.privateimpl; public class Helper { public static int resource() { return R.string.private_label; } }")
        write("privateAndroid/src/main/res/values/strings.xml", """<resources><string name="private_label">private</string></resources>""")
        write("privateAndroid/src/main/assets/private.txt", "private asset")

        val first = run("packagePlugins")
        assertEquals(TaskOutcome.SUCCESS, first.task(":prepareDemoAndroidApk")?.outcome)
        val apk = File(directory, "build/cordis/artifacts/demo/android/plugin.apk")
        ZipFile(apk).use { zip ->
            assertTrue(zip.getEntry("resources.arsc") != null)
            assertEquals(java.util.zip.ZipEntry.STORED, zip.getEntry("resources.arsc").method)
            assertTrue(zip.getEntry("assets/own.txt") != null)
            assertTrue(zip.getEntry("assets/private.txt") != null, "Missing private dependency asset in $apk")
            assertEquals(null, zip.getEntry("assets/host.txt"))
            val classes = zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .flatMap { dexClasses(zip.getInputStream(it).use { stream -> stream.readBytes() }).asSequence() }.toSet()
            assertTrue("Lexample/AndroidEntry;" in classes)
            assertTrue("Lexample/privateimpl/Helper;" in classes)
            assertTrue("Lexample/library/R${'$'}string;" in classes)
            assertTrue("Lexample/privateimpl/R${'$'}string;" in classes)
            assertFalse(classes.any { it.startsWith("Lkotlin/") || it.startsWith("Lexample/host/") })
        }
        val archive = File(directory, "build/cordis/packages/example.android-1.0.0.kplugin")
        PluginPackageArchive().inspect(archive, packageFileSha256(archive))
        val second = run("packagePlugins")
        assertTrue(second.output.contains("Reusing configuration cache"))
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":prepareDemoAndroidApk")?.outcome)
        val privateBuild = File(directory, "privateAndroid/build.gradle")
        privateBuild.writeText(privateBuild.readText().replace("; id 'io.github.meteor149.cordis.packager'", ""))
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("packagePlugins", "--configuration-cache", "--max-workers=2", "--offline",
                "--gradle-user-home", System.getProperty("cordis.test.gradleUserHome")).buildAndFail()
        assertTrue(rejected.output.contains("Apply io.github.meteor149.cordis.packager to private Android library projects"))
    }
}

internal fun dexClasses(bytes: ByteArray): Set<String> {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun string(index: Int): String {
        var offset = data.getInt(data.getInt(60) + index * 4)
        while (bytes[offset++].toInt() and 128 != 0) { /* UTF-16 length */ }
        val start = offset
        while (bytes[offset].toInt() != 0) offset++
        return bytes.copyOfRange(start, offset).decodeToString()
    }
    return (0 until data.getInt(96)).map { index ->
        val type = data.getInt(data.getInt(100) + index * 32)
        string(data.getInt(data.getInt(68) + type * 4))
    }.toSet()
}
