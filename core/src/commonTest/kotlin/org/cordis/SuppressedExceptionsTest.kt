package org.cordis

import kotlin.test.Test
import kotlin.test.assertEquals

class SuppressedExceptionsTest {
    @Test
    fun `aggregate event exception retains every cause`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        val aggregate = AggregateEventException(listOf(first, second))

        assertEquals(listOf(first, second), aggregate.suppressedExceptions)
    }
}
