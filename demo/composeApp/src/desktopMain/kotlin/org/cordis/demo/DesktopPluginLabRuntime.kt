package org.cordis.demo

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.EffectHandle
import org.cordis.Fiber
import org.cordis.demo.api.PluginSurface
import org.cordis.hmr.Hmr
import org.cordis.hmr.HmrConfig
import org.cordis.loader.EntryOptions
import org.cordis.loader.JvmModuleArtifact
import org.cordis.loader.JvmModuleDescriptor
import org.cordis.loader.JvmModuleLoader
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderPlugin

class DesktopPluginLabRuntime(
    private val pluginDirectory: File = File(
        System.getProperty("java.io.tmpdir"),
        "cordis-plugin-lab/${ProcessHandle.current().pid()}",
    ),
) : PluginLabRuntime(
    platformName = "Desktop JVM",
    artifactKind = "JAR / URLCLASSLOADER",
    plugins = DEMO_PLUGINS,
    features = listOf(
        RuntimeFeature("Child-first isolation", "Plugin code and resources cannot see arbitrary host implementation packages."),
        RuntimeFeature("Declared dependency graph", "Ocean resolves PaletteEngine through a separate registered module."),
        RuntimeFeature("Private classpath", "Sunset receives a checksummed support JAR inside its own loader boundary."),
        RuntimeFeature("Transactional HMR", "A fresh URLClassLoader commits only after every replacement Fiber applies."),
    ),
) {
    private val cordis = Context()
    private var sinkHandle: EffectHandle? = null
    private var loaderFiber: Fiber<LoaderConfig>? = null
    private var loader: Loader? = null
    private var modules: JvmModuleLoader? = null
    private var hmr: Hmr? = null
    private var activeId: String? = null
    private var closed = false

    override suspend fun start() = operation("Extracting verified JAR artifacts") {
        if (loader != null) return@operation
        pluginDirectory.mkdirs()
        val artifacts = DESKTOP_ASSETS.keys.associateWith { installArtifact(it) }
        sinkHandle = cordis.provide(org.cordis.demo.api.TimerThemeSink.Key, this).also { it.awaitReady() }
        loaderFiber = cordis.plugin(LoaderPlugin, LoaderConfig(pluginDirectory.toURI().toString())).also { it.await() }
        val cordisLoader = cordis.require(Loader.Key)
        val jvmModules = JvmModuleLoader(pluginDirectory)
        cordisLoader.internal = jvmModules
        loader = cordisLoader
        modules = jvmModules
        registerBaseArtifacts(jvmModules, artifacts)
        hmr = Hmr(cordis, HmrConfig(base = pluginDirectory.path, debounce = 60_000))
        activateInternal("theme.forest")
        markReady("Verified 5 JAR artifacts and one private classpath dependency")
    }

    override suspend fun activate(pluginId: String) = operation("Loading ${pluginTitle(pluginId)}") {
        activateInternal(pluginId)
    }

    override suspend fun installNextGeneration() = operation("Staging Forest generation 2") {
        activateInternal("theme.forest")
        val moduleLoader = checkNotNull(modules)
        val next = File(pluginDirectory, "forest-next.jar")
        moduleLoader.register(forestDescriptor(next, version = "2.0.0"))
        val url = moduleLoader.moduleUrl("theme.forest")
        checkNotNull(hmr).stash(url)
        check(checkNotNull(hmr).partialReload()) { "Forest generation 2 did not commit" }
        markActive("theme.forest", "2", "Committed forest-next.jar; the host timer kept running")
    }

    override suspend fun runRollbackProbe() = operation("Applying an intentionally broken JAR") {
        activateInternal("theme.forest")
        val moduleLoader = checkNotNull(modules)
        val previous = checkNotNull(moduleLoader.activeModule("theme.forest"))
        val broken = File(pluginDirectory, "forest-broken.jar")
        moduleLoader.register(forestDescriptor(broken, version = "3.0.0-broken"))
        val url = moduleLoader.moduleUrl("theme.forest")
        checkNotNull(hmr).stash(url)
        val succeeded = checkNotNull(hmr).partialReload()
        check(!succeeded) { "Broken forest unexpectedly became active" }
        check(moduleLoader.activeModule("theme.forest") === previous) { "Rollback changed the active generation" }
        markRollback("Apply failed as designed; generation ${previous.descriptor.version} stayed active")
    }

    override suspend fun openPluginSurface() = operation("Reading plugin-owned resource") {
        val id = checkNotNull(activeId) { "Select a plugin first" }
        val plugin = checkNotNull(modules?.activeModule(id)?.plugin as? PluginSurface) {
            "$id does not implement PluginSurface"
        }
        showSurface(plugin.surfaceInfo())
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        runCatching { hmr?.stop() }
        runCatching { loader?.root?.stop() }
        runCatching { loaderFiber?.dispose() }
        runCatching { sinkHandle?.dispose() }
        modules?.close()
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
        markActive(pluginId, version.substringBefore('.'), "${pluginTitle(pluginId)} is active from an isolated JAR")
    }

    private suspend fun registerBaseArtifacts(loader: JvmModuleLoader, artifacts: Map<String, File>) {
        val palette = artifacts.getValue("palette.jar")
        loader.register(JvmModuleDescriptor(
            id = "palette",
            version = "1.0.0",
            entryClass = "dev.cordis.demo.plugins.palette.PalettePlugin",
            file = palette,
            expectedSha256 = sha256(palette),
        ))
        val support = artifacts.getValue("sunset-support.jar")
        DESKTOP_PLUGINS.values.forEach { spec ->
            val file = artifacts.getValue(spec.assetName)
            loader.register(JvmModuleDescriptor(
                id = spec.id,
                version = "1.0.0",
                entryClass = spec.entryClass,
                file = file,
                expectedSha256 = sha256(file),
                dependencies = spec.dependencies,
                sharedHostPackages = setOf("org.cordis.demo.api"),
                classpath = if (spec.id == "theme.sunset") {
                    listOf(JvmModuleArtifact(support, sha256(support)))
                } else {
                    emptyList()
                },
            ))
        }
    }

    private fun forestDescriptor(file: File, version: String) = JvmModuleDescriptor(
        id = "theme.forest",
        version = version,
        entryClass = DESKTOP_PLUGINS.getValue("theme.forest").entryClass,
        file = file,
        expectedSha256 = sha256(file),
        sharedHostPackages = setOf("org.cordis.demo.api"),
    )

    private suspend fun installArtifact(assetName: String): File = withContext(Dispatchers.IO) {
        val resource = DESKTOP_ASSETS.getValue(assetName)
        val target = File(pluginDirectory, assetName)
        javaClass.classLoader.getResourceAsStream(resource).use { input ->
            requireNotNull(input) { "Missing packaged plugin artifact $resource" }
            target.outputStream().use(input::copyTo)
        }
        target
    }

    private fun pluginTitle(id: String) = plugins.first { it.id == id }.title

    private data class DesktopPluginSpec(
        val id: String,
        val assetName: String,
        val entryClass: String,
        val dependencies: List<String> = emptyList(),
    )

    private companion object {
        const val ACTIVE_ENTRY = "active-theme"

        val DESKTOP_ASSETS = listOf(
            "palette.jar",
            "forest.jar",
            "forest-next.jar",
            "forest-broken.jar",
            "ocean.jar",
            "sunset-support.jar",
            "sunset.jar",
        ).associateWith { "plugins/desktop/$it" }

        val DESKTOP_PLUGINS = listOf(
            DesktopPluginSpec(
                "theme.forest", "forest.jar", "dev.cordis.demo.plugins.forest.ForestThemePlugin",
            ),
            DesktopPluginSpec(
                "theme.ocean", "ocean.jar", "dev.cordis.demo.plugins.ocean.OceanThemePlugin",
                dependencies = listOf("palette"),
            ),
            DesktopPluginSpec(
                "theme.sunset", "sunset.jar", "dev.cordis.demo.plugins.sunset.SunsetThemePlugin",
            ),
        ).associateBy(DesktopPluginSpec::id)
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
