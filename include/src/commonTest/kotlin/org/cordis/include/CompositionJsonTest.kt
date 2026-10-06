package org.cordis.include

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.loader.EntryOptions
import org.cordis.loader.changeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class CompositionJsonTest {
    @Test
    fun insertionAndOverridesRetainJsonTypesWithoutInputAliases() {
        val fields = mutableMapOf<String, JsonElement>("enabled" to JsonPrimitive(true))
        val input = JsonObject(fields)
        val array = JsonArray(listOf(input))
        val result = composeEntries(emptyList(), listOf(CompositionLayer("first", listOf(
            PatchOptions(insert = listOf(EntryOptions("entry", "module", input,
                inject = mapOf("dependency" to array), extra = mapOf("metadata" to input)))),
            PatchOptions(id = "entry", config = changeTo(array)),
        )))).requireValid()
        val entry = result.entries.single()
        val actual = assertIs<JsonArray>(entry.config)
        assertNotSame(array, actual)
        assertIs<JsonObject>(actual.single())
        assertIs<JsonArray>(entry.inject!!.getValue("dependency"))
        assertIs<JsonObject>(entry.extra.getValue("metadata"))
        fields["enabled"] = JsonPrimitive(false)
        assertEquals(JsonPrimitive(true), (actual.single() as JsonObject)["enabled"])
        val second = composeEntries(result.entries, emptyList()).requireValid()
        assertEquals(actual, second.entries.single().config)
        assertNotSame(actual, second.entries.single().config)
    }
}
