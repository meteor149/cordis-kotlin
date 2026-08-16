package org.cordis

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LoggerBindingTest {
    @Test
    fun `logger view uses calling fiber name while sharing exporter backend`() = runBlocking {
        val root = Context()
        val subject = plugin<Unit>(name = "MyFeature") { ctx, _ ->
            ctx.logger().info("inside")
        }
        val fiber = root.plugin(subject, Unit).await()
        val message = root.logger.buffer.last { it.args.firstOrNull() == "inside" }
        val expectedFiber = FiberLogMeta(fiber.uid, "MyFeature")
        assertEquals("my-feature", message.name)
        assertEquals(expectedFiber, message.fiber)
        assertSame(root.logger.buffer, root.extend().logger.buffer)
        fiber.dispose()
        assertEquals(expectedFiber, message.fiber)
    }

    @Test
    fun `logger intercept follows derived context lineage`() {
        val root = Context()
        val child = root.intercept(LoggerService.Intercept, LoggerConfig(name = "intercepted", level = 3))
        child.logger().debug("visible")
        val message = root.logger.buffer.last()
        assertEquals("intercepted", message.name)
        assertEquals(LoggerType.DEBUG, message.type)
    }

    @Test
    fun `logger threshold and exporter overrides follow upstream precedence`() {
        val root = Context()
        val captured = mutableListOf<Message>()
        val exporter = object : Exporter {
            override fun export(message: Message) { captured += message }
        }
        root.logger.exporter(root, exporter)

        val logger = root.logger("threshold")
        logger.level = LoggerLevel.WARN.value
        logger.info("hidden")
        logger.warn("visible")
        assertEquals(listOf("visible"), captured.map { it.args.single() })

        val overridingExporter = object : Exporter {
            override val levels = mapOf("threshold" to LoggerLevel.DEBUG.value)
            override fun export(message: Message) { captured += message }
        }
        root.logger.exporter(root, overridingExporter)
        logger.debug("override")
        assertEquals("override", captured.last().args.single())
    }

    @Test
    fun `logger emits cause chain before its wrapping error`() {
        val root = Context()
        val captured = mutableListOf<Message>()
        root.logger.exporter(root, object : Exporter {
            override fun export(message: Message) { captured += message }
        })
        val cause = IllegalArgumentException("cause")
        val wrapper = IllegalStateException("wrapper", cause)

        root.logger("errors").warn(wrapper)

        assertEquals(listOf(cause, wrapper), captured.map { it.args.single() })
    }

    @Test
    fun `exporter registered by child fiber is disposed with that fiber`() = runBlocking {
        val root = Context()
        var exported = 0
        val exporter = object : Exporter {
            override val levels = mapOf("default" to LoggerLevel.DEBUG.value)
            override fun export(message: Message) { exported++ }
        }
        val owner = root.plugin(plugin<Unit>(name = "exporter-owner") { ctx, _ ->
            ctx.logger.exporter(ctx, exporter)
        }, Unit).await()
        root.logger("test").info("before")
        assertEquals(1, exported)
        owner.dispose()
        root.logger("test").info("after")
        assertEquals(1, exported)
    }
}
