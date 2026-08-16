package org.cordis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EffectAndInertiaTest {
    @Test
    fun `nested effects appear only below their collecting parent`() = runBlocking {
        val root = Context()
        val nested = EventKey<Unit, Unit>("nested-event")
        val rootEvent = EventKey<Unit, Unit>("root-event")
        val outer = root.effect("outer") {
            collect(root.listen(nested) { })
            collect(root.effect("inner") { collect { } })
        }
        root.listen(rootEvent) { }
        assertEquals(
            listOf(
                EffectMeta("outer", mutableListOf(
                    EffectMeta("ctx.listen(nested-event)"),
                    EffectMeta("inner"),
                )),
                EffectMeta("ctx.listen(root-event)"),
            ),
            root.fiber.getEffects(),
        )
        outer.dispose()
    }

    @Test
    fun `dispose waits for in-flight async return then recovers it`() = runBlocking {
        val root = Context()
        val sequence = mutableListOf<Int>()
        val handle = root.effect {
            delay(40)
            sequence += 1
            collect { sequence += 2 }
        }
        handle.dispose()
        assertEquals(listOf(1, 2), sequence)
    }

    @Test
    fun `async iterator checkpoint keeps one in-flight yield then aborts`() = runBlocking {
        val root = Context()
        val sequence = mutableListOf<Int>()
        val handle = root.effect {
            delay(40)
            sequence += 1
            collect { sequence += 2 }
            if (!ensureActive()) return@effect
            delay(40)
            sequence += 3
            collect { sequence += 4 }
        }
        val disposal = launch { handle.dispose() }
        disposal.join()
        assertEquals(listOf(1, 2), sequence)
    }

    @Test
    fun `synchronous failure cleans collected effects before throwing`() {
        val root = Context()
        val sequence = mutableListOf<Int>()
        assertFailsWith<IllegalStateException> {
            root.effect {
                collect { sequence += 1 }
                error("test")
            }
        }
        assertEquals(listOf(1), sequence)
        assertTrue(root.fiber.getEffects().isEmpty())
    }

    @Test
    fun `synchronous failure does not block on suspending cleanup`() = runBlocking {
        val root = Context()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val invocation = async(Dispatchers.Default) {
            assertFailsWith<IllegalStateException> {
                root.effect {
                    collect {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                        cleanupFinished.complete(Unit)
                    }
                    error("test")
                }
            }
        }

        cleanupStarted.await()
        try {
            withTimeout(5_000) { invocation.await() }
            assertFalse(cleanupFinished.isCompleted)
            assertTrue(root.fiber.getEffects().isEmpty())
        } finally {
            releaseCleanup.complete(Unit)
        }
        cleanupFinished.await()
    }

    @Test
    fun `asynchronous failure rejects readiness and disposal after partial cleanup`() = runBlocking {
        val root = Context()
        val sequence = mutableListOf<Int>()
        val handle = root.effect {
            delay(10)
            collect { sequence += 1 }
            error("async test")
        }
        assertFailsWith<IllegalStateException> { handle.awaitReady() }
        assertFailsWith<IllegalStateException> { handle.dispose() }
        assertEquals(listOf(1), sequence)
    }

    @Test
    fun `update middleware can stop the terminal config replacement`() = runBlocking {
        val root = Context()
        val applied = mutableListOf<String>()
        val subject = plugin<String>(name = "blocked-update") { ctx, config ->
            applied += config
            ctx.listen(CoreEvents.Update) { null }
        }
        val fiber = root.plugin(subject, "old").await()
        fiber.update("new")
        assertEquals("old", fiber.config)
        assertEquals(listOf("old"), applied)
        fiber.dispose()
    }
}
