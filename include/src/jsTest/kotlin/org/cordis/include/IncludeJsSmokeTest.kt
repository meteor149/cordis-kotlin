package org.cordis.include

import kotlinx.coroutines.test.runTest
import org.cordis.Context
import org.cordis.loader.EntryOptions
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val fs: dynamic = js("require('node:fs')")
private val pathApi: dynamic = js("require('node:path')")
private val osApi: dynamic = js("require('node:os')")

class IncludeJsSmokeTest {
    @Test
    fun nodeFileModelCreatesAndLoadsJson() = runTest {
        val directory = pathApi.join(osApi.tmpdir(), "cordis-kmp-${js("Date.now()")}") as String
        val file = pathApi.join(directory, "config.json") as String
        val root = Context()
        val loader = Loader(root, LoaderConfig(directory))
        loader.builtins["fixture"] = plugin<Unit>(name = "fixture") { _, _ -> }
        val include = Include(root, IncludeConfig(
            path = file,
            initial = listOf(EntryOptions(id = "fixture", name = "cordis:fixture", config = Unit)),
        ))
        try {
            include.init()
            assertTrue(fs.existsSync(file) as Boolean)
            assertEquals("cordis:fixture", include.resolve("fixture").options.name)
        } finally {
            include.root.stop()
            fs.rmSync(directory, js("({ recursive: true, force: true })"))
        }
    }
}
