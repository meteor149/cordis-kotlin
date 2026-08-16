package org.cordis.timer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.plugin
import org.cordis.dependencies
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimerServiceTest {
    private suspend fun context(): Pair<Context, org.cordis.Fiber<Unit>> {
        val root = Context()
        val provider = root.plugin(TimerPlugin, Unit).await()
        return root to provider
    }

    @Test
    fun `timeout fires once and can be disposed`() = runBlocking {
        val (root, provider) = context()
        val count = AtomicInteger()
        val owner = root.plugin(plugin<Unit>(name = "owner", inject = dependencies(TimerService.Key)) { ctx, _ ->
            ctx.timeout({ count.incrementAndGet() }, 25)
        }, Unit).await()
        delay(70)
        assertEquals(1, count.get())
        owner.dispose(); provider.dispose()
    }

    @Test
    fun `interval and debounce are context owned`() = runBlocking {
        val (root, provider) = context()
        val ticks = AtomicInteger()
        lateinit var debounced: ScheduledCall<Unit>
        val owner = root.plugin(plugin<Unit>(name = "owner", inject = dependencies(TimerService.Key)) { ctx, _ ->
            ctx.interval({ ticks.incrementAndGet() }, 15)
            debounced = ctx.debounce({ ticks.addAndGet(100) }, 30)
            debounced(); debounced()
        }, Unit).await()
        withTimeout(2_000) {
            while (ticks.get() < 102) delay(5)
        }
        assertTrue(ticks.get() >= 102)
        owner.dispose()
        val stopped = ticks.get()
        delay(40)
        assertEquals(stopped, ticks.get())
        provider.dispose()
    }
}
