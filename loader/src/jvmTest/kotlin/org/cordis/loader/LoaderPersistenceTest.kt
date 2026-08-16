package org.cordis.loader

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.CoreEvents
import org.cordis.Fiber
import org.cordis.FiberState
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class LoaderPersistenceTest {
    private class RecordingLoader(ctx: Context) : Loader(ctx) {
        var writes = 0
        override fun write() { writes++ }
    }

    private class RecordingTree(ctx: Context) : EntryTree(ctx) {
        var writes = 0
        override fun write() { writes++ }
    }

    @Test
    fun `entry root self-update persists before local middleware stops restart`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        loader.builtins["fixture"] = plugin<Map<String, Int>>(name = "fixture") { ctx, _ ->
            ctx.listen(CoreEvents.Update) { null }
        }
        val id = loader.create(EntryOptions(
            id = "fixture",
            name = "cordis:fixture",
            config = mapOf("value" to 1),
        ))
        val entry = loader.resolve(id)
        val originalFiber = entry.fiber!!
        loader.writes = 0

        originalFiber.update(mapOf("value" to 2))

        assertEquals(mapOf("value" to 2), entry.options.config)
        assertEquals(1, loader.writes)
        assertEquals(mapOf("value" to 1), originalFiber.config)
        assertTrue(originalFiber === entry.fiber)

        originalFiber.update(mapOf("value" to 3), noSave = true)
        assertEquals(mapOf("value" to 2), entry.options.config)
        assertEquals(1, loader.writes)
        loader.root.stop()
    }

    @Test
    fun `entry root self-dispose marks it disabled and can later be enabled`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        var loads = 0
        loader.builtins["fixture"] = plugin<Unit>(name = "fixture") { _, _ -> loads++ }
        val id = loader.create(EntryOptions(id = "fixture", name = "cordis:fixture", config = Unit))
        val entry = loader.resolve(id)
        val originalFiber = entry.fiber!!
        loader.writes = 0

        originalFiber.dispose()

        assertEquals(true, entry.options.disabled)
        assertEquals(1, loader.writes)
        assertEquals(FiberState.DISPOSED, originalFiber.state)

        entry.update(EntryPatch(disabled = changeTo(null)))
        assertFalse(entry.disabled)
        assertEquals(FiberState.ACTIVE, entry.fiber?.state)
        assertNotSame(originalFiber, entry.fiber)
        assertEquals(2, loads)
        loader.root.stop()
    }

    @Test
    fun `disposing nested child plugin does not disable its loader entry`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        lateinit var childFiber: Fiber<Unit>
        val child = plugin<Unit>(name = "child") { _, _ -> }
        loader.builtins["fixture"] = plugin<Unit>(name = "fixture") { ctx, _ ->
            childFiber = ctx.plugin(child, Unit).await()
        }
        val id = loader.create(EntryOptions(id = "fixture", name = "cordis:fixture", config = Unit))
        val entry = loader.resolve(id)
        loader.writes = 0

        childFiber.dispose()

        assertEquals(null, entry.options.disabled)
        assertEquals(0, loader.writes)
        assertTrue(entry.fiber?.uid != null)
        loader.root.stop()
    }

    @Test
    fun `loader await intercept stays pending until tracked loading task settles`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        val gate = CompletableDeferred<Unit>()
        loader.builtins["slow"] = plugin<Unit>(name = "slow") { _, _ -> gate.await() }
        loader.builtins["waiting"] = plugin<Unit>(
            name = "waiting",
            inject = dependencies(Loader.Key),
        ) { _, _ -> }

        val slowCreate = launch {
            loader.create(EntryOptions(id = "slow", name = "cordis:slow", config = Unit))
        }
        withTimeout(5_000) {
            while (loader.store["slow"]?.fiber?.state != FiberState.LOADING) delay(1)
        }
        loader.create(EntryOptions(
            id = "waiting",
            name = "cordis:waiting",
            config = Unit,
            intercept = mapOf("loader" to LoaderIntercept(await = true)),
        ))
        val waiting = loader.resolve("waiting").fiber!!
        assertEquals(FiberState.PENDING, waiting.state)

        gate.complete(Unit)
        slowCreate.join()
        waiting.await()
        assertEquals(FiberState.ACTIVE, waiting.state)
        loader.root.stop()
    }

    @Test
    fun `moving an entry across configuration trees writes target and source`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        lateinit var nested: RecordingTree
        loader.builtins["subtree"] = plugin<Unit>(name = "subtree") { ctx, _ ->
            nested = RecordingTree(ctx)
        }
        loader.builtins["fixture"] = plugin<Unit>(name = "fixture") { _, _ -> }
        loader.builtins["group"] = GroupPlugin
        val container = loader.create(EntryOptions(id = "container", name = "cordis:subtree", config = Unit))
        nested.create(EntryOptions(id = "inner", name = "cordis:fixture", config = Unit))
        val target = loader.create(EntryOptions(
            id = "target",
            name = "cordis:group",
            group = true,
            config = emptyList<EntryOptions>(),
        ))
        loader.writes = 0
        nested.writes = 0

        loader.update("$container:inner", EntryPatch(), target)

        assertEquals(1, loader.writes)
        assertEquals(1, nested.writes)
        assertTrue(loader.resolveGroup(target).data.any { it.id == "inner" })
        assertFalse(nested.root.data.any { it.id == "inner" })
        loader.root.stop()
    }

    @Test
    fun `partial dispose event exposes previous options and update flag`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        loader.builtins["fixture"] = plugin<Int>(name = "fixture") { _, _ -> }
        val events = mutableListOf<Pair<Any?, Boolean>>()
        root.listen(LoaderEvents.PartialDispose) { event ->
            events += event.payload.previous.config to event.payload.updating
        }
        val id = loader.create(EntryOptions(id = "fixture", name = "cordis:fixture", config = 1))

        loader.update(id, EntryPatch(config = changeTo(2)))
        loader.remove(id)

        assertEquals(listOf<Pair<Any?, Boolean>>(1 to true, 2 to false), events)
    }

    @Test
    fun `equal non-group config does not restart even when tree update is forced`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        var loads = 0
        loader.builtins["fixture"] = plugin<Map<String, Int>>(name = "fixture") { _, _ -> loads++ }
        val id = loader.create(EntryOptions(
            id = "fixture",
            name = "cordis:fixture",
            config = mapOf("value" to 1),
        ))
        val entry = loader.resolve(id)

        entry.update(EntryPatch(config = changeTo(mapOf("value" to 1))))
        loader.update(id, EntryPatch(config = changeTo(mapOf("value" to 1))))

        assertEquals(1, loads)
        loader.root.stop()
    }

    @Test
    fun `concurrent entry refreshes share one module initialization task`() = runBlocking {
        val root = Context()
        val loader = RecordingLoader(root)
        val gate = CompletableDeferred<Unit>()
        var imports = 0
        var loads = 0
        val subject = plugin<Unit>(name = "subject") { _, _ -> loads++ }
        loader.internal = object : ModuleLoader {
            override suspend fun import(specifier: String, parentUrl: String?): Any? {
                imports++
                gate.await()
                return subject
            }

            override fun resolve(specifier: String, parentUrl: String?) =
                ResolveResult(ModuleFormat.MODULE, specifier)
        }

        val first = launch {
            loader.create(EntryOptions(id = "subject", name = "module:subject", config = Unit))
        }
        withTimeout(5_000) {
            while (loader.store["subject"]?.initTask == null) delay(1)
        }
        val entry = loader.resolve("subject")
        val second = launch { entry.refresh() }
        delay(10)
        assertEquals(1, loader.getTasks().size)
        assertEquals(1, imports)

        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, imports)
        assertEquals(1, loads)
        loader.root.stop()
    }
}
