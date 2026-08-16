package org.cordis.timer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertTrue

class TimerJsSmokeTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun coroutineTimerRunsOnNode() = GlobalScope.promise {
        val root = Context()
        root.plugin(TimerPlugin, Unit).await()
        val fired = CompletableDeferred<Unit>()
        root.timeout({ fired.complete(Unit) }, 5)
        withTimeout(2_000) { fired.await() }
        assertTrue(fired.isCompleted)
        root.fiber.restart()
    }
}
