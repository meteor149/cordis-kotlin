package org.cordis.demo

import android.app.Activity
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.EffectHandle
import org.cordis.Fiber
import org.cordis.hmr.Hmr
import org.cordis.hmr.HmrConfig
import org.cordis.loader.AndroidModuleDescriptor
import org.cordis.loader.AndroidModuleLoader
import org.cordis.loader.AndroidPluginComponents
import org.cordis.loader.EntryOptions
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderPlugin

class AndroidPluginLabRuntime(
    private val activity: Activity,
) : PluginLabRuntime(
    platformName = "Android",
    artifactKind = "APK / DEX",
    plugins = DEMO_PLUGINS,
    features = listOf(
        RuntimeFeature("Isolated code + resources", "Each theme owns an APK resource table and child-first DexClassLoader."),
        RuntimeFeature("Declared dependency", "Ocean resolves PaletteEngine through palette.apk."),
        RuntimeFeature("Proxy components", "The active plugin Activity runs through CordisProxyActivity."),
        RuntimeFeature("Transactional generations", "Forest v2 commits atomically; the broken release restores the old Fiber."),
    ),
) {
    private val cordis = Context()
    private val pluginDirectory = File(activity.filesDir, "cmp-plugin-lab")
    private var sinkHandle: EffectHandle? = null
    private var loaderFiber: Fiber<LoaderConfig>? = null
    private var loader: Loader? = null
    private var modules: AndroidModuleLoader? = null
    private var components: AndroidPluginComponents? = null
    private var hmr: Hmr? = null
    private var activeId: String? = null
    private var closed = false

    override suspend fun start() = operation("Installing verified APKs") {
        if (loader != null) return@operation
        pluginDirectory.mkdirs()
        sinkHandle = cordis.provide(org.cordis.demo.api.TimerThemeSink.Key, this).also { it.awaitReady() }
        loaderFiber = cordis.plugin(LoaderPlugin, LoaderConfig(pluginDirectory.toURI().toString())).also { it.await() }
        val cordisLoader = cordis.require(Loader.Key)
        val androidModules = AndroidModuleLoader(activity.applicationContext)
        cordisLoader.internal = androidModules
        loader = cordisLoader
        modules = androidModules
        components = AndroidPluginComponents.install(androidModules)
        installBaseArtifacts(androidModules)
        hmr = Hmr(cordis, HmrConfig(base = pluginDirectory.path, debounce = 60_000))
        activateInternal("theme.forest")
        markReady("Verified 4 APK modules; host API identity is shared")
    }

    override suspend fun activate(pluginId: String) = operation("Loading ${pluginTitle(pluginId)}") {
        activateInternal(pluginId)
    }

    override suspend fun installNextGeneration() = operation("Staging Forest generation 2") {
        activateInternal("theme.forest")
        val moduleLoader = checkNotNull(modules)
        val next = installArtifact("forest-next.apk")
        moduleLoader.register(forestDescriptor(next, version = "2.0.0"))
        val url = moduleLoader.moduleUrl("theme.forest")
        checkNotNull(hmr).stash(url)
        check(checkNotNull(hmr).partialReload()) { "Forest generation 2 did not commit" }
        markActive("theme.forest", "2", "Committed forest-next.apk with a fresh DexClassLoader")
    }

    override suspend fun runRollbackProbe() = operation("Applying an intentionally broken APK") {
        activateInternal("theme.forest")
        val moduleLoader = checkNotNull(modules)
        val previous = checkNotNull(moduleLoader.activeModule("theme.forest"))
        val broken = installArtifact("forest-broken.apk")
        moduleLoader.register(forestDescriptor(broken, version = "3.0.0-broken"))
        val url = moduleLoader.moduleUrl("theme.forest")
        checkNotNull(hmr).stash(url)
        val succeeded = checkNotNull(hmr).partialReload()
        check(!succeeded) { "Broken forest unexpectedly became active" }
        check(moduleLoader.activeModule("theme.forest") === previous) { "Rollback changed the active generation" }
        markRollback("Apply failed as designed; generation ${previous.descriptor.version} stayed active")
    }

    override suspend fun openPluginSurface() = operation("Opening plugin-owned Activity") {
        val id = checkNotNull(activeId) { "Select a plugin first" }
        val spec = ANDROID_PLUGINS.getValue(id)
        checkNotNull(components).startActivity(activity, id, spec.activityClass)
        record("component/route", "${spec.activityClass} via CordisProxyActivity")
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        runCatching { hmr?.stop() }.onFailure { Log.w(TAG, "HMR cleanup failed", it) }
        runCatching { loader?.root?.stop() }.onFailure { Log.w(TAG, "Loader cleanup failed", it) }
        runCatching { loaderFiber?.dispose() }.onFailure { Log.w(TAG, "Loader Fiber cleanup failed", it) }
        runCatching { sinkHandle?.dispose() }.onFailure { Log.w(TAG, "Sink cleanup failed", it) }
        components?.close()
        activeId?.let { modules?.release(it) }
        modules?.release("palette")
    }

    private suspend fun activateInternal(pluginId: String) {
        val cordisLoader = checkNotNull(loader)
        val moduleLoader = checkNotNull(modules)
        if (activeId == pluginId && cordisLoader.store.containsKey(ACTIVE_ENTRY)) return
        if (cordisLoader.store.containsKey(ACTIVE_ENTRY)) cordisLoader.remove(ACTIVE_ENTRY)
        activeId?.takeUnless { it == pluginId }?.let(moduleLoader::release)
        moduleLoader.import(moduleLoader.moduleUrl(pluginId), null)
        cordisLoader.create(EntryOptions(id = ACTIVE_ENTRY, name = moduleLoader.moduleUrl(pluginId), config = Unit))
        checkNotNull(cordisLoader.resolve(ACTIVE_ENTRY).fiber) { "$pluginId did not create a Cordis Fiber" }
        activeId = pluginId
        val version = checkNotNull(moduleLoader.activeModule(pluginId)).descriptor.version
        markActive(pluginId, version.substringBefore('.'), "${pluginTitle(pluginId)} is active from an isolated APK")
    }

    private suspend fun installBaseArtifacts(loader: AndroidModuleLoader) {
        val palette = installArtifact("palette.apk")
        loader.register(AndroidModuleDescriptor(
            id = "palette",
            version = "1.0.0",
            entryClass = "dev.cordis.demo.plugins.palette.PalettePlugin",
            file = palette,
            expectedSha256 = sha256(palette),
            packageName = "dev.cordis.demo.plugins.palette",
        ))
        ANDROID_PLUGINS.values.forEach { spec ->
            val file = installArtifact(spec.assetName)
            loader.register(AndroidModuleDescriptor(
                id = spec.id,
                version = "1.0.0",
                entryClass = spec.entryClass,
                file = file,
                expectedSha256 = sha256(file),
                dependencies = spec.dependencies,
                sharedHostPackages = setOf("org.cordis.demo.api"),
                packageName = spec.packageName,
                activities = mapOf(spec.activityClass to 0),
            ))
        }
    }

    private fun forestDescriptor(file: File, version: String) = AndroidModuleDescriptor(
        id = "theme.forest",
        version = version,
        entryClass = ANDROID_PLUGINS.getValue("theme.forest").entryClass,
        file = file,
        expectedSha256 = sha256(file),
        sharedHostPackages = setOf("org.cordis.demo.api"),
        packageName = ANDROID_PLUGINS.getValue("theme.forest").packageName,
        activities = mapOf(ANDROID_PLUGINS.getValue("theme.forest").activityClass to 0),
    )

    private suspend fun installArtifact(assetName: String): File = withContext(Dispatchers.IO) {
        val target = File(pluginDirectory, assetName)
        val temporary = File(pluginDirectory, ".$assetName.installing")
        if (temporary.exists()) check(temporary.delete()) { "Unable to replace $temporary" }
        activity.assets.open("plugins/android/$assetName").use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        check(temporary.setReadOnly()) { "Unable to make plugin read-only: $temporary" }
        if (target.exists()) check(target.delete()) { "Unable to replace $target" }
        check(temporary.renameTo(target)) { "Unable to publish $target" }
        target
    }

    private fun pluginTitle(id: String) = plugins.first { it.id == id }.title

    private data class AndroidPluginSpec(
        val id: String,
        val assetName: String,
        val packageName: String,
        val entryClass: String,
        val activityClass: String,
        val dependencies: List<String> = emptyList(),
    )

    private companion object {
        const val ACTIVE_ENTRY = "active-theme"
        const val TAG = "CordisPluginLab"

        val ANDROID_PLUGINS = listOf(
            AndroidPluginSpec(
                "theme.forest", "forest.apk", "dev.cordis.demo.plugins.forest",
                "dev.cordis.demo.plugins.forest.ForestThemePlugin",
                "dev.cordis.demo.plugins.forest.ForestPreviewActivity",
            ),
            AndroidPluginSpec(
                "theme.ocean", "ocean.apk", "dev.cordis.demo.plugins.ocean",
                "dev.cordis.demo.plugins.ocean.OceanThemePlugin",
                "dev.cordis.demo.plugins.ocean.OceanPreviewActivity",
                dependencies = listOf("palette"),
            ),
            AndroidPluginSpec(
                "theme.sunset", "sunset.apk", "dev.cordis.demo.plugins.sunset",
                "dev.cordis.demo.plugins.sunset.SunsetThemePlugin",
                "dev.cordis.demo.plugins.sunset.SunsetPreviewActivity",
            ),
        ).associateBy(AndroidPluginSpec::id)
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
