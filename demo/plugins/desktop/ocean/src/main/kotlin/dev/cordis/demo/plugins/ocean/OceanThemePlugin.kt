package dev.cordis.demo.plugins.ocean

import dev.cordis.demo.plugins.palette.PaletteEngine
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.demo.api.PluginSurface
import org.cordis.demo.api.PluginSurfaceInfo
import org.cordis.demo.api.TimerTheme
import org.cordis.demo.api.TimerThemeSink
import org.cordis.dependencies

class OceanThemePlugin : Plugin<Unit>, PluginSurface {
    override val name = "demo-theme-ocean"
    override val inject: Dependencies = dependencies(TimerThemeSink.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val sink = ctx.require(TimerThemeSink.Key)
        sink.applyTheme(THEME)
        effect.collect { sink.clearTheme(THEME.id) }
    }

    override fun surfaceInfo() = PluginSurfaceInfo(
        title = "Declared module dependency",
        body = "Ocean resolved ${PaletteEngine.signature()} without seeing host implementation classes.",
        provenance = "ocean.jar → jvm-plugin:palette",
    )

    companion object {
        val THEME = TimerTheme(
            id = "theme.ocean",
            name = "Deep Ocean",
            tagline = "A dependency class loader supplies this blue frequency",
            symbol = "◉",
            backgroundTop = 0xFF092A46.toInt(),
            backgroundBottom = 0xFF030C18.toInt(),
            surface = 0xFF0A1D30.toInt(),
            surfaceStrong = 0xFF123B5D.toInt(),
            primaryText = 0xFFF2FAFF.toInt(),
            secondaryText = 0xFF9ABED5.toInt(),
            accent = 0xFF53C8FF.toInt(),
            accentSoft = 0xFF164A69.toInt(),
            buttonText = 0xFF031522.toInt(),
            isLightStatusBar = false,
        )
    }
}
