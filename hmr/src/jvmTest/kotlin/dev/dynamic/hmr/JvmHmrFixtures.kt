package dev.dynamic.hmr

import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

private const val PROBE = "cordis.jvm.hmr.integration.value"

class JvmHmrPluginV1 : Plugin<Unit> {
    override val name = "jvm-hmr-v1"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        System.setProperty(PROBE, "v1")
    }
}

class JvmHmrPluginV2 : Plugin<Unit> {
    override val name = "jvm-hmr-v2"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        System.setProperty(PROBE, "v2")
    }
}

class JvmHmrBrokenPlugin : Plugin<Unit> {
    override val name = "jvm-hmr-broken"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        System.setProperty(PROBE, "broken")
        throw IllegalStateException("fixture apply failure")
    }
}
