package org.cordis.loader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.cordis.Context
import org.cordis.EventKey
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeTransactionTest {
    private val probe = EventKey<Unit, String>("transaction/probe")
    private fun fixture(): Loader = Loader(Context()).also { loader ->
        loader.builtins["old"] = plugin<Unit>(name = "old") { ctx, _ -> ctx.listen(probe) { "old" } }
        loader.builtins["new"] = plugin<Unit>(name = "new") { ctx, _ -> ctx.listen(probe) { "new" } }
        loader.builtins["bad"] = plugin<Unit>(name = "bad") { _, _ -> error("apply failed") }
        loader.builtins["group"] = GroupPlugin
    }
    private fun entry(name: String) = EntryOptions(id = "root", name = "cordis:$name", config = Unit)

    @Test
    fun `disabled unavailable modules can remain in a recoverable composition`() = runBlocking {
        val loader = fixture()
        loader.applyTree(listOf(entry("missing").copy(disabled = true)))
        assertEquals("cordis:missing", loader.root.data.single().name)
        assertNull(loader.store["root"]?.fiber)
        loader.root.stop()
    }

    @Test
    fun `cancellation during durable publication does not roll back a successful commit`() = runBlocking {
        val loader = fixture()
        loader.applyTree(listOf(entry("old")))
        val publishing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved = false
        val update = async {
            loader.withTreeTransaction(listOf(entry("new"))) {
                publishing.complete(Unit)
                release.await()
                saved = true
            }
        }
        publishing.await()
        update.cancel()
        release.complete(Unit)
        update.join()
        assertTrue(saved)
        assertEquals("new", loader.ctx.bailEvent(probe, Unit))
        assertFalse(loader.transactionActive)
        loader.root.stop()
    }

    @Test
    fun `host publication failure restores previous interfaces and entry metadata`() = runBlocking {
        val loader = fixture()
        loader.applyTree(listOf(entry("old")))
        assertFailsWith<IllegalStateException> {
            loader.withTreeTransaction(listOf(entry("new"))) {
                assertEquals("new", loader.ctx.bailEvent(probe, Unit))
                error("save failed")
            }
        }
        assertEquals("old", loader.ctx.bailEvent(probe, Unit))
        assertEquals("cordis:old", loader.root.data.single().name)
        assertFalse(loader.transactionActive)
        loader.root.stop()
    }

    @Test
    fun `apply failure including a nested child rolls back the entire candidate`() = runBlocking {
        val loader = fixture()
        loader.applyTree(listOf(entry("old")))
        assertFailsWith<IllegalStateException> {
            loader.applyTree(listOf(EntryOptions(id = "group", name = "cordis:group", group = true,
                config = listOf(EntryOptions(id = "child", name = "cordis:bad", config = Unit)))))
        }
        assertEquals("old", loader.ctx.bailEvent(probe, Unit))
        assertEquals(setOf("root"), loader.store.keys)
        loader.root.stop()
    }

    @Test
    fun `import failure is preflight and does not withdraw the old plugin`() = runBlocking {
        val loader = fixture()
        var releases = 0
        loader.builtins["old"] = plugin<Unit> { _, _ -> collect { releases++ } }
        loader.applyTree(listOf(entry("old")))
        assertFailsWith<IllegalArgumentException> { loader.applyTree(listOf(entry("missing"))) }
        assertEquals(0, releases)
        assertEquals("cordis:old", loader.root.data.single().name)
        loader.root.stop()
    }

    @Test
    fun `pending dependencies are allowed and recover after a provider arrives`() = runBlocking {
        val loader = fixture()
        val service = ServiceKey<String>("transaction/dependency")
        loader.builtins["consumer"] = plugin<Unit>(inject = dependencies(service)) { ctx, _ ->
            ctx.listen(probe) { ctx.require(service) }
        }
        loader.applyTree(listOf(entry("consumer")))
        assertNull(loader.ctx.bailEvent(probe, Unit))
        loader.builtins["provider"] = plugin<Unit> { ctx, _ -> ctx.provide(service, "ready") }
        loader.applyTree(listOf(entry("consumer"), EntryOptions(id = "provider", name = "cordis:provider", config = Unit)))
        assertEquals("ready", loader.ctx.bailEvent(probe, Unit))
        loader.root.stop()
    }

    @Test
    fun `module mutation waits until host publication completes and reentry fails promptly`() = runBlocking {
        val loader = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = async {
            loader.withTreeTransaction(listOf(entry("old"))) {
                entered.complete(Unit)
                release.await()
                assertFailsWith<IllegalStateException> { loader.applyTree(emptyList()) }
            }
        }
        entered.await()
        var secondRan = false
        val second = async { loader.withMutation { secondRan = true } }
        yield()
        assertFalse(secondRan)
        release.complete(Unit)
        transaction.await()
        second.await()
        assertTrue(secondRan)
        loader.root.stop()
    }
}
