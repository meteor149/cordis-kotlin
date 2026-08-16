package org.cordis.loader

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.io.Closeable
import java.util.IdentityHashMap

/**
 * Process-local Activity/Service router for active [AndroidModuleLoader] generations.
 *
 * Install once from the host Application before launching plugin components. The proxy components
 * are merged into the host manifest by the loader AAR and are never exported.
 */
class AndroidPluginComponents private constructor(
    private val loader: AndroidModuleLoader,
) : Closeable {
    private val connectionLock = Any()
    private val connections = IdentityHashMap<ServiceConnection, PluginServiceConnection>()

    companion object {
        @Volatile
        private var installed: AndroidPluginComponents? = null

        /** Installs or replaces the process-wide component router. */
        fun install(loader: AndroidModuleLoader): AndroidPluginComponents =
            AndroidPluginComponents(loader).also { installed = it }

        internal fun requireInstalled(): AndroidPluginComponents =
            installed ?: error("AndroidPluginComponents.install(loader) must be called first")
    }

    /** Creates an explicit host proxy Intent for an active plugin Activity. */
    fun activityIntent(
        context: Context,
        moduleId: String,
        activityClass: String,
        pluginIntent: Intent = Intent(),
    ): Intent = activityIntent(
        context,
        requireComponent(moduleId, activityClass, ComponentKind.ACTIVITY).module,
        activityClass,
        pluginIntent,
    )

    fun startActivity(
        context: Context,
        moduleId: String,
        activityClass: String,
        pluginIntent: Intent = Intent(),
        options: Bundle? = null,
    ) {
        startActivity(
            context,
            requireComponent(moduleId, activityClass, ComponentKind.ACTIVITY).module,
            activityClass,
            pluginIntent,
            options,
        )
    }

    /** Creates an explicit host proxy Intent for an active plugin Service. */
    fun serviceIntent(
        context: Context,
        moduleId: String,
        serviceClass: String,
        pluginIntent: Intent = Intent(),
    ): Intent = serviceIntent(
        context,
        requireComponent(moduleId, serviceClass, ComponentKind.SERVICE).module,
        serviceClass,
        pluginIntent,
    )

    fun startService(
        context: Context,
        moduleId: String,
        serviceClass: String,
        pluginIntent: Intent = Intent(),
    ): ComponentName? = startService(
        context,
        requireComponent(moduleId, serviceClass, ComponentKind.SERVICE).module,
        serviceClass,
        pluginIntent,
    )

    /** Stops one logical plugin Service without tearing down other services sharing the proxy. */
    fun stopService(
        context: Context,
        moduleId: String,
        serviceClass: String,
        pluginIntent: Intent = Intent(),
    ): Boolean {
        return stopService(
            context,
            requireComponent(moduleId, serviceClass, ComponentKind.SERVICE).module,
            serviceClass,
            pluginIntent,
        )
    }

    /**
     * Binds to a logical plugin Service. The supplied connection receives the plugin's binder, not
     * the proxy router binder. Plugin services are in-process by design.
     */
    fun bindService(
        context: Context,
        moduleId: String,
        serviceClass: String,
        pluginIntent: Intent = Intent(),
        connection: ServiceConnection,
        flags: Int,
    ): Boolean {
        val route = requireComponent(moduleId, serviceClass, ComponentKind.SERVICE)
        return bindService(context, route, pluginIntent, connection, flags)
    }

    internal fun activityIntent(
        context: Context,
        module: AndroidModuleHandle,
        activityClass: String,
        pluginIntent: Intent,
    ): Intent = proxyIntent(
        context,
        declaredComponent(module, activityClass, ComponentKind.ACTIVITY),
        pluginIntent,
    )

    internal fun startActivity(
        context: Context,
        module: AndroidModuleHandle,
        activityClass: String,
        pluginIntent: Intent,
        options: Bundle?,
    ) {
        val proxyIntent = activityIntent(context, module, activityClass, pluginIntent)
        if (context !is Activity) proxyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(proxyIntent, options)
    }

    private fun serviceIntent(
        context: Context,
        module: AndroidModuleHandle,
        serviceClass: String,
        pluginIntent: Intent,
    ): Intent = proxyIntent(
        context,
        declaredComponent(module, serviceClass, ComponentKind.SERVICE),
        pluginIntent,
    )

    private fun startService(
        context: Context,
        module: AndroidModuleHandle,
        serviceClass: String,
        pluginIntent: Intent,
    ): ComponentName? = context.startService(serviceIntent(context, module, serviceClass, pluginIntent))

    internal fun stopService(
        context: Context,
        module: AndroidModuleHandle,
        serviceClass: String,
        pluginIntent: Intent = Intent(),
    ): Boolean {
        val intent = serviceIntent(context, module, serviceClass, pluginIntent)
            .setAction(ACTION_STOP_SERVICE)
        return context.startService(intent) != null
    }

    private fun bindService(
        context: Context,
        route: ComponentRoute,
        pluginIntent: Intent,
        connection: ServiceConnection,
        flags: Int,
    ): Boolean {
        val proxyIntent = proxyIntent(context, route, pluginIntent)
        val wrapper = PluginServiceConnection(route, pluginIntent, connection)
        synchronized(connectionLock) {
            require(connections[connection] == null) { "ServiceConnection is already bound" }
            connections[connection] = wrapper
        }
        return runCatching { context.bindService(proxyIntent, wrapper, flags) }
            .onFailure { synchronized(connectionLock) { connections.remove(connection) } }
            .getOrThrow()
            .also { bound ->
                if (!bound) synchronized(connectionLock) { connections.remove(connection) }
            }
    }

    fun unbindService(context: Context, connection: ServiceConnection): Boolean {
        val wrapper = synchronized(connectionLock) { connections.remove(connection) } ?: return false
        wrapper.release()
        context.unbindService(wrapper)
        return true
    }

    internal fun routeActivity(context: Context, module: AndroidModuleHandle, intent: Intent, options: Bundle?): Boolean {
        val className = intent.explicitPluginClass(module) ?: return false
        if (className !in module.descriptor.activities) return false
        startActivity(context, module, className, intent, options)
        return true
    }

    internal fun routeStartService(
        context: Context,
        module: AndroidModuleHandle,
        intent: Intent,
    ): RoutedValue<ComponentName?>? {
        val className = intent.explicitPluginClass(module) ?: return null
        if (className !in module.descriptor.services) return null
        return RoutedValue(startService(context, module, className, intent))
    }

    internal fun routeStopService(context: Context, module: AndroidModuleHandle, intent: Intent): Boolean? {
        val className = intent.explicitPluginClass(module) ?: return null
        if (className !in module.descriptor.services) return null
        return stopService(context, module, className, intent)
    }

    internal fun routeBindService(
        context: Context,
        module: AndroidModuleHandle,
        intent: Intent,
        connection: ServiceConnection,
        flags: Int,
    ): Boolean? {
        val className = intent.explicitPluginClass(module) ?: return null
        if (className !in module.descriptor.services) return null
        return bindService(
            context,
            declaredComponent(module, className, ComponentKind.SERVICE),
            intent,
            connection,
            flags,
        )
    }

    internal fun decode(proxyIntent: Intent, expectedKind: ComponentKind): DecodedComponent {
        val uri = requireNotNull(proxyIntent.data) { "missing Cordis plugin component route" }
        require(uri.scheme == ROUTE_SCHEME && uri.host == expectedKind.routeName) {
            "invalid Cordis plugin component route: $uri"
        }
        val segments = uri.pathSegments
        require(segments.size == 2) { "invalid Cordis plugin component route: $uri" }
        val moduleId = segments[0]
        val className = segments[1]
        val generation = requireNotNull(uri.getQueryParameter(QUERY_GENERATION)?.toLongOrNull()) {
            "missing plugin generation in $uri"
        }
        val version = requireNotNull(uri.getQueryParameter(QUERY_VERSION)) { "missing plugin version in $uri" }
        val module = loader.moduleGeneration(generation)
            ?: error("Android plugin generation $generation has been released")
        require(module.descriptor.id == moduleId) { "plugin module id changed for generation $generation" }
        val route = declaredComponent(module, className, expectedKind)
        require(route.module.descriptor.version == version) {
            "plugin generation changed before component launch: $moduleId/$version"
        }

        proxyIntent.setExtrasClassLoader(route.module.classLoader)
        val original = if (Build.VERSION.SDK_INT >= 33) {
            proxyIntent.getParcelableExtra(EXTRA_PLUGIN_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            proxyIntent.getParcelableExtra(EXTRA_PLUGIN_INTENT)
        } ?: Intent()
        original.setExtrasClassLoader(route.module.classLoader)
        return DecodedComponent(route.module, className, original)
    }

    override fun close() {
        if (installed === this) installed = null
    }

    private fun requireComponent(moduleId: String, className: String, kind: ComponentKind): ComponentRoute {
        val module = loader.activeModule(moduleId)
            ?: error("Android module $moduleId is not active; import it before launching components")
        return declaredComponent(module, className, kind)
    }

    private fun declaredComponent(
        module: AndroidModuleHandle,
        className: String,
        kind: ComponentKind,
    ): ComponentRoute {
        val allowed = when (kind) {
            ComponentKind.ACTIVITY -> className in module.descriptor.activities
            ComponentKind.SERVICE -> className in module.descriptor.services
        }
        require(allowed) {
            "$className is not a declared plugin ${kind.displayName} in module ${module.descriptor.id}"
        }
        return ComponentRoute(module, className, kind)
    }

    private fun proxyIntent(context: Context, route: ComponentRoute, original: Intent): Intent {
        val proxyClass = when (route.kind) {
            ComponentKind.ACTIVITY -> CordisProxyActivity::class.java
            ComponentKind.SERVICE -> CordisProxyService::class.java
        }
        val uri = Uri.Builder()
            .scheme(ROUTE_SCHEME)
            .authority(route.kind.routeName)
            .appendPath(route.module.descriptor.id)
            .appendPath(route.className)
            .appendQueryParameter(QUERY_GENERATION, route.module.generation.toString())
            .appendQueryParameter(QUERY_VERSION, route.module.descriptor.version)
            .build()
        return Intent(context, proxyClass)
            .setData(uri)
            .putExtra(EXTRA_PLUGIN_INTENT, Intent(original))
    }

    private inner class PluginServiceConnection(
        private val route: ComponentRoute,
        private val intent: Intent,
        private val delegate: ServiceConnection,
    ) : ServiceConnection {
        private var router: CordisServiceRouterBinder? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val serviceRouter = service as? CordisServiceRouterBinder
                ?: error("CordisProxyService must run in the host process")
            router = serviceRouter
            val pluginBinder = serviceRouter.acquire(route.module, route.className, intent)
            val componentName = pluginComponentName(route.module, route.className)
            if (pluginBinder != null) {
                delegate.onServiceConnected(componentName, pluginBinder)
            } else if (Build.VERSION.SDK_INT >= 28) {
                delegate.onNullBinding(componentName)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            router = null
            delegate.onServiceDisconnected(pluginComponentName(route.module, route.className))
        }

        fun release() {
            router?.release(route.module, route.className, intent)
            router = null
        }
    }
}

internal enum class ComponentKind(val routeName: String, val displayName: String) {
    ACTIVITY("activity", "Activity"),
    SERVICE("service", "Service"),
}

internal data class DecodedComponent(
    val module: AndroidModuleHandle,
    val className: String,
    val intent: Intent,
)

internal data class RoutedValue<T>(val value: T)

private data class ComponentRoute(
    val module: AndroidModuleHandle,
    val className: String,
    val kind: ComponentKind,
)

internal fun pluginComponentName(module: AndroidModuleHandle, className: String): ComponentName {
    val packageName = requireNotNull(module.descriptor.packageName) {
        "component-enabled module ${module.descriptor.id} must declare packageName"
    }
    return ComponentName(packageName, className)
}

internal const val ACTION_STOP_SERVICE = "org.cordis.loader.action.STOP_PLUGIN_SERVICE"
private const val ROUTE_SCHEME = "cordis-plugin"
private const val QUERY_VERSION = "version"
private const val QUERY_GENERATION = "generation"
private const val EXTRA_PLUGIN_INTENT = "org.cordis.loader.extra.PLUGIN_INTENT"
