package org.cordis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsyncEventsTest {
    @Test
    fun `parallel waits for async listeners and aggregates sync and async failures`() = runBlocking {
        val root = Context()
        val event = EventKey<Unit, Any?>("event")
        var settled = false
        root.listenAsync(event) {
            delay(10)
            settled = true
            error("async")
        }
        root.listen(event) { error("sync") }

        val failure = assertFailsWith<AggregateEventException> { root.parallelEvent(event, Unit) }
        assertTrue(settled)
        assertEquals(setOf("async", "sync"), failure.causes.map { it.message }.toSet())
    }

    @Test
    fun `serial awaits async result and stops at first bailed value`() = runBlocking {
        val root = Context()
        val event = EventKey<Unit, Any?>("event")
        val trace = mutableListOf<Int>()
        root.listenAsync(event) {
            delay(5)
            trace += 1
            false
        }
        root.listenAsync(event) {
            trace += 2
            "done"
        }
        root.listen(event) { trace += 3; null }

        assertEquals("done", root.serialEvent(event, Unit))
        assertEquals(listOf(1, 2), trace)
    }

    @Test
    fun `once async listener is removed before its first invocation settles`() = runBlocking {
        val root = Context()
        val event = EventKey<Unit, Unit>("event")
        var calls = 0
        root.listenOnceAsync(event) {
            calls++
            delay(5)
        }

        root.parallelEvent(event, Unit)
        root.parallelEvent(event, Unit)
        assertEquals(1, calls)
    }

    @Test
    fun `parallel preserves coroutine cancellation`() = runBlocking {
        val root = Context()
        val event = EventKey<Unit, Unit>("event")
        root.listenAsync(event) {
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> { root.parallelEvent(event, Unit) }
        Unit
    }
}
