package org.cordis.loader

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window

/**
 * Plugin-side Activity delegate hosted by [CordisProxyActivity].
 *
 * This explicit base class is the AGP-8-compatible counterpart of Shadow's transformed
 * `android.app.Activity -> ShadowActivity` superclass. It intentionally exposes the common host
 * operations instead of pretending to be a framework Activity.
 */
open class CordisPluginActivity : ContextThemeWrapper() {
    private lateinit var host: CordisProxyActivity
    private lateinit var componentRuntime: AndroidPluginComponents
    private lateinit var module: AndroidModuleHandle
    private lateinit var className: String

    lateinit var intent: Intent
        private set

    val hostActivity: Activity get() = host
    val window: Window get() = host.window
    val layoutInflater: LayoutInflater get() = LayoutInflater.from(this)

    internal fun attach(
        host: CordisProxyActivity,
        components: AndroidPluginComponents,
        decoded: DecodedComponent,
    ) {
        this.host = host
        componentRuntime = components
        module = decoded.module
        className = decoded.className
        intent = decoded.intent
        attachBaseContext(AndroidPluginContext(host, components, decoded.module))
        decoded.module.descriptor.activities[decoded.className]
            ?.takeIf { it != 0 }
            ?.let(::setTheme)
    }

    open fun onCreate(savedInstanceState: Bundle?) = Unit
    open fun onStart() = Unit
    open fun onResume() = Unit
    open fun onPause() = Unit
    open fun onStop() = Unit
    open fun onDestroy() = Unit
    open fun onRestart() = Unit
    open fun onNewIntent(intent: Intent) = Unit
    open fun onSaveInstanceState(outState: Bundle) = Unit
    open fun onRestoreInstanceState(savedInstanceState: Bundle) = Unit
    open fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = Unit
    open fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = Unit
    open fun onConfigurationChanged(newConfig: Configuration) = Unit
    open fun onLowMemory() = Unit
    open fun onTrimMemory(level: Int) = Unit

    /** Return true to consume back; false delegates to the host Activity. */
    open fun onBackPressed(): Boolean = false

    fun setContentView(layoutResId: Int) {
        host.setContentView(layoutInflater.inflate(layoutResId, null))
    }

    fun setContentView(view: View) = host.setContentView(view)

    fun setContentView(view: View, params: ViewGroup.LayoutParams) = host.setContentView(view, params)

    fun addContentView(view: View, params: ViewGroup.LayoutParams) = host.addContentView(view, params)

    fun <T : View> findViewById(id: Int): T? = host.findViewById(id)

    fun finish() = host.finish()

    fun setResult(resultCode: Int, data: Intent? = null) = host.setResult(resultCode, data)

    fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle? = null) {
        val target = intent.explicitPluginClass(module)
        if (target != null && target in module.descriptor.activities) {
            host.startActivityForResult(
                componentRuntime.activityIntent(host, module, target, intent),
                requestCode,
                options,
            )
        } else {
            host.startActivityForResult(intent, requestCode, options)
        }
    }

    fun requestPermissions(permissions: Array<String>, requestCode: Int) =
        host.requestPermissions(permissions, requestCode)

    fun shouldShowRequestPermissionRationale(permission: String): Boolean =
        host.shouldShowRequestPermissionRationale(permission)

    fun setTitle(title: CharSequence?) = host.setTitle(title)

    fun setTitle(titleId: Int) = host.setTitle(resources.getText(titleId))

    internal fun updateIntent(value: Intent) {
        intent = value
    }
}

/** Non-exported host Activity declared by the loader AAR manifest. */
class CordisProxyActivity : Activity() {
    internal var pluginActivity: CordisPluginActivity? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val components = AndroidPluginComponents.requireInstalled()
        val decoded = components.decode(intent, ComponentKind.ACTIVITY)
        savedInstanceState?.classLoader = decoded.module.classLoader
        val delegate = decoded.module.classLoader.loadClass(decoded.className)
            .getDeclaredConstructor()
            .newInstance() as? CordisPluginActivity
            ?: error("${decoded.className} must extend ${CordisPluginActivity::class.java.name}")
        delegate.attach(this, components, decoded)
        pluginActivity = delegate
        delegate.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        pluginActivity?.onStart()
    }

    override fun onResume() {
        super.onResume()
        pluginActivity?.onResume()
    }

    override fun onPause() {
        pluginActivity?.onPause()
        super.onPause()
    }

    override fun onStop() {
        pluginActivity?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        pluginActivity?.onDestroy()
        pluginActivity = null
        super.onDestroy()
    }

    override fun onRestart() {
        super.onRestart()
        pluginActivity?.onRestart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val decoded = AndroidPluginComponents.requireInstalled().decode(intent, ComponentKind.ACTIVITY)
        val delegate = pluginActivity ?: return
        require(delegate.javaClass.name == decoded.className) {
            "onNewIntent cannot switch plugin Activity class"
        }
        delegate.updateIntent(decoded.intent)
        delegate.onNewIntent(decoded.intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pluginActivity?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.classLoader = pluginActivity?.classLoader
        super.onRestoreInstanceState(savedInstanceState)
        pluginActivity?.onRestoreInstanceState(savedInstanceState)
    }

    @Deprecated("Deprecated in the Android framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pluginActivity?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pluginActivity?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pluginActivity?.onConfigurationChanged(newConfig)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        pluginActivity?.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        pluginActivity?.onTrimMemory(level)
    }

    @Deprecated("Deprecated in the Android framework")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (pluginActivity?.onBackPressed() != true) super.onBackPressed()
    }
}
