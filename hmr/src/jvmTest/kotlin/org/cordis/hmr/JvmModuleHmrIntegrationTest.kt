package org.cordis.hmr

import dev.dynamic.hmr.JvmHmrBrokenPlugin
import dev.dynamic.hmr.JvmHmrPluginV1
import dev.dynamic.hmr.JvmHmrPluginV2
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.loader.Entry
import org.cordis.loader.EntryOptions
import org.cordis.loader.JvmModuleDescriptor
import org.cordis.loader.JvmModuleLoader
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmModuleHmrIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @AfterEach
    fun clearProbe() {
        System.clearProperty(PROBE_PROPERTY)
    }

    @Test
    fun `real JVM jars hot reload transactionally and restore the old runtime after apply failure`() {
        runBlocking {
            val firstJar = fixtureJar("plugin-v1", JvmHmrPluginV1::class.java)
            val secondJar = fixtureJar("plugin-v2", JvmHmrPluginV2::class.java)
            val brokenJar = fixtureJar("plugin-broken", JvmHmrBrokenPlugin::class.java)
            val modules = JvmModuleLoader(temporary.toFile())
            modules.register(descriptor("1", JvmHmrPluginV1::class.java.name, firstJar))

            val root = Context()
            val loader = Loader(root, LoaderConfig(temporary.toUri().toString()))
            loader.internal = modules
            loader.create(EntryOptions(id = "dynamic", name = modules.moduleUrl(MODULE_ID), config = Unit))
            val entry = loader.resolve("dynamic")
            val firstPlugin = modules.activeModule(MODULE_ID)?.plugin
            assertEquals("v1", System.getProperty(PROBE_PROPERTY), "initial generation did not apply")

            val hmr = Hmr(root, HmrConfig(base = temporary.toString(), debounce = 60_000))
            modules.register(descriptor("2", JvmHmrPluginV2::class.java.name, secondJar))
            hmr.stash(secondJar.toFile().canonicalFile.toPath().toUri().toString())

            assertTrue(hmr.partialReload(), "second generation reload failed")
            val secondPlugin = modules.activeModule(MODULE_ID)?.plugin
            assertEquals("v2", System.getProperty(PROBE_PROPERTY), "second generation did not apply")
            assertNotSame(firstPlugin, secondPlugin, "reload retained the first plugin instance")
            assertTrue(root.registry.has(requireNotNull(secondPlugin)), "registry lost the second generation")
            assertSame(entry, entry.fiber?.attributes?.get(Entry.ATTRIBUTE), "entry association was not restored")

            modules.register(descriptor("3", JvmHmrBrokenPlugin::class.java.name, brokenJar))
            hmr.stash(brokenJar.toFile().canonicalFile.toPath().toUri().toString())

            assertFalse(hmr.partialReload(), "broken generation unexpectedly committed")
            assertEquals("v2", System.getProperty(PROBE_PROPERTY), "rollback did not reapply the second generation")
            assertSame(secondPlugin, modules.activeModule(MODULE_ID)?.plugin, "rollback changed the active module")
            assertTrue(root.registry.has(requireNotNull(secondPlugin)), "rollback lost the active registry entry")

            hmr.stop()
            loader.root.stop()
            modules.close()
        }
    }

    private fun descriptor(version: String, entryClass: String, jar: Path) = JvmModuleDescriptor(
        id = MODULE_ID,
        version = version,
        entryClass = entryClass,
        file = jar.toFile(),
        expectedSha256 = sha256(jar),
    )

    private fun fixtureJar(name: String, type: Class<*>): Path {
        val resourceName = type.name.replace('.', '/') + ".class"
        val bytes = requireNotNull(type.classLoader.getResourceAsStream(resourceName)) {
            "missing compiled fixture $resourceName"
        }.use { it.readAllBytes() }
        return temporary.resolve("$name.jar").also { jar ->
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(JarEntry(resourceName))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val MODULE_ID = "hmr-integration"
        const val PROBE_PROPERTY = "cordis.jvm.hmr.integration.value"
    }
}
