package org.cordis.loader

import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.FiberState
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class IsolationParityTest {
    private val isolated = ServiceKey<String>("isolated")
    private val irrelevant = ServiceKey<Any>("irrelevant")
    private data class Fixture(
        val root: Context,
        val loader: Loader,
        val trace: MutableList<String>,
    )

    private fun fixture(): Fixture {
        val root = Context()
        val loader = Loader(root)
        val trace = mutableListOf<String>()
        loader.builtins["group"] = GroupPlugin
        loader.builtins["provider"] = plugin<Map<String, String>>(name = "provider") { ctx, config ->
            ctx.provide(isolated, config["value"])
        }
        loader.builtins["consumer"] = plugin<Unit>(
            name = "consumer", inject = dependencies(isolated),
        ) { ctx, _ ->
            val id = checkNotNull(ctx.attributes[Entry.ATTRIBUTE]).options.id
            trace += "$id:load:${ctx[isolated]}" 
            collect { trace += "$id:unload" }
        }
        return Fixture(root, loader, trace)
    }

    @Test
    fun `adding and removing relevant injector isolation reacts once`() = runBlocking {
        val (_, loader, trace) = fixture()
        loader.create(EntryOptions(
            id = "provider", name = "cordis:provider", config = mapOf("value" to "root"),
        ))
        loader.create(EntryOptions(id = "consumer", name = "cordis:consumer", config = Unit))
        assertEquals(listOf("consumer:load:root"), trace)

        loader.update("consumer", EntryPatch(isolate = changeTo(localIsolation(isolated))))
        loader.await()
        assertEquals(FiberState.PENDING, loader.resolve("consumer").fiber?.state)
        assertEquals(1, trace.count { it == "consumer:unload" })

        loader.update("consumer", EntryPatch(isolate = changeTo(localIsolation(isolated, irrelevant))))
        loader.await()
        assertEquals(1, trace.count { it == "consumer:unload" })

        loader.update("consumer", EntryPatch(isolate = changeTo(localIsolation(irrelevant))))
        loader.await()
        assertEquals(FiberState.ACTIVE, loader.resolve("consumer").fiber?.state)
        assertEquals(2, trace.count { it.startsWith("consumer:load") })
        loader.root.stop()
    }

    @Test
    fun `provider implementation transfers between realm keys without restart`() = runBlocking {
        val (_, loader, trace) = fixture()
        loader.create(EntryOptions(
            id = "provider", name = "cordis:provider", config = mapOf("value" to "value"),
        ))
        loader.create(EntryOptions(id = "consumer", name = "cordis:consumer", config = Unit))
        val providerFiber = loader.resolve("provider").fiber

        loader.update("provider", EntryPatch(isolate = changeTo(localIsolation(isolated))))
        loader.await()
        assertEquals(providerFiber, loader.resolve("provider").fiber)
        assertEquals(FiberState.PENDING, loader.resolve("consumer").fiber?.state)

        loader.update("provider", EntryPatch(isolate = changeTo(emptyMap())))
        loader.await()
        assertEquals(providerFiber, loader.resolve("provider").fiber)
        assertEquals(FiberState.ACTIVE, loader.resolve("consumer").fiber?.state)
        assertEquals(2, trace.count { it.startsWith("consumer:load") })
        loader.root.stop()
    }

    @Test
    fun `disposing a relocated provider removes its current realm binding`() = runBlocking {
        val (_, loader, _) = fixture()
        val group = loader.create(EntryOptions(
            id = "group",
            name = "cordis:group",
            group = true,
            isolate = localIsolation(isolated),
            config = emptyList<EntryOptions>(),
        ))
        loader.create(EntryOptions(
            id = "provider",
            name = "cordis:provider",
            config = mapOf("value" to "value"),
        ))
        loader.create(EntryOptions(id = "consumer", name = "cordis:consumer", config = Unit))

        loader.update("provider", EntryPatch(), group)
        loader.update("consumer", EntryPatch(), group)
        loader.await()
        assertEquals(FiberState.ACTIVE, loader.resolve("consumer").fiber?.state)

        loader.remove("provider")
        loader.await()
        assertEquals(FiberState.PENDING, loader.resolve("consumer").fiber?.state)
        loader.root.stop()
    }

    @Test
    fun `unused shared realms are collected and recreated with new identity`() = runBlocking {
        val (_, loader, _) = fixture()
        loader.create(EntryOptions(
            id = "provider",
            name = "cordis:provider",
            config = mapOf("value" to "value"),
            isolate = sharedIsolation("shared", isolated),
        ))
        val previous = loader.globalRealm("shared")

        loader.remove("provider")

        assertNotSame(previous, loader.globalRealm("shared"))
        loader.root.stop()
    }

    @Test
    fun `changing group realm rebinds child consumer to another provider`() = runBlocking {
        val (_, loader, trace) = fixture()
        loader.create(EntryOptions(
            id = "alpha", name = "cordis:provider", config = mapOf("value" to "alpha"),
            isolate = sharedIsolation("alpha", isolated),
        ))
        loader.create(EntryOptions(
            id = "beta", name = "cordis:provider", config = mapOf("value" to "beta"),
            isolate = sharedIsolation("beta", isolated),
        ))
        val group = loader.create(EntryOptions(
            id = "group", name = "cordis:group", group = true,
            isolate = sharedIsolation("alpha", isolated), config = emptyList<EntryOptions>(),
        ))
        val consumer = loader.create(
            EntryOptions(id = "child", name = "cordis:consumer", config = Unit), group,
        )
        assertEquals("child:load:alpha", trace.last())
        val childFiber = loader.resolve(consumer).fiber

        loader.update(group, EntryPatch(isolate = changeTo(sharedIsolation("beta", isolated))))
        loader.await()
        assertEquals(childFiber, loader.resolve(consumer).fiber)
        assertEquals(listOf("child:load:alpha", "child:unload", "child:load:beta"), trace.filter { it.startsWith("child:") })
        loader.root.stop()
    }

    @Test
    fun `moving injector and provider across isolated group preserves dependency ordering`() = runBlocking {
        val (_, loader, trace) = fixture()
        val group = loader.create(EntryOptions(
            id = "group", name = "cordis:group", group = true,
            isolate = localIsolation(isolated), config = emptyList<EntryOptions>(),
        ))
        val provider = loader.create(EntryOptions(
            id = "provider", name = "cordis:provider", config = mapOf("value" to "value"),
        ))
        val consumer = loader.create(EntryOptions(id = "consumer", name = "cordis:consumer", config = Unit))
        loader.update(consumer, EntryPatch(), group)
        loader.await()
        assertEquals(FiberState.PENDING, loader.resolve(consumer).fiber?.state)
        loader.update(provider, EntryPatch(), group)
        loader.await()
        assertEquals(FiberState.ACTIVE, loader.resolve(consumer).fiber?.state)
        loader.update(consumer, EntryPatch(), null)
        loader.await()
        assertEquals(FiberState.PENDING, loader.resolve(consumer).fiber?.state)
        loader.update(provider, EntryPatch(), null)
        loader.await()
        assertEquals(FiberState.ACTIVE, loader.resolve(consumer).fiber?.state)
        assertEquals(3, trace.count { it.startsWith("consumer:load") })
        loader.root.stop()
    }
}
