package org.cordis.loader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidModuleDescriptorTest {
    @Test
    fun `descriptor accepts an app-private plugin with a valid contract`() {
        val root = temporaryRoot()
        val plugin = File(root, "plugins/example/plugin.apk").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("dex fixture")
        }
        val descriptor = AndroidModuleDescriptor(
            id = "example.plugin",
            version = "1.2.0",
            entryClass = "com.example.ExamplePlugin",
            file = plugin,
            expectedSha256 = androidModuleSha256(plugin),
            dependencies = listOf("shared.api"),
            sharedHostPackages = setOf("com.example.host.api"),
        )

        validateAndroidModuleDescriptor(descriptor, root)
        assertEquals("af61eff559f4890b5f91b9d432da51288b15af395c1aa820562a2550b4c8db0c", descriptor.expectedSha256)
        root.deleteRecursively()
    }

    @Test
    fun `descriptor rejects code outside the trusted root`() {
        val root = temporaryRoot()
        val outside = kotlin.io.path.createTempFile("cordis-plugin-outside", ".apk").toFile().apply {
            writeText("untrusted")
        }
        val descriptor = AndroidModuleDescriptor(
            id = "outside",
            version = "1",
            entryClass = "com.example.OutsidePlugin",
            file = outside,
            expectedSha256 = androidModuleSha256(outside),
        )

        assertFailsWith<IllegalArgumentException> { validateAndroidModuleDescriptor(descriptor, root) }
        outside.delete()
        root.deleteRecursively()
    }

    @Test
    fun `descriptor rejects self-dependency`() {
        val root = temporaryRoot()
        val plugin = File(root, "plugin.apk").apply { writeText("fixture") }

        assertFailsWith<IllegalArgumentException> {
            validateAndroidModuleDescriptor(
                AndroidModuleDescriptor(
                    id = "self",
                    version = "1",
                    entryClass = "com.example.SelfPlugin",
                    file = plugin,
                    expectedSha256 = androidModuleSha256(plugin),
                    dependencies = listOf("self"),
                ),
                root,
            )
        }
        root.deleteRecursively()
    }

    @Test
    fun `component descriptor requires plugin package identity`() {
        val root = temporaryRoot()
        val plugin = File(root, "plugin.apk").apply { writeText("fixture") }

        assertFailsWith<IllegalArgumentException> {
            validateAndroidModuleDescriptor(
                AndroidModuleDescriptor(
                    id = "components",
                    version = "1",
                    entryClass = "com.example.ComponentPlugin",
                    file = plugin,
                    expectedSha256 = androidModuleSha256(plugin),
                    activities = mapOf("com.example.MainActivity" to 0),
                ),
                root,
            )
        }
        root.deleteRecursively()
    }

    private fun temporaryRoot(): File = kotlin.io.path.createTempDirectory("cordis-plugin-root").toFile()
}
