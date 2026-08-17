package dev.cordis.demo.plugins.sunset

import dev.cordis.demo.plugins.sunset.support.SunsetSupport
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.demo.api.PluginSurface
import org.cordis.demo.api.PluginSurfaceInfo
import org.cordis.demo.api.TimerTheme
import org.cordis.demo.api.TimerThemeSink
import org.cordis.dependencies

class SunsetThemePlugin : Plugin<Unit>, PluginSurface {
    override val name = "demo-theme-sunset"
    override val inject: Dependencies = dependencies(TimerThemeSink.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val sink = ctx.require(TimerThemeSink.Key)
        sink.applyTheme(THEME)
        effect.collect { sink.clearTheme(THEME.id) }
    }

    override fun surfaceInfo() = PluginSurfaceInfo(
        title = "Private classpath",
        body = SunsetSupport.message(),
        provenance = "sunset.jar + sunset-support.jar",
    )

    companion object {
        val THEME = TimerTheme(
            id = "theme.sunset",
            name = "Sunset Glow",
            tagline = "A private classpath JAR warms this generation",
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
