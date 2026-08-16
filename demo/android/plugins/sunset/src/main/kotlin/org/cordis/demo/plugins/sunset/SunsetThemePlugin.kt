package dev.cordis.demo.plugins.sunset

import android.os.Bundle
import android.widget.TextView
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.demo.api.TimerTheme
import org.cordis.demo.api.TimerThemeSink
import org.cordis.dependencies
import org.cordis.loader.CordisPluginActivity

class SunsetThemePlugin : Plugin<Unit> {
    override val name = "demo-theme-sunset"
    override val inject: Dependencies = dependencies(TimerThemeSink.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val sink = ctx.require(TimerThemeSink.Key)
        sink.applyTheme(THEME)
        effect.collect { sink.clearTheme(THEME.id) }
    }

    companion object {
        val THEME = TimerTheme(
            id = "theme.sunset",
            name = "Sunset Glow",
            tagline = "Ease a tense day into a warm, slower rhythm",
            symbol = "☼",
            backgroundTop = 0xFFFFD3B0.toInt(),
            backgroundBottom = 0xFFF47768.toInt(),
            surface = 0xFFFFE6D4.toInt(),
            surfaceStrong = 0xFFFFF3E9.toInt(),
            primaryText = 0xFF40211F.toInt(),
            secondaryText = 0xFF805652.toInt(),
            accent = 0xFFED624F.toInt(),
            accentSoft = 0xFFFFC6B8.toInt(),
            buttonText = 0xFFFFFFFF.toInt(),
            isLightStatusBar = true,
        )
    }
}

class SunsetPreviewActivity : CordisPluginActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_preview)
        findViewById<TextView>(R.id.close)?.setOnClickListener { finish() }
    }
}
