package org.cordis.loader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TreeMoveTest {
    private class Fixture {
        val loader = Loader(Context())
        val answer = ServiceKey<String>("move/answer")
        val observed = mutableListOf<String>()
        val released = mutableListOf<String>()
        var pause: CompletableDeferred<Unit>? = null
        var pauseValue = "destination"
        val entered = CompletableDeferred<Unit>()

        init {
            loader.builtins["group"] = GroupPlugin
            loader.builtins["provider"] = plugin<String> { context, value -> context.provide(answer, value) }
            loader.builtins["consumer"] = plugin<Unit>(inject = dependencies(answer)) { context, _ ->
                val value = context.require(answer)
                observed += value
                collect { released += value }
                if (value == pauseValue && pause != null) { entered.complete(Unit); pause!!.await() }
            }
        }

        fun group(id: String, consumer: Boolean) = EntryOptions(id, "cordis:group", group = true,
            isolate = mapOf(answer.name to IsolationRule.Local), config = listOf(
                EntryOptions("$id-provider", "cordis:provider", config = id),
            ) + if (consumer) listOf(EntryOptions("traveller", "cordis:consumer", config = Unit)) else emptyList())

        fun tree(moved: Boolean) = listOf(group("destination", moved), group("source", !moved))
    }

    @Test
    fun `destination before source recreates moved consumer in the new realm`() = runBlocking {
        val fixture = Fixture()
        val loader = fixture.loader
        try {
            loader.applyTree(fixture.tree(false))
            val old = loader.resolve("traveller").fiber!!.uid
            val provider = loader.resolve("source-provider").fiber!!.uid
            loader.applyTree(fixture.tree(true))
            assertEquals(listOf("source", "destination"), fixture.observed)
            assertEquals(listOf("source"), fixture.released)
            assertEquals("destination", loader.resolve("traveller").parent.ctx.attributes[Entry.ATTRIBUTE]!!.options.id)
            assertNotEquals(old, loader.resolve("traveller").fiber!!.uid)
            assertEquals(provider, loader.resolve("source-provider").fiber!!.uid)
            assertEquals(setOf("destination", "source", "destination-provider", "source-provider", "traveller"), loader.store.keys)
        } finally { loader.root.stop() }
        assertEquals(listOf("source", "destination"), fixture.released)
    }

    @Test
    fun `same parent reorder preserves resources and instance fibers`() = runBlocking {
        val loader = Loader(Context())
        var released = 0
        loader.builtins["resource"] = plugin<Unit> { _, _ -> collect { released++ } }
        val entries = listOf("first", "second", "third").map { EntryOptions(it, "cordis:resource", config = Unit) }
        try {
            loader.applyTree(entries)
            val identities = loader.store.mapValues { it.value.fiber!!.uid }
            loader.applyTree(listOf(entries[2], entries[0], entries[1]))
            assertEquals(listOf("third", "first", "second"), loader.root.data.map { it.id })
            assertEquals(identities, loader.store.mapValues { it.value.fiber!!.uid })
            assertEquals(0, released)
        } finally { loader.root.stop() }
        assertEquals(3, released)
    }

    @Test
    fun `publication failure restores moved consumer and source realm`() = runBlocking {
        val fixture = Fixture()
        val loader = fixture.loader
        try {
            loader.applyTree(fixture.tree(false))
            assertFailsWith<IllegalStateException> {
                loader.withTreeTransaction(fixture.tree(true)) { error("Publication refused") }
            }
            assertEquals(fixture.tree(false), loader.root.data)
            assertEquals("source", fixture.observed.last())
            assertEquals("source", loader.resolve("traveller").parent.ctx.attributes[Entry.ATTRIBUTE]!!.options.id)
            assertEquals(1, fixture.observed.size - fixture.released.size)
        } finally { loader.root.stop() }
        assertEquals(fixture.observed.size, fixture.released.size)
    }

    @Test
    fun `cancelling moved group allocation drains its nested children before restoration`() = runBlocking {
        val fixture = Fixture()
        val loader = fixture.loader
        try {
            loader.applyTree(fixture.tree(false))
            fixture.pauseValue = "source"
            fixture.pause = CompletableDeferred()
            val destination = fixture.group("destination", false)
            val nested = destination.copy(config = (destination.config as List<*>) + fixture.group("source", true))
            val moving = async { loader.applyTree(listOf(nested)) }
            withTimeout(10_000) { fixture.entered.await() }
            fixture.pause = null
            withTimeout(10_000) { moving.cancelAndJoin() }
            assertEquals(fixture.tree(false), loader.root.data)
            assertEquals(loader.root, loader.resolve("source").parent)
            assertEquals(1, fixture.observed.size - fixture.released.size)
        } finally { loader.root.stop() }
        assertEquals(fixture.observed.size, fixture.released.size)
    }

    @Test
    fun `cancellation during moved allocation joins cleanup and restores source`() = runBlocking {
        val fixture = Fixture()
        val loader = fixture.loader
        try {
            loader.applyTree(fixture.tree(false))
            fixture.pause = CompletableDeferred()
            val moving = async { loader.applyTree(fixture.tree(true)) }
            withTimeout(10_000) { fixture.entered.await() }
            withTimeout(10_000) { moving.cancelAndJoin() }
            assertEquals(fixture.tree(false), loader.root.data)
            assertEquals("source", fixture.observed.last())
            assertEquals(1, fixture.observed.size - fixture.released.size)
        } finally { loader.root.stop() }
        assertEquals(fixture.observed.size, fixture.released.size)
    }
}
