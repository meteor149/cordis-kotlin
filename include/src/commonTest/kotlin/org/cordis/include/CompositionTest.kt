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
