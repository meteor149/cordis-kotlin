package org.cordis

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiberLifecycleTest {
    @Test
    fun `concurrent plugin creation cannot orphan a runtime`() = runBlocking {
        val root = Context()
        val subject = plugin<Unit>(name = "concurrent") { _, _ -> Unit }
        val fibers = coroutineScope {
            List(32) { async(Dispatchers.Default) { root.plugin(subject, Unit).await() } }.map { it.await() }
        }

        assertEquals(1, root.registry.size)
        coroutineScope { fibers.map { launch(Dispatchers.Default) { it.dispose() } }.forEach { it.join() } }
        assertEquals(0, root.registry.size)
    }

    @Test
    fun `registry deletion waits for a fiber still inside creation hooks`() = runBlocking {
        val root = Context()
        val subject = plugin<Unit>(name = "delete-during-create") { _, _ -> Unit }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        root.listen(CoreEvents.Plugin) { event ->
            val fiber = event.payload
            if (fiber.runtime?.plugin === subject && fiber.uid != null) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val creation = executor.submit<Fiber<Unit>> { root.plugin(subject, Unit) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deletion = async(Dispatchers.Default) { root.registry.delete(subject) }
            while (root.registry.has(subject)) delay(1)
            assertFalse(deletion.isCompleted)

            release.countDown()
            val fiber = creation.get(5, TimeUnit.SECONDS)
            deletion.await()
            assertNull(fiber.uid)
            assertEquals(FiberState.DISPOSED, fiber.state)
            assertEquals(0, root.registry.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `dependency change waits for in-flight load and cleanup before reload`() = runBlocking {
        val root = Context()
        val dependency = ServiceKey<Int>("slow-dependency")
        val loadStarted = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val trace = mutableListOf<String>()
        val provision = root.provide(dependency, 1)
        val consumer = root.inject(dependencies(dependency)) { _ ->
            loadStarted.complete(Unit)
            finishLoad.await()
            trace += "loaded"
            collect {
                cleanupStarted.complete(Unit)
                finishCleanup.await()
                trace += "unloaded"
            }
        }

        loadStarted.await()
        assertEquals(FiberState.LOADING, consumer.state)
        val removal = launch(start = CoroutineStart.UNDISPATCHED) { provision.dispose() }
        finishLoad.complete(Unit)
        cleanupStarted.await()
        assertEquals(FiberState.UNLOADING, consumer.state)
        root.provide(dependency, 2)
        finishCleanup.complete(Unit)
        removal.join()
        consumer.await()
        assertEquals(FiberState.ACTIVE, consumer.state)
        assertEquals(listOf("loaded", "unloaded", "loaded"), trace)
        consumer.dispose()
    }

    @Test
    fun `provider restored during in-flight load keeps the same root epoch`() = runBlocking {
        val root = Context()
        val dependency = ServiceKey<Int>("stable-epoch")
        val loadStarted = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        var applies = 0
        val first = root.provide(dependency, 1)
        val consumer = root.inject(dependencies(dependency)) { _ ->
            loadStarted.complete(Unit)
            finishLoad.await()
            applies++
        }

        loadStarted.await()
        val removal = launch(start = CoroutineStart.UNDISPATCHED) { first.dispose() }
        root.provide(dependency, 2)
        finishLoad.complete(Unit)
        removal.join()
        consumer.await()
        assertEquals(FiberState.ACTIVE, consumer.state)
        assertEquals(1, applies)
        consumer.dispose()
    }

    @Test
    fun `failed plugin cleans partial effects without affecting sibling fiber`() = runBlocking {
        val root = Context()
        val failureEvent = EventKey<Unit, Unit>("failure-event")
        var calls = 0
        val subject = plugin<Boolean>(name = "failure") { ctx, succeeds ->
            ctx.listen(failureEvent) { calls++ }
            if (!succeeds) error("plugin error")
        }
        val failed = root.plugin(subject, false)
        val active = root.plugin(subject, true)
        assertFailsWith<IllegalStateException> { failed.await() }
        active.await()
        assertEquals(FiberState.FAILED, failed.state)
        assertEquals(FiberState.ACTIVE, active.state)
        root.emitEvent(failureEvent, Unit)
        assertEquals(1, calls)
        active.dispose(); failed.dispose()
    }

    @Test
    fun `disposer failure is logged and fiber still reaches disposed`() = runBlocking {
        val root = Context()
        val subject = plugin<Unit>(name = "bad-dispose") { _, _ ->
            collect { error("dispose failure") }
        }
        val fiber = root.plugin(subject, Unit).await()
        fiber.dispose()
        assertEquals(FiberState.DISPOSED, fiber.state)
        assertTrue(root.logger.buffer.any { message ->
            message.args.any { it is IllegalStateException && it.message == "dispose failure" }
        })
    }

    @Test
    fun `parent disposal cascades into nested plugins`() = runBlocking {
        val root = Context()
        val trace = mutableListOf<String>()
        val child = plugin<Unit>(name = "child") { _, _ -> collect { trace += "child" } }
        val parent = plugin<Unit>(name = "parent") { ctx, _ ->
            ctx.plugin(child, Unit).await()
            collect { trace += "parent" }
        }
        val fiber = root.plugin(parent, Unit).await()
        fiber.dispose()
        assertEquals(listOf("parent", "child"), trace)
        assertNull(root.registry.get(parent))
        assertNull(root.registry.get(child))
    }

    @Test
    fun `root restart clears owned effects and returns active`() = runBlocking {
        val root = Context()
        var disposed = false
        root.effect("root-owned") { collect { disposed = true } }
        root.fiber.restart()
        assertTrue(disposed)
        assertEquals(FiberState.ACTIVE, root.fiber.state)
        assertTrue(root.fiber.getEffects().isEmpty())
    }

    @Test
    fun `duplicate provide throws synchronously and disposed inject cannot leak`() = runBlocking {
        val root = Context()
        val unique = ServiceKey<Int>("unique")
        root.provide(unique, 1)
        assertFailsWith<IllegalStateException> { root.provide(unique, 2) }
        val consumer = root.inject(dependencies(unique)) { ctx ->
            assertEquals(1, ctx[unique])
        }.await()
        consumer.dispose()
        assertFailsWith<IllegalStateException> { consumer.ctx[unique] }
        Unit
    }

    private class VersionService(ctx: Context, val version: Int) : Service<Unit>(ctx, VersionKey)

    @Test
    fun `config update coalesces with injected provider reload`() = runBlocking {
        val root = Context()
        val applied = mutableListOf<Pair<Int, String>>()
        val provider = root.plugin(servicePlugin<Int, VersionService>(name = "version-provider") { ctx, value ->
            VersionService(ctx, value)
        }, 1).await()
        val consumer = root.plugin(plugin<String>(
            name = "version-consumer",
            inject = dependencies(VersionKey),
        ) { ctx, mode ->
            applied += ctx.require(VersionKey).version to mode
        }, "old").await()

        coroutineScope {
            val providerUpdate = async { provider.update(2) }
            val consumerUpdate = async { consumer.update("new") }
            providerUpdate.await()
            consumerUpdate.await()
        }
        provider.await()
        consumer.await()

        assertEquals(listOf(1 to "old", 2 to "new"), applied)
        provider.dispose()
        consumer.dispose()
    }

    @Test
    fun `consumer with multiple injects activates only after every provider exists`() = runBlocking {
        val root = Context()
        val alphaKey = ServiceKey<Int>("alpha")
        val betaKey = ServiceKey<Int>("beta")
        var loads = 0
        var unloads = 0
        val consumer = root.plugin(plugin<Unit>(
            name = "multi-consumer",
            inject = dependencies(alphaKey, betaKey),
        ) { _, _ ->
            loads++
            collect { unloads++ }
        }, Unit).await()
        assertEquals(FiberState.PENDING, consumer.state)

        val alpha = root.provide(alphaKey, 1).awaitReady()
        consumer.await()
        assertEquals(FiberState.PENDING, consumer.state)
        val beta = root.provide(betaKey, 2).awaitReady()
        consumer.await()
        assertEquals(FiberState.ACTIVE, consumer.state)
        assertEquals(1, loads)

        alpha.dispose()
        consumer.await()
        assertEquals(FiberState.PENDING, consumer.state)
        assertEquals(1, unloads)
        beta.dispose()
        consumer.dispose()
    }

    private companion object {
        val VersionKey = ServiceKey<VersionService>("version")
    }
}
