package org.cordis.demo

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPluginLabRuntimeTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `desktop lab exercises isolation dependencies private classpath reload and rollback`() = runBlocking {
        val runtime = DesktopPluginLabRuntime(temporary.toFile())
        runtime.start()

        assertTrue(runtime.state.value.ready)
        assertEquals("theme.forest", runtime.state.value.activePluginId)
        assertEquals("1", runtime.state.value.generation)

        runtime.activate("theme.ocean")
        runtime.openPluginSurface()
        assertTrue(assertNotNull(runtime.state.value.surface).body.contains("palette-engine/azure-2"))

        runtime.activate("theme.sunset")
        runtime.openPluginSurface()
        assertTrue(assertNotNull(runtime.state.value.surface).body.contains("private classpath JAR"))

        runtime.installNextGeneration()
        assertEquals("theme.forest", runtime.state.value.activePluginId)
        assertEquals("2", runtime.state.value.generation)
        runtime.openPluginSurface()
        assertTrue(assertNotNull(runtime.state.value.surface).body.contains("fresh URLClassLoader"))

        runtime.runRollbackProbe()
        assertEquals("2", runtime.state.value.generation)
        assertTrue(runtime.state.value.events.any { it.kind == LabEventKind.ROLLBACK })
        assertFalse(runtime.state.value.busy)

        runtime.close()
    }
}
