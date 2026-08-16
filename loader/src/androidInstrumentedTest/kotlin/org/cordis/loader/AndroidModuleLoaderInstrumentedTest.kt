package org.cordis.loader

import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cordisfixture.InstrumentedFixtureActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.cordis.Plugin
import com.example.cordisfixture.InstrumentedFixturePlugin
import com.example.cordisfixture.InstrumentedFixtureService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidModuleLoaderInstrumentedTest {
    @Test
    fun testLoadsAndTransactionallyReplacesAnIsolatedPlugin() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val targetContext = instrumentation.targetContext
            val testApk = File(instrumentation.context.applicationInfo.sourceDir)
            val pluginApk = File(targetContext.filesDir, "cordis-test/plugin.apk").apply {
                requireNotNull(parentFile).mkdirs()
                testApk.copyTo(this, overwrite = true)
            }
            val loader = AndroidModuleLoader(targetContext)
            val pluginPackage = requireNotNull(
                targetContext.packageManager.getPackageArchiveInfo(pluginApk.path, 0)?.packageName,
            )
            val descriptor = AndroidModuleDescriptor(
                id = "instrumented.fixture",
                version = "1",
                entryClass = InstrumentedFixturePlugin::class.java.name,
                file = pluginApk,
                expectedSha256 = androidModuleSha256(pluginApk),
                packageName = pluginPackage,
                activities = mapOf(InstrumentedFixtureActivity::class.java.name to 0),
                services = setOf(InstrumentedFixtureService::class.java.name),
            )
            loader.register(descriptor)

            val url = loader.resolve("android-plugin:instrumented.fixture", null).url
            val original = requireNotNull(loader.import(url, null))
            assertTrue(original is Plugin<*>)
            assertEquals(InstrumentedFixturePlugin::class.java.name, original.javaClass.name)
            assertNotSame(InstrumentedFixturePlugin::class.java.classLoader, original.javaClass.classLoader)
            assertSame(original, loader.peek(url))
            assertTrue(descriptor.file.canonicalFile.toURI().toString() in loader.linked(url))
            val components = AndroidPluginComponents.install(loader)
            val oldGenerationActivity = components.activityIntent(
                targetContext,
                descriptor.id,
                InstrumentedFixtureActivity::class.java.name,
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            val rollback = loader.beginReload(setOf(url))
            val rolledBack = rollback.import(url)
            assertNotSame(original, rolledBack)
            rollback.rollback()
            assertSame(original, loader.peek(url))

            val replacement = loader.beginReload(setOf(url))
            val next = replacement.import(url)
            assertNotSame(original, next)
            assertSame(original, loader.peek(url))
            replacement.commit()
            assertSame(next, loader.peek(url))

            val oldMonitor = instrumentation.addMonitor(CordisProxyActivity::class.java.name, null, false)
            targetContext.startActivity(oldGenerationActivity)
            val oldProxyActivity = oldMonitor.waitForActivityWithTimeout(5_000) as? CordisProxyActivity
            assertNotNull(oldProxyActivity)
            instrumentation.runOnMainSync {
                assertSame(original.javaClass.classLoader, oldProxyActivity!!.pluginActivity!!.javaClass.classLoader)
                oldProxyActivity.finish()
            }
            instrumentation.removeMonitor(oldMonitor)

            val monitor = instrumentation.addMonitor(CordisProxyActivity::class.java.name, null, false)
            components.startActivity(
                targetContext,
                descriptor.id,
                InstrumentedFixtureActivity::class.java.name,
            )
            val proxyActivity = monitor.waitForActivityWithTimeout(5_000) as? CordisProxyActivity
            assertNotNull(proxyActivity)
            instrumentation.runOnMainSync {
                assertEquals(
                    "Cordis plugin resources",
                    proxyActivity!!.findViewById<TextView>(android.R.id.text1).text.toString(),
                )
                assertNotSame(
                    original.javaClass.classLoader,
                    proxyActivity.pluginActivity!!.javaClass.classLoader,
                )
                assertSame(next!!.javaClass.classLoader, proxyActivity.pluginActivity!!.javaClass.classLoader)
                proxyActivity.finish()
            }
            instrumentation.removeMonitor(monitor)

            val connected = CountDownLatch(1)
            var serviceBinder: IBinder? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName, service: IBinder) {
                    serviceBinder = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
            }
            assertTrue(
                components.bindService(
                    targetContext,
                    descriptor.id,
                    InstrumentedFixtureService::class.java.name,
                    connection = connection,
                    flags = Context.BIND_AUTO_CREATE,
                ),
            )
            assertTrue("plugin Service did not bind", connected.await(5, TimeUnit.SECONDS))
            assertEquals(InstrumentedFixtureService.BINDER_DESCRIPTOR, serviceBinder!!.interfaceDescriptor)
            components.unbindService(targetContext, connection)
            components.close()

            loader.release(descriptor.id)
            loader.unregister(descriptor.id)
            pluginApk.delete()
        }
    }
}
