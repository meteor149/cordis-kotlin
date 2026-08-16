package com.example.cordisfixture

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.loader.CordisPluginActivity
import org.cordis.loader.CordisPluginService

class InstrumentedFixturePlugin : Plugin<Unit> {
    override val name: String = "instrumented-fixture"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) = Unit
}

class InstrumentedFixtureActivity : CordisPluginActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val title = resources.getIdentifier("cordis_plugin_fixture_title", "string", packageName)
        setContentView(TextView(this).apply {
            id = android.R.id.text1
            text = resources.getString(title)
        })
    }
}

class InstrumentedFixtureService : CordisPluginService() {
    override fun onBind(intent: android.content.Intent): IBinder = Binder().apply {
        attachInterface(null, BINDER_DESCRIPTOR)
    }

    companion object {
        const val BINDER_DESCRIPTOR = "com.example.cordisfixture.SERVICE"
    }
}
