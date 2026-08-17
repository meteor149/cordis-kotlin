package dev.cordis.demo.plugins.palette

import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

class PalettePlugin : Plugin<Unit> {
    override val name = "demo-palette-dependency"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) = Unit
}

object PaletteEngine {
    @JvmStatic
    fun signature(): String = "palette-engine/azure-2"
}
