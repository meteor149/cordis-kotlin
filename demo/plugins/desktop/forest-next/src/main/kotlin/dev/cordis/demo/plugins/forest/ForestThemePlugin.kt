package dev.cordis.demo.plugins.forest

import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.demo.api.PluginSurface
import org.cordis.demo.api.PluginSurfaceInfo
import org.cordis.demo.api.TimerTheme
import org.cordis.demo.api.TimerThemeSink
import org.cordis.dependencies

class ForestThemePlugin : Plugin<Unit>, PluginSurface {
    override val name = "demo-theme-forest-next"
    override val inject: Dependencies = dependencies(TimerThemeSink.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val sink = ctx.require(TimerThemeSink.Key)
        sink.applyTheme(THEME)
        effect.collect { sink.clearTheme(THEME.id) }
    }

    override fun surfaceInfo() = PluginSurfaceInfo(
        title = "Forest generation 2",
        body = javaClass.classLoader.getResourceAsStream("plugin-surface.txt")
            ?.bufferedReader()?.use { it.readText() } ?: "resource missing",
        provenance = "Transactional JAR replacement · generation 2",
    )

    companion object {
        val THEME = TimerTheme(
            id = "theme.forest",
            name = "Forest After Rain",
            tagline = "Generation two arrived without losing the timer",
            symbol = "✧",
            backgroundTop = 0xFF154A42.toInt(),
            backgroundBottom = 0xFF071E20.toInt(),
            surface = 0xFF0E302E.toInt(),
            surfaceStrong = 0xFF1C5950.toInt(),
            primaryText = 0xFFF3FFF9.toInt(),
            secondaryText = 0xFFA7CCBC.toInt(),
            accent = 0xFF70E6D1.toInt(),
            accentSoft = 0xFF28655C.toInt(),
            buttonText = 0xFF082017.toInt(),
            isLightStatusBar = false,
        )
    }
}
