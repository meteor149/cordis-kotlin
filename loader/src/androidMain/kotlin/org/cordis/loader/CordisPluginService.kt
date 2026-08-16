package org.cordis.loader

import android.app.Service
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.FutureTask

/** Plugin-side Service delegate hosted by the non-exported [CordisProxyService]. */
open class CordisPluginService : ContextWrapper(null) {
    private lateinit var host: CordisProxyService
    private lateinit var components: AndroidPluginComponents
    private lateinit var module: AndroidModuleHandle
    private lateinit var className: String

    internal fun attach(
        host: CordisProxyService,
        components: AndroidPluginComponents,
        module: AndroidModuleHandle,
        className: String,
    ) {
        this.host = host
        this.components = components
        this.module = module
        this.className = className
        attachBaseContext(AndroidPluginContext(host, components, module))
    }

    open fun onCreate() = Unit
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    open fun onBind(intent: Intent): IBinder? = null
    open fun onUnbind(intent: Intent): Boolean = false
    open fun onRebind(intent: Intent) = Unit
    open fun onDestroy() = Unit
    open fun onConfigurationChanged(newConfig: Configuration) = Unit
    open fun onLowMemory() = Unit
    open fun onTrimMemory(level: Int) = Unit
    open fun onTaskRemoved(rootIntent: Intent) = Unit

    fun stopSelf(): Boolean = components.stopService(this, module, className)

    companion object {
        const val START_STICKY_COMPATIBILITY = Service.START_STICKY_COMPATIBILITY
        const val START_STICKY = Service.START_STICKY
        const val START_NOT_STICKY = Service.START_NOT_STICKY
        const val START_REDELIVER_INTENT = Service.START_REDELIVER_INTENT
    }
}

/** Non-exported host Service declared by the loader AAR manifest. */
class CordisProxyService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val records = linkedMapOf<ServiceKey, ServiceRecord>()
    private val router = CordisServiceRouterBinder(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val decoded = AndroidPluginComponents.requireInstalled().decode(intent, ComponentKind.SERVICE)
        if (intent.action == ACTION_STOP_SERVICE) {
            stop(decoded)
            return START_NOT_STICKY
        }
        val record = requireRecord(decoded)
        record.started = true
        return record.service.onStartCommand(decoded.intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder = router

    internal fun acquire(module: AndroidModuleHandle, className: String, intent: Intent): IBinder? = onMain {
        val decoded = DecodedComponent(module, className, intent)
        val record = requireRecord(decoded)
        if (record.bindings == 0 && record.wantsRebind) {
            record.service.onRebind(intent)
            record.wantsRebind = false
        }
        record.bindings += 1
        if (!record.boundOnce) {
            record.binder = record.service.onBind(intent)
            record.boundOnce = true
        }
        record.binder
    }

    internal fun release(module: AndroidModuleHandle, className: String, intent: Intent) = onMain {
        val key = ServiceKey(module, className)
        val record = records[key] ?: return@onMain
        if (record.bindings > 0) record.bindings -= 1
        if (record.bindings == 0) {
            record.wantsRebind = record.service.onUnbind(intent)
            if (!record.started) destroy(key, record)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        records.values.toList().forEach { it.service.onConfigurationChanged(newConfig) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        records.values.toList().forEach { it.service.onLowMemory() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        records.values.toList().forEach { it.service.onTrimMemory(level) }
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        records.values.toList().forEach { it.service.onTaskRemoved(rootIntent) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        records.toList().forEach { (key, record) -> destroy(key, record) }
        super.onDestroy()
    }

    private fun requireRecord(decoded: DecodedComponent): ServiceRecord {
        val key = ServiceKey(decoded.module, decoded.className)
        return records.getOrPut(key) {
            val service = decoded.module.classLoader.loadClass(decoded.className)
                .getDeclaredConstructor()
                .newInstance() as? CordisPluginService
                ?: error("${decoded.className} must extend ${CordisPluginService::class.java.name}")
            service.attach(this, AndroidPluginComponents.requireInstalled(), decoded.module, decoded.className)
            service.onCreate()
            ServiceRecord(service)
        }
    }

    private fun stop(decoded: DecodedComponent) {
        val key = ServiceKey(decoded.module, decoded.className)
        val record = records[key] ?: return
        record.started = false
        if (record.bindings == 0) destroy(key, record)
        if (records.isEmpty()) stopSelf()
    }

    private fun destroy(key: ServiceKey, record: ServiceRecord) {
        if (records.remove(key) != null) record.service.onDestroy()
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        mainHandler.post(task)
        return task.get()
    }
}

internal class CordisServiceRouterBinder(
    private val service: CordisProxyService,
) : Binder() {
    fun acquire(module: AndroidModuleHandle, className: String, intent: Intent): IBinder? =
        service.acquire(module, className, intent)

    fun release(module: AndroidModuleHandle, className: String, intent: Intent) =
        service.release(module, className, intent)
}

private class ServiceKey(
    private val module: AndroidModuleHandle,
    private val className: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ServiceKey && module === other.module && className == other.className

    override fun hashCode(): Int = 31 * System.identityHashCode(module) + className.hashCode()
}

private data class ServiceRecord(
    val service: CordisPluginService,
    var binder: IBinder? = null,
    var started: Boolean = false,
    var bindings: Int = 0,
    var boundOnce: Boolean = false,
    var wantsRebind: Boolean = false,
)
