package dev.cordis.demo.plugins.forest

import android.os.Bundle
import android.widget.TextView
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.demo.api.TimerTheme
import org.cordis.demo.api.TimerThemeSink
import org.cordis.demo.api.PluginSurface
import org.cordis.demo.api.PluginSurfaceInfo
import org.cordis.dependencies
import org.cordis.loader.CordisPluginActivity

class ForestThemePlugin : Plugin<Unit>, PluginSurface {
    override val name = "demo-theme-forest"
    override val inject: Dependencies = dependencies(TimerThemeSink.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        error("Intentional demo failure after import: rollback must restore the previous forest generation")
    }

    override fun surfaceInfo() = PluginSurfaceInfo(
        title = "Broken generation",
        body = "This surface must never become active.",
        provenance = "Rollback probe",
    )

    companion object {
        val THEME = TimerTheme(
            id = "theme.forest",
            name = "Tranquil Forest",
            tagline = "Let every minute grow quietly like a tree",
            symbol = "✦",
            backgroundTop = 0xFF173F32.toInt(),
            backgroundBottom = 0xFF071712.toInt(),
            surface = 0xFF102820.toInt(),
            surfaceStrong = 0xFF215040.toInt(),
            primaryText = 0xFFF3FFF9.toInt(),
            secondaryText = 0xFFA7CCBC.toInt(),
            accent = 0xFF63E2B1.toInt(),
            accentSoft = 0xFF285847.toInt(),
            buttonText = 0xFF082017.toInt(),
            isLightStatusBar = false,
        )
    }
}

class ForestPreviewActivity : CordisPluginActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_preview)
        findViewById<TextView>(R.id.close)?.setOnClickListener { finish() }
    }
}
