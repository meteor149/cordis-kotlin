package org.cordis.demo

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.loader.AndroidModuleDescriptor
import org.cordis.loader.AndroidModuleLoader

data class ThemePluginSpec(
    val id: String,
    val title: String,
    val caption: String,
    val symbol: String,
    val previewColor: Int,
    val assetName: String,
    val packageName: String,
    val entryClass: String,
    val activityClass: String,
)

object PluginCatalog {
    val plugins = listOf(
        ThemePluginSpec(
            id = "theme.forest",
            title = "Tranquil Forest",
            caption = "Pine green · Focus",
            symbol = "✦",
            previewColor = 0xFF55D6A5.toInt(),
            assetName = "forest.apk",
            packageName = "dev.cordis.demo.plugins.forest",
            entryClass = "dev.cordis.demo.plugins.forest.ForestThemePlugin",
            activityClass = "dev.cordis.demo.plugins.forest.ForestPreviewActivity",
        ),
        ThemePluginSpec(
            id = "theme.ocean",
            title = "Deep Ocean",
            caption = "Electric blue · Immerse",
            symbol = "◉",
            previewColor = 0xFF53C8FF.toInt(),
            assetName = "ocean.apk",
            packageName = "dev.cordis.demo.plugins.ocean",
            entryClass = "dev.cordis.demo.plugins.ocean.OceanThemePlugin",
            activityClass = "dev.cordis.demo.plugins.ocean.OceanPreviewActivity",
        ),
        ThemePluginSpec(
            id = "theme.sunset",
            title = "Sunset Glow",
            caption = "Warm coral · Unwind",
            symbol = "☼",
            previewColor = 0xFFFF8C6B.toInt(),
            assetName = "sunset.apk",
            packageName = "dev.cordis.demo.plugins.sunset",
            entryClass = "dev.cordis.demo.plugins.sunset.SunsetThemePlugin",
            activityClass = "dev.cordis.demo.plugins.sunset.SunsetPreviewActivity",
        ),
    )

    suspend fun installAll(context: Context, loader: AndroidModuleLoader) = withContext(Dispatchers.IO) {
        val pluginDirectory = File(context.filesDir, "demo-plugins").apply { mkdirs() }
        plugins.forEach { spec ->
            val target = File(pluginDirectory, spec.assetName)
            val temporary = File(pluginDirectory, ".${spec.assetName}.installing")
            if (temporary.exists()) {
                check(temporary.delete()) { "Unable to replace $temporary" }
            }
            context.assets.open("plugins/${spec.assetName}").use { input ->
                temporary.outputStream().use(input::copyTo)
            }
            // Android 14+ refuses to load writable dex. Publish the APK only after
            // its write bit has been removed, so the loader never observes mutable code.
            check(temporary.setReadOnly()) { "Unable to make plugin read-only: $temporary" }
            if (target.exists()) {
                check(target.delete()) { "Unable to replace $target" }
            }
            check(temporary.renameTo(target)) { "Unable to publish plugin: $target" }
            loader.register(
                AndroidModuleDescriptor(
                    id = spec.id,
                    version = "1.0.0",
                    entryClass = spec.entryClass,
                    file = target,
                    expectedSha256 = sha256(target),
                    sharedHostPackages = setOf("org.cordis.demo.api"),
                    packageName = spec.packageName,
                    activities = mapOf(spec.activityClass to 0),
                ),
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
