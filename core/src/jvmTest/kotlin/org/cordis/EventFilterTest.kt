package org.cordis

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EventFilterTest {
    private val event = EventKey<Unit, Unit>("event")
    private val flag = AttributeKey<Boolean>("flag")
    private data class Session(val flag: Boolean, val key: AttributeKey<Boolean>) : EventFilter {
        override fun filter(context: Context): Boolean = context.attributes[key] == flag
    }

    private class IsolatedEmitter(ctx: Context, private val event: EventKey<Unit, Unit>) : Service<Unit>(ctx, Key) {
        fun fire() = ctx.emitEvent(this, event, Unit)

        companion object {
            val Key = ServiceKey<IsolatedEmitter>("isolated")
        }
    }

    @Test
    fun `event receiver filters listeners by their registration context`() {
        val root = Context()
        val truthy = root.extend { attributes[flag] = true }
        val falsy = root.extend { attributes[flag] = false }
        val trace = mutableListOf<String>()
        truthy.listen(event) { trace += "true" }
        falsy.listen(event) { trace += "false" }

        root.emitEvent(Session(true, flag), event, Unit)
        assertEquals(listOf("true"), trace)
    }

    @Test
    fun `service event receiver follows its isolation realm`() = runBlocking {
        val root = Context()
        val isolated = root.isolate(IsolatedEmitter.Key)
        val trace = mutableListOf<String>()
        root.listen(event) { trace += "root" }
        isolated.listen(event) { trace += "isolated" }
        lateinit var service: IsolatedEmitter
        val fiber = isolated.plugin(servicePlugin<Unit, IsolatedEmitter>(name = "emitter") { ctx, _ ->
            IsolatedEmitter(ctx, event).also { service = it }
        }, Unit).await()

        service.fire()
        assertEquals(listOf("isolated"), trace)
        fiber.dispose()
    }
}
