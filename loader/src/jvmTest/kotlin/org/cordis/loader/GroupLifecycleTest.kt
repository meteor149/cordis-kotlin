package org.cordis.loader

import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupLifecycleTest {
    private val probeConfig = org.cordis.InterceptKey<Map<String, Any?>>("probe")
    @Test
    fun `nested groups disable and re-enable descendants incrementally`() = runBlocking {
        val root = Context()
        val loader = Loader(root)
        var loads = 0
        var unloads = 0
        loader.builtins["group"] = GroupPlugin
        loader.builtins["foo"] = plugin<Unit>(name = "foo") { _, _ ->
            loads++
            collect { unloads++ }
        }

        val outer = loader.create(EntryOptions(
            name = "cordis:group", group = true,
            config = listOf(EntryOptions(id = "outer-foo", name = "cordis:foo", config = Unit)),
        ))
        val inner = loader.create(EntryOptions(
            name = "cordis:group", group = true,
            config = listOf(EntryOptions(id = "inner-foo", name = "cordis:foo", config = Unit)),
        ), outer)
        assertEquals(2, loads)
        assertEquals(4, loader.entries().count())

        loader.update(inner, EntryPatch(disabled = changeTo(true)))
        assertEquals(1, unloads)
        loader.update(outer, EntryPatch(disabled = changeTo(true)))
        assertEquals(2, unloads)

        loader.update(inner, EntryPatch(disabled = changeTo(null)))
        assertEquals(2, loads)
        loader.update(outer, EntryPatch(disabled = changeTo(null)))
        assertEquals(4, loads)
        assertEquals(4, loader.entries().count())
        loader.root.stop()
    }

    @Test
    fun `entry transfer only reloads when enabled state changes`() = runBlocking {
        val root = Context()
        val loader = Loader(root)
        var loads = 0
        var unloads = 0
        loader.builtins["group"] = GroupPlugin
        loader.builtins["foo"] = plugin<Unit>(name = "foo") { _, _ ->
            loads++
            collect { unloads++ }
        }
        val id = loader.create(EntryOptions(name = "cordis:foo", config = Unit))
        val alpha = loader.create(EntryOptions(name = "cordis:group", group = true, config = emptyList<EntryOptions>()))
        val beta = loader.create(
            EntryOptions(name = "cordis:group", group = true, disabled = true, config = emptyList<EntryOptions>()),
            alpha,
        )
        val gamma = loader.create(
            EntryOptions(name = "cordis:group", group = true, config = emptyList<EntryOptions>()),
            beta,
        )
        assertEquals(1, loads)

        loader.update(id, EntryPatch(), alpha)
        assertEquals(1, loads)
        assertEquals(0, unloads)
        loader.update(id, EntryPatch(), beta)
        assertEquals(1, unloads)
        loader.update(id, EntryPatch(), gamma)
        assertEquals(1, unloads)
        loader.update(id, EntryPatch(), null)
        assertEquals(2, loads)
        assertEquals(4, loader.entries().count())
        loader.root.stop()
    }

    @Test
    fun `intercept lineage follows nested group rebase`() = runBlocking {
        val root = Context()
        val loader = Loader(root)
        loader.builtins["group"] = GroupPlugin
        val observed = mutableListOf<List<Map<String, Any?>>>()
        loader.builtins["probe"] = plugin<Unit>(name = "probe") { ctx, _ ->
            observed.add(ctx.interceptValues(probeConfig))
        }
        val outer = loader.create(EntryOptions(
            name = "cordis:group", group = true, config = emptyList<EntryOptions>(),
            intercept = mapOf("probe" to mapOf("a" to 1)),
        ))
        val inner = loader.create(EntryOptions(
            name = "cordis:group", group = true, config = emptyList<EntryOptions>(),
            intercept = mapOf("probe" to mapOf("b" to 2)),
        ), outer)
        loader.create(EntryOptions(
            id = "probe", name = "cordis:probe", config = Unit,
            intercept = mapOf("probe" to mapOf("c" to 3)),
        ), inner)
        assertEquals(
            listOf<Map<String, Any?>>(mapOf("a" to 1), mapOf("b" to 2), mapOf("c" to 3)),
            observed.single(),
        )
        loader.root.stop()
    }
}
