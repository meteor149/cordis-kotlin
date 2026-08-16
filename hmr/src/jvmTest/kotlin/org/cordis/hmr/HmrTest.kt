package org.cordis.hmr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.EventKey
import org.cordis.Plugin
import org.cordis.loader.Entry
import org.cordis.loader.EntryOptions
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.ModuleFormat
import org.cordis.loader.ModuleLoader
import org.cordis.loader.ReloadTransaction
import org.cordis.loader.ResolveResult
import org.cordis.plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HmrTest {
    private val valueEvent = EventKey<Unit, String>("value")
    private val dependencyValueEvent = EventKey<Unit, String>("dep-value")
    private val stableEvent = EventKey<Unit, Boolean>("stable")
    private val recoverEvent = EventKey<Unit, String>("recover")
    @TempDir lateinit var temporary: Path

    private class MemoryLoader : ModuleLoader {
        data class Record(
            val specifier: String,
            val url: String,
            var current: Any?,
            var next: suspend () -> Any?,
            val dependencies: MutableList<String> = mutableListOf(),
        )

        private val records = linkedMapOf<String, Record>()
        val externalUrls = linkedSetOf<String>()
        var lastReloadUrls: Set<String> = emptySet()
        fun add(specifier: String, file: Path, plugin: Any?, vararg dependencies: String): Record {
            val url = file.toUri().toString()
            return Record(specifier, url, plugin, { plugin }, dependencies.toMutableList()).also { records[url] = it }
        }

        override suspend fun import(specifier: String, parentUrl: String?): Any? = record(specifier).current
        override fun resolve(specifier: String, parentUrl: String?): ResolveResult =
            ResolveResult(ModuleFormat.MODULE, record(specifier).url)
        override fun contains(url: String) = records.containsKey(url)
        override fun linked(url: String): List<String> = records[url]?.dependencies.orEmpty()
        override fun externals(): Set<String> = externalUrls
        override fun peek(url: String): Any? = records[url]?.current
        override fun beginReload(urls: Set<String>): ReloadTransaction {
            lastReloadUrls = urls
            val staged = linkedMapOf<String, Any?>()
            return object : ReloadTransaction {
                override suspend fun import(url: String): Any? = records.getValue(url).next().also { staged[url] = it }
                override fun commit() { staged.forEach { (url, value) -> records.getValue(url).current = value } }
                override fun rollback() { staged.clear() }
            }
        }

        private fun record(key: String): Record = records[key]
            ?: records.values.firstOrNull { it.specifier == key }
            ?: error("unknown module $key")
    }

    private data class Fixture(val root: Context, val loader: Loader, val modules: MemoryLoader, val hmr: Hmr)

    private fun fixture(): Fixture {
        val root = Context()
        val loader = Loader(root, LoaderConfig(temporary.toUri().toString()))
        val modules = MemoryLoader()
        loader.internal = modules
        val hmr = Hmr(root, HmrConfig(base = temporary.toString(), debounce = 60_000))
        return Fixture(root, loader, modules, hmr)
    }

    @Test
    fun `relative module paths retain the filename`() {
        val file = temporary.resolve("nested").resolve("feature.kt")
        assertEquals(
            Path.of("nested", "feature.kt").toString(),
            PlatformHmr.relative(temporary.toString(), file.toUri().toString()),
        )
    }

    @Test
    fun `recursive globs include files directly below their root`() {
        assertTrue(matchesWatchPath("src/a.kt", listOf("src/**/*.kt"), emptyList()))
        assertTrue(matchesWatchPath("src/nested/a.kt", listOf("src/**/*.kt"), emptyList()))
        assertFalse(matchesWatchPath("src/a.js", listOf("src/**/*.kt"), emptyList()))
    }

    @Test
    fun `reloads plugin disposes effects emits event and preserves entry`() = runBlocking {
        val fixture = fixture()
        var disposed = 0
        var reloaded = 0
        val old = plugin<Unit>(name = "old") { ctx, _ ->
            ctx.listen(valueEvent) { "old" }
            collect { disposed++ }
        }
        val replacement = plugin<Unit>(name = "new") { ctx, _ ->
            ctx.listen(valueEvent) { "new" }
        }
        val record = fixture.modules.add("module:feature", temporary.resolve("feature.kt"), old)
        fixture.loader.create(EntryOptions(id = "feature", name = "module:feature", config = Unit))
        val entry = fixture.loader.resolve("feature")
        val originalFiber = entry.fiber
        fixture.root.listen(HmrEvents.Reload) { reloaded++ }
        record.next = { replacement }

        fixture.hmr.stash(record.url)
        assertTrue(fixture.hmr.partialReload())
        assertEquals("new", fixture.root.bailEvent(valueEvent, Unit))
        assertEquals(1, disposed)
        assertEquals(1, reloaded)
        assertTrue(entry.fiber !== originalFiber)
        assertSame(entry, entry.fiber?.attributes?.get(Entry.ATTRIBUTE))
        fixture.hmr.stop()
    }

    @Test
    fun `dependency change reloads dependent plugin`() = runBlocking {
        val fixture = fixture()
        var generation = "old"
        fun subject(): Plugin<Unit> = plugin(name = "subject") { ctx, _ ->
            val captured = generation
            ctx.listen(dependencyValueEvent) { captured }
        }
        val dependency = fixture.modules.add("module:dep", temporary.resolve("dep.kt"), Any())
        val pluginRecord = fixture.modules.add("module:subject", temporary.resolve("subject.kt"), subject(), dependency.url)
        fixture.loader.create(EntryOptions(id = "subject", name = "module:subject", config = Unit))
        assertEquals("old", fixture.root.bailEvent(dependencyValueEvent, Unit))
        generation = "new"
        pluginRecord.next = { subject() }
        dependency.next = { Any() }

        fixture.hmr.stash(dependency.url)
        assertTrue(fixture.hmr.partialReload())
        assertEquals("new", fixture.root.bailEvent(dependencyValueEvent, Unit))
        assertEquals(listOf(dependency.url), fixture.hmr.getLinked(pluginRecord.url))
        assertEquals(setOf(pluginRecord.url, dependency.url), fixture.modules.lastReloadUrls)
        assertEquals(emptyList(), fixture.hmr.getLinked("file:///missing"))
        fixture.hmr.stop()
    }

    @Test
    fun `import failure rolls back and keeps old plugin active`() = runBlocking {
        val fixture = fixture()
        val old = plugin<Unit>(name = "stable") { ctx, _ -> ctx.listen(stableEvent) { true } }
        val record = fixture.modules.add("module:stable", temporary.resolve("stable.kt"), old)
        fixture.loader.create(EntryOptions(id = "stable", name = "module:stable", config = Unit))
        record.next = { throw BuildFailure(listOf(BuildMessage("syntax error"))) }

        fixture.hmr.stash(record.url)
        assertFalse(fixture.hmr.partialReload())
        assertEquals(true, fixture.root.bailEvent(stableEvent, Unit))
        assertTrue(fixture.root.registry.has(old))
        fixture.hmr.stop()
    }

    @Test
    fun `apply failure restores old runtime`() = runBlocking {
        val fixture = fixture()
        val old = plugin<Unit>(name = "stable") { ctx, _ -> ctx.listen(recoverEvent) { "old" } }
        val broken = plugin<Unit>(name = "broken") { _, _ -> error("apply failed") }
        val record = fixture.modules.add("module:recover", temporary.resolve("recover.kt"), old)
        fixture.loader.create(EntryOptions(id = "recover", name = "module:recover", config = Unit))
        record.next = { broken }

        fixture.hmr.stash(record.url)
        assertFalse(fixture.hmr.partialReload())
        assertEquals("old", fixture.root.bailEvent(recoverEvent, Unit))
        assertFalse(fixture.root.registry.has(broken))
        fixture.hmr.stop()
    }

    @Test
    fun `watcher emits unknown change and debounces known module`() = runBlocking {
        val fixture = fixture()
        val changed = CompletableDeferred<String>()
        fixture.root.listen(HmrEvents.Change) { event ->
            changed.complete(event.payload.single())
        }
        fixture.hmr.start()
        delay(100)
        val file = temporary.resolve("unknown.txt")
        Files.writeString(file, "changed")
        val url = withTimeout(5_000) { changed.await() }
        assertEquals(file.toUri().toString(), url)
        fixture.hmr.stop()
    }

    @Test
    fun `build failure formatting includes source location`() {
        val root = Context()
        val file = temporary.resolve("source.kt")
        Files.writeString(file, "val answer =\n")
        handleError(root, BuildFailure(listOf(BuildMessage("expecting expression", BuildLocation(file.toString(), 1, 5)))))
        val warning = root.logger.buffer.last()
        assertTrue(warning.args.first().toString().startsWith("File:"))
    }

    @Test
    fun `shared plugin runtime reloads once and recreates each associated fiber once`() = runBlocking {
        val fixture = fixture()
        var oldLoads = 0
        var newLoads = 0
        val old = plugin<Unit>(name = "shared-old") { _, _ -> oldLoads++ }
        val replacement = plugin<Unit>(name = "shared-new") { _, _ -> newLoads++ }
        val record = fixture.modules.add("module:shared", temporary.resolve("shared.kt"), old)
        fixture.loader.create(EntryOptions(id = "first", name = "module:shared", config = Unit))
        fixture.loader.create(EntryOptions(id = "second", name = "module:shared", config = Unit))
        assertEquals(2, oldLoads)
        assertEquals(2, fixture.root.registry.get(old)?.fibers?.size)
        record.next = { replacement }

        fixture.hmr.stash(record.url)
        assertTrue(fixture.hmr.partialReload())

        assertEquals(2, newLoads)
        assertEquals(2, fixture.root.registry.get(replacement)?.fibers?.size)
        assertFalse(fixture.root.registry.has(old))
        listOf("first", "second").forEach { id ->
            val entry = fixture.loader.resolve(id)
            assertSame(entry, entry.fiber?.attributes?.get(Entry.ATTRIBUTE))
        }
        fixture.hmr.stop()
    }

    @Test
    fun `external dependency change requests full loader restart`() = runBlocking {
        val root = Context()
        val loader = Loader(root, LoaderConfig(temporary.toUri().toString()))
        val modules = MemoryLoader()
        val file = temporary.resolve("framework.kt")
        val record = modules.add("module:framework", file, Any())
        modules.externalUrls += record.url
        loader.internal = modules
        val hmr = Hmr(root, HmrConfig(base = temporary.toString()))

        hmr.change(file.toString())

        assertTrue(loader.exitRequested)
        hmr.stop()
    }
}
