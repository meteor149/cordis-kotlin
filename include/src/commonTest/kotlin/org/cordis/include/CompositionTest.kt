package org.cordis.include

import org.cordis.loader.EntryOptions
import org.cordis.loader.changeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositionTest {
    @Test
    fun positionedInsertAndMovesComposeWithoutMutatingLayers() {
        val base = listOf(EntryOptions("a", "module"), EntryOptions("b", "module"))
        val group = EntryOptions("group", "group", group = true, config = emptyList<EntryOptions>())
        val layer = CompositionLayer("user", listOf(
            PatchOptions(insert = listOf(group), position = 1),
            PatchOptions(id = "b", parent = changeTo("group"), position = 0),
            PatchOptions(id = "group", insert = listOf(EntryOptions("c", "module")), position = 0),
            PatchOptions(id = "b", config = changeTo("moved")),
            PatchOptions(id = "a", parent = changeTo(null), position = -1),
        ))
        val result = composeEntries(base, listOf(layer)).requireValid()
        assertEquals(listOf("a", "group"), result.entries.map { it.id })
        val nested = (result.entries.last().config as List<*>).filterIsInstance<EntryOptions>()
        assertEquals(listOf("c", "b"), nested.map { it.id })
        assertEquals("moved", nested.last().config)
        assertEquals(CompositionOrigin("user", 1), result.origins["b"]?.get("parent"))
        assertEquals(CompositionOrigin("user", 4), result.origins["a"]?.get("position"))
        assertEquals(listOf("a", "b"), base.map { it.id })
        assertNull(base.last().config)
        assertEquals(emptyList<EntryOptions>(), group.config)
        assertEquals(result.entries, composeEntries(base, listOf(layer)).requireValid().entries)
        assertEquals(base, composeEntries(base, emptyList()).requireValid().entries)
    }

    @Test
    fun groupMovesRetainChildrenAndCanBePatchedAndMovedBackToRoot() {
        val inner = EntryOptions("inner", "group", group = true, config = listOf(EntryOptions("leaf", "module")))
        val outer = EntryOptions("outer", "group", group = true, config = emptyList<EntryOptions>())
        val result = composeEntries(listOf(inner, outer), listOf(CompositionLayer("user", listOf(
            PatchOptions(id = "inner", parent = changeTo("outer")),
            PatchOptions(id = "leaf", disabled = changeTo(true)),
            PatchOptions(id = "inner", parent = changeTo(null), position = 0),
        )))).requireValid()
        assertEquals(listOf("inner", "outer"), result.entries.map { it.id })
        assertEquals(emptyList<EntryOptions>(), result.entries.last().config)
        assertTrue(((result.entries.first().config as List<*>).single() as EntryOptions).disabled == true)
    }

    @Test
    fun movesRejectMissingParentsNonGroupsAndCyclesBeforeChangingStructure() {
        val base = listOf(EntryOptions("group", "group", group = true, config = listOf(
            EntryOptions("nested", "group", group = true, config = emptyList<EntryOptions>()),
        )), EntryOptions("leaf", "module"))
        val result = composeEntries(base, listOf(CompositionLayer("user", listOf(
            PatchOptions(id = "group", parent = changeTo("nested")),
            PatchOptions(id = "group", parent = changeTo("group")),
            PatchOptions(id = "leaf", parent = changeTo("absent")),
            PatchOptions(id = "nested", parent = changeTo("leaf")),
            PatchOptions(id = "absent", parent = changeTo(null)),
        ))))
        assertEquals(base, result.entries)
        assertEquals(listOf(0, 1, 2, 3, 4), result.diagnostics.map { it.operation })
        assertFailsWith<IllegalArgumentException> { result.requireValid() }
    }

    @Test
    fun sameParentPositionsUsePostRemovalSpliceBounds() {
        val base = listOf("a", "b", "c").map { EntryOptions(it, "module") }
        fun move(target: String, position: Int) = composeEntries(base, listOf(CompositionLayer("user", listOf(
            PatchOptions(id = target, position = position),
        )))).requireValid().entries.map { it.id }
        assertEquals(listOf("b", "a", "c"), move("a", -1))
        assertEquals(listOf("c", "a", "b"), move("c", Int.MIN_VALUE))
        assertEquals(listOf("b", "c", "a"), move("a", Int.MAX_VALUE))
    }

    @Test
    fun contradictoryStructuralPatchesAndInvalidAncestryRemainDiagnosable() {
        val base = listOf(EntryOptions("a", "module"))
        val result = composeEntries(base, listOf(CompositionLayer("user", listOf(
            PatchOptions(id = "a", remove = true, position = 0),
            PatchOptions(insert = listOf(EntryOptions("b", "module")), parent = changeTo(null)),
        ))))
        assertEquals(base, result.entries)
        assertEquals(2, result.diagnostics.size)
        val duplicate = EntryOptions("group", "group", group = true, config = listOf(
            EntryOptions("group", "group", group = true, config = emptyList<EntryOptions>()),
        ))
        val invalid = composeEntries(listOf(duplicate) + base, listOf(CompositionLayer("user", listOf(
            PatchOptions(id = "a", parent = changeTo("group")),
        ))))
        assertTrue(invalid.diagnostics.any { it.message.contains("ancestry") })
    }

    @Test
    fun insertedEntriesCanBeConfiguredByLaterLayersWithoutMutatingSources() {
        val entry = EntryOptions(id = "model", name = "model", config = mapOf("temperature" to 1))
        val bundle = CompositionLayer("bundle", listOf(PatchOptions(insert = listOf(entry))))
        val user = CompositionLayer("user", listOf(PatchOptions(id = "model", config = changeTo(null))))
        val result = composeEntries(emptyList(), listOf(bundle, user)).requireValid()
        assertNull(result.entries.single().config)
        assertEquals(mapOf("temperature" to 1), entry.config)
        assertEquals(CompositionOrigin("user", 0), result.origins["model"]?.get("config"))
        assertEquals(entry.config, composeEntries(emptyList(), listOf(bundle)).entries.single().config)
    }

    @Test
    fun nestedInsertReplacementAndRemovalRespectLayerOrder() {
        val group = EntryOptions(id = "tools", name = "group", group = true, config = emptyList<EntryOptions>())
        val layers = listOf(CompositionLayer("bundle", listOf(
            PatchOptions(insert = listOf(group)),
            PatchOptions(id = "tools", insert = listOf(EntryOptions(id = "shell", name = "bash"))),
            PatchOptions(id = "shell", name = "bash", replacement = "pwsh", disabled = changeTo(true)),
        )))
        val first = composeEntries(emptyList(), layers).requireValid()
        val child = (first.entries.single().config as List<*>).single() as EntryOptions
        assertEquals("pwsh", child.name)
        assertTrue(child.disabled == true)
        val removed = composeEntries(emptyList(), layers + CompositionLayer("user", listOf(
            PatchOptions(id = "shell", remove = true),
        ))).requireValid()
        assertEquals(emptyList<EntryOptions>(), removed.entries.single().config)
        assertFalse("shell" in removed.origins)
        assertEquals(emptyList<EntryOptions>(), group.config)
    }

    @Test
    fun invalidTargetsAndDuplicateIdsHaveSourceDiagnostics() {
        val result = composeEntries(emptyList(), listOf(CompositionLayer("profile", listOf(
            PatchOptions(id = "missing", disabled = changeTo(true)),
            PatchOptions(insert = listOf(EntryOptions(id = "x", name = "a"), EntryOptions(id = "x", name = "b"))),
        ))))
        assertEquals(listOf(0, 1), result.diagnostics.map { it.operation })
        assertTrue(result.diagnostics.all { it.layer == "profile" })
        assertFailsWith<IllegalArgumentException> { result.requireValid() }
    }

    @Test
    fun returnedContainersNeverModifyBundleDefaults() {
        val config = mutableMapOf("nested" to mutableListOf("default"))
        val entry = EntryOptions(id = "x", name = "a", config = config)
        val layers = listOf(CompositionLayer("bundle", listOf(PatchOptions(insert = listOf(entry)))))
        val first = composeEntries(emptyList(), layers)
        first.entries.single().config = "changed"
        first.entries.single().name = "changed"
        val second = composeEntries(emptyList(), layers)
        assertEquals("a", second.entries.single().name)
        assertEquals(config, second.entries.single().config)
        assertEquals(first.origins, second.origins)
    }
}
