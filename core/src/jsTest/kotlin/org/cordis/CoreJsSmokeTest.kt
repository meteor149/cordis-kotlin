package org.cordis

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreJsSmokeTest {
    @Test
    fun lifecycleAndIdentityCollectionsWorkOnJs() = runTest {
        val root = Context()
        val order = mutableListOf<String>()
        val feature = plugin<Unit>(name = "feature") { _, _ ->
            collect { order += "first" }
            collect { order += "second" }
        }
        val fiber = root.plugin(feature, Unit).await()
        assertEquals(FiberState.ACTIVE, fiber.state)
        root.logger("js-smoke").info("hello")
        assertEquals(FiberLogMeta(0L, "root"), root.logger.buffer.last().fiber)
        fiber.dispose()
        assertEquals(listOf("second", "first"), order)
        assertTrue(root.registry.keys().none { it === feature })
    }
}
