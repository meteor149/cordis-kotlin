package org.cordis.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.plugin
import org.cordis.dependencies
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimerParityTest {
    private suspend fun owner(block: suspend (Context) -> Unit) {
        val root = Context()
        val timer = root.plugin(TimerPlugin, Unit).await()
        val fiber = root.plugin(plugin<Unit>(name = "timer-owner", inject = dependencies(TimerService.Key)) { ctx, _ ->
            block(ctx)
        }, Unit).await()
        fiber.dispose()
        timer.dispose()
    }

    @Test
    fun `manual timeout disposal prevents callback`() = runBlocking {
        owner { ctx ->
            var calls = 0
            val timeout = ctx.timeout({ calls++ }, 40)
            timeout.dispose()
            delay(80)
            assertEquals(0, calls)
        }
    }

    @Test
    fun `suspending timeout resolves naturally`() = runBlocking {
        owner { ctx ->
            val started = System.currentTimeMillis()
            ctx.timeout(30)
            assertTrue(System.currentTimeMillis() - started >= 20)
        }
    }

    @Test
    fun `suspending timeout rejects when owner is disposed`() = runBlocking {
        val root = Context()
        val timer = root.plugin(TimerPlugin, Unit).await()
        lateinit var task: kotlinx.coroutines.Deferred<Unit>
        val fiber = root.plugin(plugin<Unit>(name = "timeout-owner", inject = dependencies(TimerService.Key)) { ctx, _ ->
            task = async { ctx.timeout(5_000) }
        }, Unit).await()
        fiber.dispose()
        assertFailsWith<CancellationException> { task.await() }
        timer.dispose()
    }

    @Test
    fun `flow interval supports bounded iteration`() = runBlocking {
        owner { ctx ->
            val ticks = ctx.interval(15).take(3).toList()
            assertEquals(3, ticks.size)
        }
    }

    @Test
    fun `flow interval closes when its owner is disposed`() = runBlocking {
        val root = Context()
        val timer = root.plugin(TimerPlugin, Unit).await()
        lateinit var task: kotlinx.coroutines.Deferred<List<Unit>>
        val fiber = root.plugin(plugin<Unit>(name = "interval-owner", inject = dependencies(TimerService.Key)) { ctx, _ ->
            task = async { ctx.interval(10).toList() }
        }, Unit).await()
        delay(30)

        fiber.dispose()

        assertFailsWith<CancellationException> { withTimeout(2_000) { task.await() } }
        timer.dispose()
    }

    @Test
    fun `collector failure closes interval and removes both owner effects`() = runBlocking {
        owner { ctx ->
            assertFailsWith<IllegalStateException> {
                ctx.interval(5).collect { error("collector") }
            }
            withTimeout(2_000) {
                while (ctx.fiber.getEffects().any { it.label.startsWith("ctx.interval") }) delay(1)
            }
        }
    }

    @Test
    fun `throttle executes leading and one trailing call`() = runBlocking {
        owner { ctx ->
            val calls = AtomicInteger()
            val throttled = ctx.throttle({ calls.incrementAndGet() }, 50)
            throttled()
            throttled()
            throttled()
            assertEquals(1, calls.get())
            delay(80)
            assertEquals(2, calls.get())
            throttled.dispose()
            assertTrue(ctx.fiber.getEffects().none { it.label == "ctx.throttle()" })
        }
    }

    @Test
    fun `no trailing throttle permits only leading call after disposal mode`() = runBlocking {
        owner { ctx ->
            val calls = AtomicInteger()
            val throttled = ctx.throttle({ calls.incrementAndGet() }, 40, noTrailing = true)
            throttled()
            throttled()
            delay(70)
            assertEquals(1, calls.get())
            throttled.dispose()
        }
    }

    @Test
    fun `debounce resets delay and disposal cancels pending call`() = runBlocking {
        owner { ctx ->
            val calls = AtomicInteger()
            val debounced = ctx.debounce({ calls.incrementAndGet() }, 40)
            debounced(); debounced(); debounced()
            withTimeout(2_000) {
                while (calls.get() < 1) delay(5)
            }
            assertEquals(1, calls.get())
            debounced(); debounced.dispose(); delay(80)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `scheduled calls preserve payload types and latest value`() = runBlocking {
        val root = Context()
        root.plugin(TimerPlugin, Unit).await()
        val values = mutableListOf<Int>()
        val owner = root.plugin(plugin<Unit>(
            name = "typed-schedule",
            inject = dependencies(TimerService.Key),
        ) { ctx, _ ->
            val debounced = ctx.debounceValue<Int>(values::add, 20)
            debounced(1)
            debounced(2)
            delay(40)
            debounced.dispose()
        }, Unit).await()

        assertEquals(listOf(2), values)
        owner.dispose()
    }
}
