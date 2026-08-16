package org.cordis.include

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EventKey
import org.cordis.ServiceKey
import org.cordis.loader.EntryOptions
import org.cordis.loader.GroupPlugin
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderEvents
import org.cordis.loader.changeTo
import org.cordis.FiberState
import org.cordis.plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IncludeTest {
    private val valueEvent = EventKey<Unit, String>("test/get-value")
    private val extraEvent = EventKey<Unit, String>("test/get-extra")
    @TempDir
    lateinit var temporary: Path

    private fun fixturePlugin(name: String, event: EventKey<Unit, String>, value: String) =
        plugin<Any?>(name = name) { ctx, _ ->
            ctx.listen(event) { value }
    }

    private fun loader(root: Context): Loader = Loader(root, LoaderConfig(temporary.toUri().toString())).also {
        it.builtins["test"] = fixturePlugin("test/get-value", valueEvent, "default")
        it.builtins["extra"] = fixturePlugin("test/get-extra", extraEvent, "extra")
        it.builtins["group"] = GroupPlugin
    }

    @Test
    fun `custom entry fields survive decoding and patch overrides`() {
        val root = Context()
        loader(root)
        val include = Include(root, IncludeConfig(
            path = temporary.resolve("extra.yml").toString(),
            patches = listOf(PatchOptions(id = "entry", extra = mapOf("custom" to 2))),
        ))
        val entry = EntryOptions(id = "entry", name = "cordis:test", extra = mapOf("custom" to 1, "kept" to true))

        include.applyPatches(mutableListOf(entry))

        assertEquals(mapOf("custom" to 2, "kept" to true), entry.extra)
    }

    private fun writeBase(path: Path) {
        Files.writeString(path, """
            - id: inner
              name: cordis:test
            - id: group
              name: cordis:group
              group: true
              config: []
        """.trimIndent())
    }

    @Test
    fun `loads yaml without patches and disables entry via patch`() = runBlocking {
        val root = Context()
        loader(root)
        val file = temporary.resolve("base.yml")
        writeBase(file)

        val plain = Include(root, IncludeConfig(file.toString()))
        plain.init()
        assertEquals("default", root.bailEvent(valueEvent, Unit))
        plain.root.stop()

        val patched = Include(root, IncludeConfig(file.toString(), patches = listOf(
            PatchOptions(id = "inner", disabled = changeTo(true)),
        )))
        patched.init()
        assertNull(root.bailEvent(valueEvent, Unit))
        patched.root.stop()
    }

    @Test
    fun `patch validation warns and skips mismatched name`() = runBlocking {
        val root = Context()
        loader(root)
        val file = temporary.resolve("base.yml")
        writeBase(file)
        val include = Include(root, IncludeConfig(file.toString(), patches = listOf(
            PatchOptions(id = "inner", name = "wrong-name", disabled = changeTo(true)),
            PatchOptions(id = "missing", disabled = changeTo(true)),
        )))
        include.init()
        assertEquals("default", root.bailEvent(valueEvent, Unit))
        val warnings = root.logger.buffer.filter { it.type == org.cordis.LoggerType.WARN }
        assertTrue(warnings.any { it.args.firstOrNull().toString().contains("name mismatch") })
        assertTrue(warnings.any { it.args.firstOrNull().toString().contains("not found") })
        include.root.stop()
    }

    @Test
    fun `inserts entries into root and nested groups`() = runBlocking {
        val root = Context()
        loader(root)
        val file = temporary.resolve("base.yml")
        writeBase(file)
        val include = Include(root, IncludeConfig(file.toString(), patches = listOf(
            PatchOptions(insert = listOf(EntryOptions(name = "cordis:extra"))),
            PatchOptions(id = "group", insert = listOf(EntryOptions(name = "cordis:extra"))),
        )))
        include.init()
        assertEquals("default", root.bailEvent(valueEvent, Unit))
        assertEquals("extra", root.bailEvent(extraEvent, Unit))
        assertEquals(3, include.root.data.size)
        val group = include.root.data.first { it.id == "group" }
        assertEquals(1, assertIs<List<*>>(group.config).size)
        include.root.stop()
    }

    @Test
    fun `initial content creates missing file and write is atomic`() = runBlocking {
        val root = Context()
        loader(root)
        val file = temporary.resolve("created.json")
        var updates = 0
        root.listen(LoaderEvents.ConfigUpdate) { updates++ }
        val include = Include(root, IncludeConfig(
            file.toString(),
            initial = listOf(EntryOptions(id = "inner", name = "cordis:test")),
        ))
        include.init()
        assertTrue(Files.exists(file))
        assertEquals("default", root.bailEvent(valueEvent, Unit))

        include.root.data += EntryOptions(id = "extra", name = "cordis:extra")
        include.write()
        repeat(50) {
            if (Files.readString(file).contains("extra")) return@repeat
            delay(10)
        }
        assertTrue(Files.readString(file).contains("extra"))
        assertFalse(Files.exists(file.resolveSibling("created.json.tmp")))
        assertEquals(1, updates)
        include.root.stop()
    }

    @Test
    fun `yaml js tag is preserved as loader expression`() = runBlocking {
        val root = Context()
        loader(root)
        root.provide(ServiceKey<Int>("answer"), 42).awaitReady()
        val file = temporary.resolve("expr.yml")
        Files.writeString(file, "- id: expr\n  name: cordis:test\n  config: !js answer\n")
        val include = Include(root, IncludeConfig(file.toString()))
        include.init()
        assertEquals(42, include.resolve("expr").fiber?.config)
        assertEquals("default", root.bailEvent(valueEvent, Unit))
        include.root.stop()
    }

    @Test
    fun `matching-name config override and multiple patches are applied together`() = runBlocking {
        val root = Context()
        loader(root)
        val file = temporary.resolve("base.yml")
        writeBase(file)
        val include = Include(root, IncludeConfig(file.toString(), patches = listOf(
            PatchOptions(id = "inner", name = "cordis:test", config = changeTo(mapOf("custom" to true))),
            PatchOptions(id = "inner", disabled = changeTo(true)),
            PatchOptions(insert = listOf(EntryOptions(id = "extra", name = "cordis:extra"))),
        )))

        include.init()
        assertNull(root.bailEvent(valueEvent, Unit))
        assertEquals("extra", root.bailEvent(extraEvent, Unit))
        assertEquals(mapOf("custom" to true), include.resolve("inner").options.config)
        include.root.stop()
    }

    @Test
    fun `invalid patch targets warn without inserting or changing entries`() = runBlocking {
        val root = Context()
        loader(root)
        val file = temporary.resolve("base.yml")
        writeBase(file)
        val include = Include(root, IncludeConfig(file.toString(), patches = listOf(
            PatchOptions(id = "inner", insert = listOf(EntryOptions(name = "cordis:extra"))),
            PatchOptions(disabled = changeTo(true)),
        )))

        include.init()
        assertEquals("default", root.bailEvent(valueEvent, Unit))
        assertNull(root.bailEvent(extraEvent, Unit))
        val warnings = root.logger.buffer.filter { it.type == org.cordis.LoggerType.WARN }
        assertTrue(warnings.any { it.args.firstOrNull().toString().contains("not a group") })
        assertTrue(warnings.any { it.args.firstOrNull().toString().contains("id is required") })
        include.root.stop()
    }

    @Test
    fun `same-path include config update refreshes children without restarting include fiber`() = runBlocking {
        val root = Context()
        val loader = Loader(root, LoaderConfig(temporary.toUri().toString()))
        var loads = 0
        var unloads = 0
        loader.builtins["test"] = plugin<Any?>(name = "test") { _, _ ->
            loads++
            collect { unloads++ }
        }
        loader.builtins["group"] = GroupPlugin
        loader.builtins["include"] = IncludePlugin
        val file = temporary.resolve("base.yml")
        writeBase(file)
        val original = IncludeConfig(file.toString())
        val id = loader.create(EntryOptions(id = "include", name = "cordis:include", config = original))
        val entry = loader.resolve(id)
        val fiber = entry.fiber!!

        fiber.update(original.copy(enableLogs = true))

        assertTrue(entry.fiber === fiber)
        assertEquals(FiberState.ACTIVE, fiber.state)
        assertEquals(1, loads)
        assertEquals(0, unloads)
        assertEquals(true, assertIs<IncludeConfig>(entry.options.config).enableLogs)
        loader.root.stop()
    }
}
