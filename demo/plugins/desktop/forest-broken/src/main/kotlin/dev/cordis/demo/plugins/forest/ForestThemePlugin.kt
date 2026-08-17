package dev.cordis.demo.plugins.forest

import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.demo.api.PluginSurface
import org.cordis.demo.api.PluginSurfaceInfo
import org.cordis.demo.api.TimerThemeSink
import org.cordis.dependencies

class ForestThemePlugin : Plugin<Unit>, PluginSurface {
    override val name = "demo-theme-forest-broken"
    override val inject: Dependencies = dependencies(TimerThemeSink.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        error("Intentional demo failure: the active forest generation must be restored")
    }

    override fun surfaceInfo() = PluginSurfaceInfo(
        title = "Broken generation",
        body = "This generation imported, then failed during Cordis apply.",
        provenance = "Rollback probe",
    )
}
