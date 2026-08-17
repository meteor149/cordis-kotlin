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
    override val name = "demo-theme-forest"
    override val inject: Dependencies = dependencies(TimerThemeSink.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val sink = ctx.require(TimerThemeSink.Key)
        sink.applyTheme(THEME)
        effect.collect { sink.clearTheme(THEME.id) }
    }

    override fun surfaceInfo() = PluginSurfaceInfo(
        title = "Forest JAR resource",
        body = resourceText(),
        provenance = "forest.jar · child-first resources · generation 1",
    )

    private fun resourceText(): String = javaClass.classLoader
        .getResourceAsStream("plugin-surface.txt")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: "resource missing"

    companion object {
        val THEME = TimerTheme(
            id = "theme.forest",
            name = "Tranquil Forest",
            tagline = "An isolated JAR is driving this palette",
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
