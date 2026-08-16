package org.cordis

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TypedEventsTest {
    @Test
    fun typedEventCarriesPayloadAndResultWithoutArgumentCasts() = runTest {
        val query = EventKey<Int, String>("query")
        val root = Context()
        root.listen(query) { event -> "value=${event.payload}" }

        assertEquals("value=42", root.serialEvent(query, 42))
    }

    @Test
    fun equalDescriptionsDoNotMergeDistinctEventTokens() {
        val first = EventKey<String, Unit>("change")
        val second = EventKey<String, Unit>("change")
        val root = Context()
        val received = mutableListOf<String>()
        root.listen(first) { event -> received += "first:${event.payload}" }
        root.listen(second) { event -> received += "second:${event.payload}" }

        root.emitEvent(first, "a")
        root.emitEvent(second, "b")

        assertEquals(listOf("first:a", "second:b"), received)
    }

    @Test
    fun typedWaterfallKeepsMiddlewarePayloadAndResultTypes() = runTest {
        val transform = EventKey<Int, String>("transform")
        val root = Context()
        root.interceptEvent(transform) { event, next -> "${event.payload}:${next()}" }

        assertEquals("7:done", root.waterfallEvent(transform, 7) { "done" })
    }

    @Test
    fun typedWaterfallSupportsSuspendingMiddleware() = runTest {
        val transform = EventKey<Int, String>("async-transform")
        val root = Context()
        root.interceptEventAsync(transform) { event, next ->
            delay(1)
            "${event.payload}:${next()}"
        }

        assertEquals("9:done", root.waterfallEvent(transform, 9) { "done" })
    }
}
