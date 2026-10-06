package org.cordis.loader

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.cordis.Context
import org.cordis.ConfigValidator
import org.cordis.Plugin
import org.cordis.EffectScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JsonConfigurationTest {
    @Test
    fun typedJsonRemainsLiteralAndReachesValidatorWithoutMapCoercion(): Unit = runBlocking {
        val ctx = Context()
        val expected = Json.parseToJsonElement("""{"nested":[{"__jsExpr":"ctx.unavailable"}],"text":"\u0024{literal}"}""")
        var received: JsonObject? = null
        val plugin = object : Plugin<JsonObject> {
            override val config = ConfigValidator<JsonObject> { it }
            override suspend fun apply(ctx: Context, config: JsonObject, effect: EffectScope) { received = config }
        }
        val loaderFiber = ctx.plugin(LoaderPlugin, LoaderConfig()).await()
        val loader = ctx.require(Loader.Key)
        try {
            loader.builtins["json"] = plugin
            loader.root.update(listOf(EntryOptions("entry", "cordis:json", expected)))
            assertEquals<kotlinx.serialization.json.JsonElement?>(expected, received)
            assertNotSame<Any?>(expected, received)
            assertIs<JsonArray>(received!!["nested"])
            assertFalse(isJsExpr((received!!["nested"] as JsonArray).single()))
            val candidate = listOf(EntryOptions("entry", "cordis:json", JsonObject(emptyMap())))
            assertFailsWith<IllegalStateException> {
                loader.withTreeTransaction(candidate) { error("publication rejected") }
            }
            assertEquals<kotlinx.serialization.json.JsonElement?>(expected, received)
            assertIs<JsonObject>(loader.store.getValue("entry").options.config)
        } finally { loaderFiber.dispose() }
    }
}
