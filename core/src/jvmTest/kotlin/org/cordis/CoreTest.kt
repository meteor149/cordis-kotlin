package org.cordis

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun `disposable list preserves identity and clears in LIFO order`() {
        val first = Any()
        val second = Any()
        val list = DisposableList<Any>()
        list.push(first)
        list.push(second)
        assertEquals(listOf(second, first), list.clear())
        assertTrue(list.isEmpty)
    }

    @Test
    fun `plugin effects load and dispose in LIFO order`() = runBlocking {
        val root = Context()
        val trace = mutableListOf<String>()
        val subject = plugin<Unit>(name = "subject") { _, _ ->
            trace += "load"
            collect { trace += "first" }
            collect { trace += "second" }
        }

        val fiber = root.plugin(subject, Unit).await()
        assertEquals(FiberState.ACTIVE, fiber.state)
        assertEquals(listOf("load"), trace)
        fiber.dispose()
        assertEquals(FiberState.DISPOSED, fiber.state)
        assertEquals(listOf("load", "second", "first"), trace)
    }

    @Test
    fun `manual effect disposal is idempotent and unregisters from fiber`() = runBlocking {
        val root = Context()
        var disposed = 0
        val handle = root.effect("test") { collect { disposed++ } }
        handle.awaitReady()
        assertEquals(listOf("test"), root.fiber.getEffects().map { it.label })
        handle.dispose()
        handle.dispose()
        assertEquals(1, disposed)
        assertTrue(root.fiber.getEffects().isEmpty())
    }

    @Test
    fun `consumer is pending until provider appears and unloads before provider`() = runBlocking {
        val root = Context()
        val clock = ServiceKey<String>("clock")
        val trace = Collections.synchronizedList(mutableListOf<String>())
        val consumer = plugin<Unit>(name = "consumer", inject = dependencies(clock)) { ctx, _ ->
            assertEquals("clock-value", ctx[clock])
            trace += "consumer-load"
            collect { trace += "consumer-unload:${ctx[clock]}" }
        }
        val consumerFiber = root.plugin(consumer, Unit)
        delay(20)
        assertEquals(FiberState.PENDING, consumerFiber.state)

        val provider = plugin<Unit>(name = "provider") { ctx, _ ->
            ctx.provide(clock, "clock-value")
            trace += "provider-load"
            collect { trace += "provider-unload" }
        }
        val providerFiber = root.plugin(provider, Unit).await()
        consumerFiber.await()
        assertEquals(FiberState.ACTIVE, consumerFiber.state)
        assertEquals("clock-value", root[clock])

        providerFiber.dispose()
        consumerFiber.await()
        assertEquals(FiberState.PENDING, consumerFiber.state)
        // The binding remains committed during dependent cleanup even though the
        // provider Fiber has already entered UNLOADING.
        assertTrue("consumer-unload:clock-value" in trace)
        assertNull(root[clock])
    }

    @Test
    fun `isolated contexts resolve independent providers and shared labels`() = runBlocking {
        val root = Context()
        val value = ServiceKey<Int>("value")
        val shared = ServiceKey<Int>("shared")
        val left = root.isolate(value)
        val right = root.isolate(value)
        val label = ServiceRealm("shared")
        val sharedA = root.isolate(shared, label)
        val sharedB = root.isolate(shared, label)

        val leftFiber = left.plugin(plugin<Unit>(name = "left") { ctx, _ -> ctx.provide(value, 1) }, Unit).await()
        val rightFiber = right.plugin(plugin<Unit>(name = "right") { ctx, _ -> ctx.provide(value, 2) }, Unit).await()
        val sharedFiber = sharedA.plugin(plugin<Unit>(name = "shared") { ctx, _ -> ctx.provide(shared, 3) }, Unit).await()

        assertEquals(1, left[value])
        assertEquals(2, right[value])
        assertEquals(3, sharedB[shared])
        assertNull(root[value])
        leftFiber.dispose(); rightFiber.dispose(); sharedFiber.dispose()
    }

    @Test
    fun `event modes preserve ordering bail and waterfall semantics`() = runBlocking {
        val root = Context()
        val trace = mutableListOf<String>()
        val event = EventKey<Unit, Unit>("event")
        root.listen(event, EventOptions(prepend = false)) { trace += "tail" }
        root.listen(event, EventOptions(prepend = true)) { trace += "head" }
        root.emitEvent(event, Unit)
        assertEquals(listOf("head", "tail"), trace)

        var once = 0
        val onceEvent = EventKey<Unit, Unit>("once")
        root.listenOnce(onceEvent) { once++ }
        root.emitEvent(onceEvent, Unit); root.emitEvent(onceEvent, Unit)
        assertEquals(1, once)

        val bail = EventKey<Unit, Any?>("bail")
        root.listen(bail) { false }
        root.listen(bail) { "done" }
        root.listen(bail) { error("unreachable") }
        assertEquals("done", root.bailEvent(bail, Unit))

        val flow = EventKey<Unit, Int>("flow")
        root.interceptEvent(flow) { _, next ->
            trace += "outer-before"
            val result = next()
            trace += "outer-after"
            result
        }
        val result = root.waterfallEvent(flow, Unit) { trace += "terminal"; 42 }
        assertEquals(42, result)
        assertEquals(listOf("outer-before", "terminal", "outer-after"), trace.takeLast(3))
    }

    @Test
    fun `parallel aggregates every listener failure`() = runBlocking {
        val root = Context()
        val event = EventKey<Unit, Unit>("parallel")
        root.listen(event) { error("one") }
        root.listen(event) { error("two") }
        val error = assertFailsWith<AggregateEventException> { root.parallelEvent(event, Unit) }
        assertEquals(2, error.causes.size)
    }

    @Test
    fun `fiber local update listeners honor once and prepend`() = runBlocking {
        val root = Context()
        var onceCalls = 0
        val oncePlugin = plugin<Int>(name = "once-update") { ctx, _ ->
            ctx.listenOnce(CoreEvents.Update) {
                onceCalls++
                null
            }
        }
        val onceFiber = root.plugin(oncePlugin, 0).await()
        onceFiber.update(1)
        onceFiber.update(2)
        assertEquals(1, onceCalls)

        val order = mutableListOf<String>()
        val orderedPlugin = plugin<Int>(name = "ordered-update") { ctx, _ ->
            ctx.interceptEvent(CoreEvents.Update) { _, next ->
                order += "tail"
                next()
            }
            ctx.interceptEvent(CoreEvents.Update, EventOptions(prepend = true)) { _, next ->
                order += "head"
                next()
            }
        }
        root.plugin(orderedPlugin, 0).await().update(1)
        assertEquals(listOf("head", "tail"), order)
    }

    @Test
    fun `registry shares runtime by plugin identity and removes the last fiber`() = runBlocking {
        val root = Context()
        val subject = plugin<Unit>(name = "shared") { _, _ -> }
        val first = root.plugin(subject, Unit).await()
        val second = root.plugin(subject, Unit).await()
        assertEquals(1, root.registry.size)
        assertEquals(2, root.registry.get(subject)?.fibers?.size)
        first.dispose()
        assertTrue(root.registry.has(subject))
        second.dispose()
        assertFalse(root.registry.has(subject))
    }

    @Test
    fun `plugin creation notification precedes parent ownership registration`() = runBlocking {
        val root = Context()
        var ownedDuringNotification = true
        root.listen(CoreEvents.Plugin) { event ->
            val fiber = event.payload
            if (fiber.uid != null && fiber.runtime?.name == "ordering") {
                ownedDuringNotification = fiber.parent.fiber.getEffects().any { it.label == "ctx.plugin()" }
            }
        }

        val fiber = root.plugin(plugin<Unit>(name = "ordering") { _, _ -> Unit }, Unit).await()
        assertFalse(ownedDuringNotification)
        assertTrue(root.fiber.getEffects().any { it.label == "ctx.plugin()" })
        fiber.dispose()
    }

    @Test
    fun `config validation and update reload a fiber`() = runBlocking {
        val root = Context()
        val seen = mutableListOf<Int>()
        val subject = plugin(
            name = "validated",
            validator = ConfigValidator<Int> { value ->
                if (value < 0) throw ValidationError("negative")
                value
            },
        ) { _, value -> seen += value }
        val fiber = root.plugin(subject, 1).await()
        fiber.update(2)
        assertEquals(listOf(1, 2), seen)
        assertFailsWith<ValidationError> { fiber.update(-1) }
        fiber.dispose()
    }

    @Test
    fun `inactive contexts reject new effects`() = runBlocking {
        val root = Context()
        lateinit var pluginContext: Context
        val fiber = root.plugin(plugin<Unit>(name = "inactive") { ctx, _ -> pluginContext = ctx }, Unit).await()
        fiber.dispose()
        val error = assertFailsWith<CordisError> { pluginContext.effect { } }
        assertEquals(CordisError.Code.INACTIVE_EFFECT, error.code)
    }

    @Test
    fun `logger keeps bounded chronological buffer and formats messages`() {
        val root = Context()
        root.logger.bufferSize = 2
        root.logger("test").info("value=%d", 1.8)
        root.logger("test").warn("second")
        root.logger("test").error("third")
        assertEquals(2, root.logger.buffer.size)
        assertEquals(listOf(LoggerType.WARN, LoggerType.ERROR), root.logger.buffer.map { it.type })
        val exporter = object : Exporter { override val colors = 0; override fun export(message: Message) = Unit }
        assertEquals("third", Logger.format(exporter, root.logger.buffer.last()))
    }

    @Test
    fun `root context identity and description align`() {
        val root = Context()
        assertSame(root, root.root)
        assertEquals("Context <root>", root.toString())
    }
}
