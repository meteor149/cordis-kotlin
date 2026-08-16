package org.cordis.loader

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.view.LayoutInflater

/**
 * Context boundary presented to plugin components.
 *
 * Code, resources, package identity, and layout inflation come from the plugin APK. Calls targeting
 * a component declared in the module descriptor are routed back through the host proxy components.
 */
class AndroidPluginContext internal constructor(
    hostContext: Context,
    internal val components: AndroidPluginComponents,
    internal val module: AndroidModuleHandle,
) : ContextWrapper(hostContext) {
    private val resourceState = module.resources(hostContext)
    private val pluginApplicationInfo = resourceState.first
    private val pluginResources = resourceState.second
    private val inflater by lazy(LazyThreadSafetyMode.NONE) {
        LayoutInflater.from(baseContext).cloneInContext(this)
    }

    override fun getAssets(): AssetManager = pluginResources.assets

    override fun getResources(): Resources = pluginResources

    override fun getClassLoader(): ClassLoader = module.classLoader

    override fun getPackageName(): String = pluginApplicationInfo.packageName

    override fun getApplicationInfo(): ApplicationInfo = pluginApplicationInfo

    override fun getPackageCodePath(): String = module.descriptor.file.path

    override fun getPackageResourcePath(): String = module.descriptor.file.path

    override fun getSystemService(name: String): Any? =
        if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)

    override fun startActivity(intent: Intent) {
        if (!components.routeActivity(this, module, intent, null)) super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: android.os.Bundle?) {
        if (!components.routeActivity(this, module, intent, options)) super.startActivity(intent, options)
    }

    override fun startService(service: Intent): ComponentName? {
        val routed = components.routeStartService(this, module, service)
        return if (routed != null) routed.value else super.startService(service)
    }

    override fun stopService(service: Intent): Boolean =
        components.routeStopService(this, module, service) ?: super.stopService(service)

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
        components.routeBindService(this, module, service, conn, flags)
            ?: super.bindService(service, conn, flags)

    override fun unbindService(conn: ServiceConnection) {
        if (!components.unbindService(this, conn)) super.unbindService(conn)
    }
}

@Suppress("DEPRECATION")
internal fun createAndroidPluginResources(
    context: Context,
    descriptor: AndroidModuleDescriptor,
): Pair<ApplicationInfo, Resources> {
    val packageManager = context.packageManager
    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageArchiveInfo(
            descriptor.file.path,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    } else {
        packageManager.getPackageArchiveInfo(descriptor.file.path, PackageManager.GET_META_DATA)
    } ?: error("Android module ${descriptor.id} is not an APK with a readable manifest")

    val applicationInfo = requireNotNull(packageInfo.applicationInfo) {
        "Android module ${descriptor.id} does not contain application metadata"
    }.apply {
        sourceDir = descriptor.file.path
        publicSourceDir = descriptor.file.path
    }

    descriptor.packageName?.let { expected ->
        require(applicationInfo.packageName == expected) {
            "package name mismatch for Android module ${descriptor.id}: " +
                "expected $expected, got ${applicationInfo.packageName}"
        }
    }

    return applicationInfo to packageManager.getResourcesForApplication(applicationInfo)
}

internal fun Intent.explicitPluginClass(module: AndroidModuleHandle): String? {
    val component = component ?: return null
    val pluginPackage = module.descriptor.packageName
    return component.className.takeIf {
        component.packageName == pluginPackage ||
            it in module.descriptor.activities ||
            it in module.descriptor.services
    }
}
