package org.cordis.utils

import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ListTest {
    @Test
    fun `entries belong to the creating fiber`() = runBlocking {
        val root = Context()
        val list = ContextList<String>(root, "list")
        val first = root.plugin(plugin<Unit>(name = "first") { ctx, _ -> list.add(ctx, "a") }, Unit).await()
        val second = root.plugin(plugin<Unit>(name = "second") { ctx, _ -> list.add(ctx, "b") }, Unit).await()
        assertEquals(listOf("a", "b"), list.toList())
        second.dispose()
        assertEquals(listOf("a"), list.toList())
        first.dispose()
        assertEquals(emptyList(), list.toList())
    }
}
