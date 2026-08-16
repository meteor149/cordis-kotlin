package org.cordis.logger.console

import org.cordis.Context
import org.cordis.LoggerType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleExporterTest {
    @Test
    fun `renders object custom formatter diff and multiline labels`() {
        val root = Context()
        val exporter = ConsoleExporter(root, ConsoleExporterConfig(colors = 0, showDiff = true, showTime = ""))
        val base = System.currentTimeMillis()
        exporter.timestamp = base
        val objectMessage = org.cordis.Message(1, base + 2, "test", LoggerType.INFO, 2, listOf(mapOf("foo" to "bar")))
        assertEquals("[I] test { foo: 'bar' } +2ms", exporter.render(objectMessage))

        exporter.formatters['x'] = org.cordis.Formatter { _, _, _ -> "custom" }
        val custom = objectMessage.copy(sn = 2, ts = base + 3, args = listOf("%x%%x"))
        assertEquals("[I] test custom%x +1ms", exporter.render(custom))

        exporter.label = LabelStyle(width = 10, margin = 2, align = LabelStyle.Align.RIGHT)
        val multiline = custom.copy(sn = 3, args = listOf("message\nmessage"))
        assertTrue(exporter.render(multiline).startsWith("      test  [I]  message\n"))
    }
}
