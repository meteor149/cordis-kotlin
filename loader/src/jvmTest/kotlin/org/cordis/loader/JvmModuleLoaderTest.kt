package org.cordis.loader

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmModuleLoaderTest {
    @TempDir
    lateinit var temporary: Path

    @AfterEach
    fun clearProbe() {
        System.clearProperty(PROBE_PROPERTY)
    }

    @Test
    fun `loads a checksummed jar with isolated resources through a Cordis entry`() = runBlocking {
        val jar = pluginJar("basic-v1", "dev.plugins.BasicPlugin", "v1", resource = "resource-v1")
        val modules = JvmModuleLoader(temporary.toFile())
        val descriptor = descriptor("basic", "1", "dev.plugins.BasicPlugin", jar)
        modules.register(descriptor)

        assertTrue(modules.contains(modules.moduleUrl("basic")))
        assertTrue(modules.contains(jar.canonicalUrl()))
        assertEquals(listOf(jar.canonicalUrl()), modules.linked(modules.moduleUrl("basic")))

        val root = Context()
        val loader = Loader(root)
        loader.internal = modules
        loader.create(EntryOptions(id = "basic", name = modules.moduleUrl("basic"), config = Unit))

        assertEquals("v1", System.getProperty(PROBE_PROPERTY))
        val handle = assertNotNull(modules.activeModule("basic"))
        assertSame(handle.plugin, modules.peek(modules.moduleUrl("basic")))
        assertEquals("resource-v1", handle.plugin.javaClass.getMethod("resource").invoke(handle.plugin))
        assertNotSame(javaClass.classLoader, handle.classLoader)

        loader.root.stop()
        modules.release("basic")
        assertNull(modules.activeModule("basic"))
        assertEquals("basic", modules.unregister("basic")?.id)
        modules.close()
    }

    @Test
    fun `reload transaction switches generations and rollback keeps the active plugin`() = runBlocking {
        val firstJar = pluginJar("reload-v1", "dev.plugins.ReloadPlugin", "v1", resource = "one")
        val secondJar = pluginJar("reload-v2", "dev.plugins.ReloadPlugin", "v2", resource = "two")
        val modules = JvmModuleLoader(temporary.toFile())
        modules.register(descriptor("reload", "1", "dev.plugins.ReloadPlugin", firstJar))
        val first = modules.import(modules.moduleUrl("reload"), null) as Plugin<*>
        val firstHandle = assertNotNull(modules.activeModule("reload"))

        modules.register(descriptor("reload", "2", "dev.plugins.ReloadPlugin", secondJar))
        assertSame(first, modules.import(modules.moduleUrl("reload"), null))

        val rollback = modules.beginReload(setOf(secondJar.canonicalUrl()))
        val rejected = rollback.import(modules.moduleUrl("reload")) as Plugin<*>
        assertNotSame(first, rejected)
        assertEquals("two", rejected.javaClass.getMethod("resource").invoke(rejected))
        rollback.rollback()
        assertSame(first, modules.activeModule("reload")?.plugin)
        assertNull(rejected.javaClass.classLoader.getResource("plugin-resource.txt"))

        val replacement = modules.beginReload(setOf(secondJar.canonicalUrl()))
        val second = replacement.import(modules.moduleUrl("reload")) as Plugin<*>
        replacement.commit()
        val secondHandle = assertNotNull(modules.activeModule("reload"))
        assertSame(second, secondHandle.plugin)
        assertNotSame(firstHandle.classLoader, secondHandle.classLoader)
        assertTrue(secondHandle.generation > firstHandle.generation)
        assertNull(first.javaClass.classLoader.getResource("plugin-resource.txt"))
        modules.close()
    }

    @Test
    fun `declared module dependencies reload together with their dependent`() = runBlocking {
        val dependencyV1 = dependencyJar("dependency-v1", "dep-v1")
        val dependencyV2 = dependencyJar("dependency-v2", "dep-v2")
        val consumer = consumerJar("consumer", dependencyV1)
        val modules = JvmModuleLoader(temporary.toFile())
        modules.register(descriptor("dependency", "1", "dev.dependency.DependencyPlugin", dependencyV1))
        modules.register(descriptor(
            "consumer",
            "1",
            "dev.consumer.ConsumerPlugin",
            consumer,
            dependencies = listOf("dependency"),
        ))

        val first = modules.import(modules.moduleUrl("consumer"), null) as Plugin<*>
        assertEquals("dep-v1", first.javaClass.getMethod("value").invoke(first))
        assertEquals(
            listOf(consumer.canonicalUrl(), modules.moduleUrl("dependency")),
            modules.linked(modules.moduleUrl("consumer")),
        )

        modules.register(descriptor("dependency", "2", "dev.dependency.DependencyPlugin", dependencyV2))
        val reload = modules.beginReload(setOf(
            dependencyV2.canonicalUrl(),
            modules.moduleUrl("consumer"),
        ))
        val second = reload.import(modules.moduleUrl("consumer")) as Plugin<*>
        assertEquals("dep-v2", second.javaClass.getMethod("value").invoke(second))
        reload.commit()

        assertSame(second, modules.activeModule("consumer")?.plugin)
        assertEquals("dep-v2", modules.activeModule("consumer")?.plugin?.javaClass?.getMethod("value")
            ?.invoke(modules.activeModule("consumer")?.plugin))
        modules.release("consumer")
        assertNull(modules.activeModule("consumer"))
        assertNull(modules.activeModule("dependency"))
        modules.close()
    }

    @Test
    fun `private classpath jars and explicit host API packages are available without exposing the host`() = runBlocking {
        val library = libraryJar("private-library", "private-value")
        val plugin = pluginWithLibraryJar("private-plugin", library)
        val hostPlugin = hostApiPluginJar("host-plugin")
        val modules = JvmModuleLoader(temporary.toFile())
        modules.register(descriptor(
            "private",
            "1",
            "dev.privateplugin.PrivatePlugin",
            plugin,
            classpath = listOf(artifact(library)),
        ))
        modules.register(descriptor("host", "1", "dev.hostplugin.HostPlugin", hostPlugin))

        val privateInstance = modules.import(modules.moduleUrl("private"), null) as Plugin<*>
        assertEquals("private-value", privateInstance.javaClass.getMethod("value").invoke(privateInstance))

        val hiddenHostInstance = modules.import(modules.moduleUrl("host"), null) as Plugin<*>
        val hiddenError = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            hiddenHostInstance.javaClass.getMethod("value").invoke(hiddenHostInstance)
        }
        assertTrue(hiddenError.cause is NoClassDefFoundError)

        modules.register(descriptor(
            "host",
            "2",
            "dev.hostplugin.HostPlugin",
            hostPlugin,
            sharedHostPackages = setOf("dev.host.api"),
        ))
        val reload = modules.beginReload(setOf(modules.moduleUrl("host")))
        val sharedHostInstance = reload.import(modules.moduleUrl("host")) as Plugin<*>
        assertEquals("host-api", sharedHostInstance.javaClass.getMethod("value").invoke(sharedHostInstance))
        reload.commit()
        modules.close()
    }

    @Test
    fun `rejects untrusted artifacts checksum mismatches and cyclic dependencies`() = runBlocking {
        val jar = pluginJar("validated", "dev.plugins.ValidatedPlugin", "valid")
        val modules = JvmModuleLoader(temporary.toFile())
        val badDigest = descriptor("bad-digest", "1", "dev.plugins.ValidatedPlugin", jar)
            .copy(expectedSha256 = "0".repeat(64))
        assertFailsWith<IllegalArgumentException> { modules.register(badDigest) }

        val outsideRoot = Files.createTempFile("cordis-untrusted", ".jar")
        try {
            Files.copy(jar, outsideRoot, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertFailsWith<IllegalArgumentException> {
                modules.register(descriptor("outside", "1", "dev.plugins.ValidatedPlugin", outsideRoot))
            }
        } finally {
            Files.deleteIfExists(outsideRoot)
        }

        val tampered = temporary.resolve("tampered.jar")
        Files.copy(jar, tampered)
        modules.register(descriptor("tampered", "1", "dev.plugins.ValidatedPlugin", tampered))
        Files.writeString(tampered, "changed after registration", java.nio.file.StandardOpenOption.APPEND)
        assertFailsWith<IllegalArgumentException> {
            modules.import(modules.moduleUrl("tampered"), null)
        }
        assertNull(modules.activeModule("tampered"))

        modules.register(descriptor(
            "cycle-a", "1", "dev.plugins.ValidatedPlugin", jar, dependencies = listOf("cycle-b"),
        ))
        modules.register(descriptor(
            "cycle-b", "1", "dev.plugins.ValidatedPlugin", jar, dependencies = listOf("cycle-a"),
        ))
        val error = assertFailsWith<IllegalStateException> {
            modules.import(modules.moduleUrl("cycle-a"), null)
        }
        assertTrue(error.message.orEmpty().contains("cyclic JVM module dependency"))
        assertNull(modules.activeModule("cycle-a"))
        assertNull(modules.activeModule("cycle-b"))
        modules.close()
    }

    @Test
    fun `verifier external files and native artifacts participate in loader metadata`() = runBlocking {
        val jar = pluginJar("metadata", "dev.plugins.MetadataPlugin", "metadata")
        val external = temporary.resolve("host-api.jar")
        Files.writeString(external, "host")
        val native = temporary.resolve(System.mapLibraryName("cordis_fixture"))
        Files.writeString(native, "native fixture")
        var verifications = 0
        val modules = JvmModuleLoader(
            trustedRoot = temporary.toFile(),
            verifier = JvmModuleVerifier { verifications++ },
            externalFiles = setOf(external.toFile()),
        )
        modules.register(descriptor(
            "metadata",
            "1",
            "dev.plugins.MetadataPlugin",
            jar,
            nativeLibraries = listOf(artifact(native)),
        ))

        assertEquals(1, verifications)
        assertEquals(setOf(external.canonicalUrl()), modules.externals())
        assertEquals(
            listOf(jar.canonicalUrl(), native.canonicalUrl()),
            modules.linked(modules.moduleUrl("metadata")),
        )
        modules.import(modules.moduleUrl("metadata"), null)
        assertEquals(2, verifications)
        modules.close()
    }

    private fun descriptor(
        id: String,
        version: String,
        entryClass: String,
        jar: Path,
        dependencies: List<String> = emptyList(),
        sharedHostPackages: Set<String> = emptySet(),
        classpath: List<JvmModuleArtifact> = emptyList(),
        nativeLibraries: List<JvmModuleArtifact> = emptyList(),
    ) = JvmModuleDescriptor(
        id = id,
        version = version,
        entryClass = entryClass,
        file = jar.toFile(),
        expectedSha256 = jvmModuleSha256(jar.toFile()),
        dependencies = dependencies,
        sharedHostPackages = sharedHostPackages,
        classpath = classpath,
        nativeLibraries = nativeLibraries,
    )

    private fun artifact(path: Path) = JvmModuleArtifact(path.toFile(), jvmModuleSha256(path.toFile()))

    private fun Path.canonicalUrl(): String = toFile().canonicalFile.toPath().toUri().toString()

    private fun pluginJar(name: String, className: String, value: String, resource: String = value): Path {
        val packageName = className.substringBeforeLast('.')
        val simpleName = className.substringAfterLast('.')
        return compileJar(name, mapOf(className to """
            package $packageName;

            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            import org.cordis.ConfigValidator;
            import org.cordis.Context;
            import org.cordis.Dependencies;
            import org.cordis.EffectScope;
            import org.cordis.Plugin;

            public final class $simpleName implements Plugin<Unit> {
                public String getName() { return "$name"; }
                public ConfigValidator<Unit> getConfig() { return null; }
                public Dependencies getInject() { return Dependencies.Companion.getEmpty(); }
                public Object apply(Context context, Unit config, EffectScope effect, Continuation<? super Unit> continuation) {
                    System.setProperty("$PROBE_PROPERTY", "$value");
                    return Unit.INSTANCE;
                }
                public String resource() throws Exception {
                    try (InputStream input = getClass().getClassLoader().getResourceAsStream("plugin-resource.txt")) {
                        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        """.trimIndent()), resources = mapOf("plugin-resource.txt" to resource))
    }

    private fun dependencyJar(name: String, value: String): Path = compileJar(name, mapOf(
        "dev.dependency.Value" to """
            package dev.dependency;
            public final class Value {
                private Value() {}
                public static String value() { return "$value"; }
            }
        """.trimIndent(),
        "dev.dependency.DependencyPlugin" to javaPluginSource(
            "dev.dependency", "DependencyPlugin", name, "return Unit.INSTANCE;",
        ),
    ))

    private fun consumerJar(name: String, dependency: Path): Path = compileJar(name, mapOf(
        "dev.consumer.ConsumerPlugin" to javaPluginSource(
            "dev.consumer",
            "ConsumerPlugin",
            name,
            "return Unit.INSTANCE;",
            "public String value() { return dev.dependency.Value.value(); }",
        ),
    ), compileClasspath = listOf(dependency))

    private fun libraryJar(name: String, value: String): Path = compileJar(name, mapOf(
        "dev.privatelib.Value" to """
            package dev.privatelib;
            public final class Value {
                private Value() {}
                public static String value() { return "$value"; }
            }
        """.trimIndent(),
    ))

    private fun pluginWithLibraryJar(name: String, library: Path): Path = compileJar(name, mapOf(
        "dev.privateplugin.PrivatePlugin" to javaPluginSource(
            "dev.privateplugin",
            "PrivatePlugin",
            name,
            "return Unit.INSTANCE;",
            "public String value() { return dev.privatelib.Value.value(); }",
        ),
    ), compileClasspath = listOf(library))

    private fun hostApiPluginJar(name: String): Path = compileJar(name, mapOf(
        "dev.hostplugin.HostPlugin" to javaPluginSource(
            "dev.hostplugin",
            "HostPlugin",
            name,
            "return Unit.INSTANCE;",
            "public String value() { return dev.host.api.HostApi.value(); }",
        ),
    ))

    private fun javaPluginSource(
        packageName: String,
        simpleName: String,
        name: String,
        applyBody: String,
        extraBody: String = "",
    ) = """
        package $packageName;

        import kotlin.Unit;
        import kotlin.coroutines.Continuation;
        import org.cordis.ConfigValidator;
        import org.cordis.Context;
        import org.cordis.Dependencies;
        import org.cordis.EffectScope;
        import org.cordis.Plugin;

        public final class $simpleName implements Plugin<Unit> {
            public String getName() { return "$name"; }
            public ConfigValidator<Unit> getConfig() { return null; }
            public Dependencies getInject() { return Dependencies.Companion.getEmpty(); }
            public Object apply(Context context, Unit config, EffectScope effect, Continuation<? super Unit> continuation) {
                $applyBody
            }
            $extraBody
        }
    """.trimIndent()

    private fun compileJar(
        name: String,
        sources: Map<String, String>,
        resources: Map<String, String> = emptyMap(),
        compileClasspath: List<Path> = emptyList(),
    ): Path {
        val workspace = temporary.resolve(name).createDirectories()
        val sourceRoot = workspace.resolve("src").createDirectories()
        val classes = workspace.resolve("classes").createDirectories()
        val sourceFiles = sources.map { (className, source) ->
            sourceRoot.resolve(className.replace('.', File.separatorChar) + ".java").also { file ->
                file.parent.createDirectories()
                file.writeText(source)
            }
        }

        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "tests require a JDK")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { files ->
            val units = files.getJavaFileObjectsFromPaths(sourceFiles)
            val classpath = buildList {
                add(System.getProperty("java.class.path"))
                compileClasspath.forEach { add(it.toString()) }
            }.joinToString(File.pathSeparator)
            val success = compiler.getTask(
                null,
                files,
                diagnostics,
                listOf("-classpath", classpath, "-d", classes.toString(), "-proc:none"),
                null,
                units,
            ).call()
            assertTrue(success, diagnostics.diagnostics.joinToString(System.lineSeparator()))
        }

        val jar = temporary.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file ->
                    val entryName = classes.relativize(file).toString().replace(File.separatorChar, '/')
                    output.putNextEntry(JarEntry(entryName))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
            resources.forEach { (entryName, value) ->
                output.putNextEntry(JarEntry(entryName))
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.closeEntry()
            }
        }
        return jar
    }

    private companion object {
        const val PROBE_PROPERTY = "cordis.jvm.module.test.value"
    }
}
