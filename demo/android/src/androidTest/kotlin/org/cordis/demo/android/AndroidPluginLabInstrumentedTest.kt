package org.cordis.demo.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cordis.demo.LabEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPluginLabInstrumentedTest {
    @Test
    fun apkDependenciesComponentsReloadAndRollbackRemainOperational() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val runtime = activity.pluginRuntime
            withTimeout(20_000) { runtime.state.first { it.ready } }

            runtime.activate("theme.ocean")
            assertEquals("theme.ocean", runtime.state.value.activePluginId)

            withContext(Dispatchers.Main) { runtime.openPluginSurface() }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            var proxyActivity: android.app.Activity? = null
            instrumentation.runOnMainSync {
                proxyActivity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it.javaClass.name == "org.cordis.loader.CordisProxyActivity" }
            }
            assertTrue("Plugin preview did not reach the proxy Activity", proxyActivity != null)
            instrumentation.runOnMainSync { proxyActivity?.finish() }
            instrumentation.waitForIdleSync()

            runtime.installNextGeneration()
            assertEquals("theme.forest", runtime.state.value.activePluginId)
            assertEquals("2", runtime.state.value.generation)

            runtime.runRollbackProbe()
            assertEquals("2", runtime.state.value.generation)
            assertTrue(runtime.state.value.events.any { it.kind == LabEventKind.ROLLBACK })
        }
    }
}
