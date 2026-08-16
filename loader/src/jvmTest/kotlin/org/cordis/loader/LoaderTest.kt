package org.cordis.loader

import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.FiberState
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoaderTest {
    @Test
    fun `entry create update disable and remove coordinate fibers`() = runBlocking {
        val root = Context()
        val loaderFiber = root.plugin(LoaderPlugin, LoaderConfig()).await()
        val loader = root.require(Loader.Key)
        val values = mutableListOf<Int>()
        val disposals = mutableListOf<Int>()
        val builtin = plugin<Any?>(name = "fixture") { _, config ->
            val value = (config as? Number ?: error("fixture config must be numeric")).toInt()
            values += value
            collect { disposals += value }
        }
        loader.builtins["fixture"] = builtin

        val id = loader.create(EntryOptions(id = "fixture", name = "cordis:fixture", config = 1))
        val entry = loader.resolve(id)
        assertEquals(FiberState.ACTIVE, entry.fiber?.state)
        assertEquals(listOf(1), values)

        loader.update(id, EntryPatch(config = changeTo(2)))
        assertEquals(listOf(1, 2), values)
        assertTrue(1 in disposals)

        loader.update(id, EntryPatch(disabled = changeTo(true)))
        assertNull(entry.fiber)
        assertTrue(2 in disposals)

        loader.remove(id)
        assertFalse(loader.store.containsKey(id))
        loaderFiber.dispose()
    }

    @Test
    fun `global and local realms use identity matching Cordis symbols`() {
        val root = Context()
        val loader = Loader(root)
        val entryA = Entry(loader).also { it.options = EntryOptions(id = "a", name = "x") }
        val entryB = Entry(loader).also { it.options = EntryOptions(id = "b", name = "x") }
        val localA = LocalRealm(entryA)
        val localB = LocalRealm(entryB)
        assertTrue(localA.access("svc", true) !== localB.access("svc", true))
        val global = GlobalRealm("shared")
        val ephemeral1 = global.access("temporary")
        val ephemeral2 = global.access("temporary")
        assertTrue(ephemeral1 !== ephemeral2)
        assertEquals(0, global.size)
        assertTrue(global.access("svc", true) === global.access("svc", true))
        assertEquals(1, global.size)
        global.delete("svc")
        assertEquals(0, global.size)
    }

    @Test
    fun `interpolation recursively resolves portable expressions`() = runBlocking {
        val root = Context()
        root.provide(ServiceKey<Int>("answer"), 42).awaitReady()
        val value = interpolate(root, mapOf(
            "nested" to listOf(JsExpr("answer"), JsExpr("true"), JsExpr("42"), JsExpr("\"a\\nb\"")),
        ))
        assertEquals(mapOf("nested" to listOf(42, true, 42, "a\nb")), value)
    }

    @Test
    fun `negative positions follow JavaScript splice semantics`() = runBlocking {
        val loader = Loader(Context())
        loader.create(EntryOptions(id = "a", name = "unused", disabled = true))
        loader.create(EntryOptions(id = "b", name = "unused", disabled = true))
        loader.create(EntryOptions(id = "c", name = "unused", disabled = true), position = -1)
        assertEquals(listOf("a", "c", "b"), loader.root.data.map { it.id })

        loader.update("a", EntryPatch(), parent = null, position = -1)
        assertEquals(listOf("c", "a", "b"), loader.root.data.map { it.id })
        loader.root.stop()
    }

    @Test
    fun `moving provider realm unloads old consumer and activates new consumer`() = runBlocking {
        val root = Context()
        val loaderFiber = root.plugin(LoaderPlugin, LoaderConfig()).await()
        val loader = root.require(Loader.Key)
        val realmService = ServiceKey<String>("realm-service")
        val trace = mutableListOf<String>()
        val provider = plugin<Unit>(name = "realm-provider") { ctx, _ ->
            ctx.provide(realmService, "value")
        }
        val consumer = plugin<Unit>(
            name = "realm-consumer",
            inject = dependencies(realmService),
        ) { ctx, _ ->
            val id = checkNotNull(ctx.attributes[Entry.ATTRIBUTE]).options.id
            trace += "$id:load:${ctx[realmService]}" 
            collect { trace += "$id:unload" }
        }
        loader.builtins["provider"] = provider
        loader.builtins["consumer"] = consumer

        loader.create(EntryOptions(
            id = "provider", name = "cordis:provider", config = Unit,
            isolate = sharedIsolation("a", realmService),
        ))
        loader.create(EntryOptions(
            id = "consumer-a", name = "cordis:consumer", config = Unit,
            isolate = sharedIsolation("a", realmService),
        ))
        loader.create(EntryOptions(
            id = "consumer-b", name = "cordis:consumer", config = Unit,
            isolate = sharedIsolation("b", realmService),
        ))
        assertEquals(FiberState.ACTIVE, loader.resolve("consumer-a").fiber?.state)
        assertEquals(FiberState.PENDING, loader.resolve("consumer-b").fiber?.state)

        loader.update("provider", EntryPatch(isolate = changeTo(sharedIsolation("b", realmService))))
        loader.await()
        assertEquals(FiberState.PENDING, loader.resolve("consumer-a").fiber?.state)
        assertEquals(FiberState.ACTIVE, loader.resolve("consumer-b").fiber?.state)
        assertTrue("consumer-a:unload" in trace)
        assertTrue("consumer-b:load:value" in trace)
        loaderFiber.dispose()
    }
}
