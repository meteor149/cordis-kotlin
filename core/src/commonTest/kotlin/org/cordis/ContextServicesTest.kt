package org.cordis

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class ContextServicesTest {
    private data class EqualRealm(val value: String)

    @Test
    fun equalAttributeDescriptionsDoNotMergeDistinctTypedKeys() {
        val root = Context()
        val first = AttributeKey<Int>("same")
        val second = AttributeKey<Int>("same")

        root.attributes[first] = 1

        assertEquals(1, root.attributes[first])
        assertNull(root.attributes[second])
    }

    @Test
    fun equalServiceNamesCannotBypassTypedKeyIdentity() = runTest {
        val root = Context()
        val provided = ServiceKey<Int>("same")
        val incompatible = ServiceKey<String>("same")
        val handle = root.provide(provided, 1).awaitReady()

        val error = assertFailsWith<IllegalStateException> { root[incompatible] }
        assertEquals(
            "service \"same\" was requested with a different ServiceKey instance",
            error.message,
        )
        handle.dispose()
    }

    @Test
    fun equalButDistinctRealmsRemainIsolated() = runTest {
        val firstRealm = EqualRealm("same")
        val secondRealm = EqualRealm("same")
        assertEquals(firstRealm, secondRealm)
        assertNotSame(firstRealm, secondRealm)

        val root = Context()
        val answer = ServiceKey<Int>("answer")
        val first = root.isolate(answer, firstRealm)
        val second = root.isolate(answer, secondRealm)
        val firstProvider = first.provide(answer, 1).awaitReady()
        val secondProvider = second.provide(answer, 2).awaitReady()

        assertEquals(1, first[answer])
        assertEquals(2, second[answer])

        firstProvider.dispose()
        secondProvider.dispose()
    }

    @Test
    fun typedKeysAndComputedPropertiesAvoidStringCasts() = runTest {
        val answer = ServiceKey<Int>("answer")
        val root = Context()
        val provider = root.provide(answer, 40).awaitReady()

        assertEquals(40, root[answer])
        root[answer] = 41
        assertEquals(41, root.require(answer))

        var suffix = "!"
        val label = ContextProperty("label", { "${require(answer)}$suffix" }) { value ->
            suffix = value.orEmpty()
            true
        }
        val property = root.property(label).awaitReady()
        assertEquals("41!", root[label])
        root[label] = "?"
        assertEquals("41?", root[label])

        property.dispose()
        provider.dispose()
    }

    @Test
    fun typedInjectionConfigParticipatesInServiceConfigResolution() = runTest {
        val configurable = ConfigurableService.Key
        val root = Context()
        val provider = root.plugin(servicePlugin<Map<String, Int>, ConfigurableService> {
            ctx, _ -> ConfigurableService(ctx)
        }, emptyMap()).await()
        var resolved: Map<String, Int>? = null

        val consumer = root.inject(
            dependencies(configurable.configured(mapOf("value" to 7))),
        ) { ctx ->
            resolved = ctx.require(configurable).resolveConfig(ctx)
        }.await()

        assertEquals(mapOf("value" to 7), resolved)
        consumer.dispose()
        provider.dispose()
    }

    @Test
    fun serviceMembersAreAssociatedWithoutRuntimeReflection() = runTest {
        val counter = ServiceKey<Counter>("counter")
        val value = counter.property("value", Counter::value) { next -> this.value = next ?: 0 }
        val root = Context()
        val provider = root.provide(counter, Counter(1)).awaitReady()
        val association = root.property(value).awaitReady()

        assertEquals(1, root[value])
        root[value] = 2
        assertEquals(2, root.require(counter).value)

        association.dispose()
        provider.dispose()
    }

    @Test
    fun callerContextIsExplicitForCallableServices() = runTest {
        val root = Context()
        val provider = root.plugin(servicePlugin<Map<String, Int>, CallableService>(name = "callable-provider") {
            ctx, base -> CallableService(ctx, base)
        }, mapOf("base" to 1)).await()
        val caller = root.intercept(CallableKey.configKey(), mapOf("intercept" to 2))

        val result = caller.use(CallableKey) { context ->
            this(context, mapOf("head" to 3))
        }

        assertEquals(mapOf("base" to 1, "intercept" to 2, "head" to 3), result)
        provider.dispose()
    }

    private class ConfigurableService(ctx: Context) : Service<Map<String, Int>>(
        ctx, Key, Key.configKey(), mergeMapConfig(),
    ) {
        companion object { val Key = ServiceKey<ConfigurableService>("configurable") }
    }
    private class CallableService(
        ctx: Context,
        private val base: Map<String, Int>,
    ) : Service<Map<String, Int>>(ctx, CallableKey, CallableKey.configKey(), mergeMapConfig()) {
        operator fun invoke(caller: Context, head: Map<String, Int> = emptyMap()): Map<String, Int> =
            resolveConfig(caller, base, head)
    }

    private data class Counter(var value: Int)

    private companion object {
        val CallableKey = ServiceKey<CallableService>("callable")
    }
}
