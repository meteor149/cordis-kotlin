package org.cordis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ServiceLifecycleTest {
    private class ConfigService(ctx: Context) : Service<Map<String, Int>>(
        ctx, ConfigKey, ConfigKey.configKey(), mergeMapConfig(),
    ) {
        fun resolved(base: Map<String, Int>, head: Map<String, Int>) = resolveConfig(base, head)
    }

    private class DelayedService(
        ctx: Context,
        private val gate: CompletableDeferred<Unit>,
        private val trace: MutableList<String>,
    ) : Service<Unit>(ctx, DelayedKey) {
        override suspend fun initialize(): Disposable {
            trace += "init:start"
            gate.await()
            trace += "init:end"
            return Disposable { trace += "stop" }
        }
    }

    @Test
    fun `service init blocks injection and returned disposer follows fiber`() = runBlocking {
        val root = Context()
        val gate = CompletableDeferred<Unit>()
        val trace = mutableListOf<String>()
        val consumer = root.inject(dependencies(DelayedKey)) { ctx ->
            trace += "consumer:${ctx[DelayedKey] != null}"
            collect { trace += "consumer:stop" }
        }
        val provider = root.plugin(servicePlugin<Unit, DelayedService>(name = "delayed-service") { ctx, _ ->
            DelayedService(ctx, gate, trace)
        }, Unit)

        withTimeout(5_000) {
            while (trace.isEmpty()) delay(1)
        }
        assertEquals(FiberState.LOADING, provider.state)
        assertEquals(FiberState.PENDING, consumer.state)
        assertEquals(listOf("init:start"), trace)

        gate.complete(Unit)
        provider.await()
        consumer.await()
        assertEquals(listOf("init:start", "init:end", "consumer:true"), trace)

        provider.dispose()
        consumer.await()
        assertEquals(
            // The initialize disposer was collected after ctx.provide(), so
            // normal Fiber LIFO runs it before the provider effect notifies
            // and unloads consumers.
            listOf("init:start", "init:end", "consumer:true", "stop", "consumer:stop"),
            trace,
        )
        assertEquals(FiberState.PENDING, consumer.state)
        consumer.dispose()
    }

    @Test
    fun `service config shallow merges base intercept lineage and head`() {
        val root = Context()
        val context = root
            .intercept(ConfigKey.configKey(), mapOf("shared" to 1, "outer" to 1))
            .intercept(ConfigKey.configKey(), mapOf("shared" to 2, "inner" to 2))
        val service = ConfigService(context)

        assertEquals(
            mapOf("base" to 0, "shared" to 3, "outer" to 1, "inner" to 2, "head" to 3),
            service.resolved(mapOf("base" to 0, "shared" to 0), mapOf("head" to 3, "shared" to 3)),
        )
    }

    private companion object {
        val ConfigKey = ServiceKey<ConfigService>("config")
        val DelayedKey = ServiceKey<DelayedService>("delayed")
    }
}
