package org.cordis.loader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.Plugin

actual class ClasspathModuleLoader actual constructor() : ModuleLoader {
    actual override suspend fun import(specifier: String, parentUrl: String?): Any? =
        instantiatePluginClass(Class.forName(specifier.removePrefix(CLASS_PREFIX)))

    actual override fun resolve(specifier: String, parentUrl: String?): ResolveResult =
        ResolveResult(ModuleFormat.MODULE, specifier)
}

/**
 * Describes one already-installed Android plugin APK or dex container.
 *
 * Installation and download deliberately stay outside [AndroidModuleLoader]. The file should be
 * copied to immutable app-private storage and [expectedSha256] must come from trusted, preferably
 * signed, metadata before the descriptor is registered.
 */
data class AndroidModuleDescriptor(
    val id: String,
    val version: String,
    val entryClass: String,
    val file: File,
    val expectedSha256: String,
    val dependencies: List<String> = emptyList(),
    val sharedHostPackages: Set<String> = emptySet(),
    val nativeLibraryDirectory: File? = null,
    /** Optional package name check used by the component/resource runtime. */
    val packageName: String? = null,
    /** Plugin Activity class names and their optional plugin theme resource ids. */
    val activities: Map<String, Int> = emptyMap(),
    /** Plugin Service class names accepted by the service proxy. */
    val services: Set<String> = emptySet(),
)

/** Immutable view of one active plugin generation. Existing components retain this generation on reload. */
class AndroidModuleHandle internal constructor(
    val descriptor: AndroidModuleDescriptor,
    val classLoader: ClassLoader,
    val plugin: Plugin<*>,
    internal val generation: Long,
) {
    @Volatile
    private var resourceState: Pair<ApplicationInfo, Resources>? = null

    internal fun resources(context: Context): Pair<ApplicationInfo, Resources> =
        resourceState ?: synchronized(this) {
            resourceState ?: createAndroidPluginResources(context, descriptor).also { resourceState = it }
        }
}

/** Additional verification hook for signature, certificate, or release-policy checks. */
fun interface AndroidModuleVerifier {
    fun verify(descriptor: AndroidModuleDescriptor)
}

/**
 * Android dex-backed [ModuleLoader] inspired by Shadow's isolated plugin class-loader boundary.
 *
 * Plugin classes are loaded child-first. Android/JDK/Kotlin/Cordis API classes and explicitly
 * shared host packages are loaded from the host, preserving the identity of [Plugin]. Arbitrary
 * host implementation packages are not visible to plugins.
 *
 * Specifiers use `android-plugin:<id>`. Registering a new descriptor for an active id only stages
 * the new release. Call [beginReload] with [moduleUrl] and commit the transaction after Cordis has
 * successfully replaced the old plugin runtime.
 *
 * Component and resource proxies are opt-in through [AndroidPluginComponents.install]. This loader
 * does not download packages or bypass Android/Google Play executable-code policies.
 */
class AndroidModuleLoader(
    context: Context,
    private val hostClassLoader: ClassLoader = context.classLoader,
    private val trustedRoot: File = File(context.applicationInfo.dataDir),
    private val verifier: AndroidModuleVerifier = AndroidModuleVerifier { },
) : ModuleLoader {
    private val lock = Any()
    private val codeCacheDirectory = File(context.codeCacheDir, "cordis-plugins")
    private val descriptors = linkedMapOf<String, AndroidModuleDescriptor>()
    private val active = linkedMapOf<String, LoadedModule>()
    private val generations = linkedMapOf<Long, LoadedModule>()

    /** Registers installed metadata without activating or replacing a running plugin. */
    suspend fun register(descriptor: AndroidModuleDescriptor) = withContext(Dispatchers.IO) {
        validateAndroidModuleDescriptor(descriptor, trustedRoot)
        verifyDescriptor(descriptor)
        synchronized(lock) { descriptors[descriptor.id] = descriptor.canonicalized() }
    }

    /** Removes an inactive descriptor. Running plugin code must be disposed before unregistering. */
    fun unregister(id: String): AndroidModuleDescriptor? = synchronized(lock) {
        require(active[id] == null) { "Android module $id is active; dispose and release it first" }
        descriptors.remove(id)
    }

    /** Releases the loader's strong reference after the corresponding Cordis runtime is disposed. */
    fun release(id: String) = synchronized(lock) {
        active.remove(id)
        generations.entries.removeAll { it.value.descriptor.id == id }
        Unit
    }

    fun registered(): List<AndroidModuleDescriptor> = synchronized(lock) { descriptors.values.toList() }

    fun moduleUrl(id: String): String = "$PLUGIN_PREFIX$id"

    /** Returns the currently active generation, or null until the module has been imported. */
    fun activeModule(id: String): AndroidModuleHandle? = synchronized(lock) { active[id]?.handle }

    internal fun moduleGeneration(generation: Long): AndroidModuleHandle? =
        synchronized(lock) { generations[generation]?.handle }

    override suspend fun import(specifier: String, parentUrl: String?): Any? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val id = pluginId(specifier)
            if (id == null) {
                return@synchronized instantiatePluginClass(
                    Class.forName(specifier.removePrefix(CLASS_PREFIX), true, hostClassLoader),
                )
            }
            load(id, linkedMapOf(), emptySet(), linkedSetOf()).instance
        }
    }

    override fun resolve(specifier: String, parentUrl: String?): ResolveResult {
        val id = pluginId(specifier)
            ?: return ResolveResult(ModuleFormat.MODULE, specifier)
        synchronized(lock) { requireDescriptor(id) }
        return ResolveResult(ModuleFormat.MODULE, moduleUrl(id))
    }

    override fun contains(url: String): Boolean = synchronized(lock) {
        pluginId(url)?.let(descriptors::containsKey) == true ||
            descriptors.values.any { it.fileUrl() == url }
    }

    override fun linked(url: String): List<String> = synchronized(lock) {
        val id = pluginId(url) ?: return@synchronized emptyList()
        val descriptor = descriptors[id] ?: return@synchronized emptyList()
        buildList {
            add(descriptor.fileUrl())
            descriptor.dependencies.mapTo(this) { moduleUrl(it) }
        }
    }

    override fun peek(url: String): Any? = synchronized(lock) {
        pluginId(url)?.let(active::get)?.instance
    }

    override fun beginReload(urls: Set<String>): ReloadTransaction {
        val affected = synchronized(lock) {
            descriptors.values.mapNotNullTo(linkedSetOf()) { descriptor ->
                descriptor.id.takeIf { moduleUrl(it) in urls || descriptor.fileUrl() in urls }
            }
        }
        val staged = linkedMapOf<String, LoadedModule>()
        var completed = false

        return object : ReloadTransaction {
            override suspend fun import(url: String): Any? = withContext(Dispatchers.IO) {
                synchronized(lock) {
                    check(!completed) { "reload transaction is already complete" }
                    val id = pluginId(url)
                        ?: return@synchronized instantiatePluginClass(
                            Class.forName(url.removePrefix(CLASS_PREFIX), true, hostClassLoader),
                        )
                    val forceFresh = affected + id
                    load(id, staged, forceFresh, linkedSetOf()).instance
                }
            }

            override fun commit() = synchronized(lock) {
                check(!completed) { "reload transaction is already complete" }
                staged.forEach { (id, module) ->
                    check(descriptors[id] == module.descriptor) {
                        "Android module $id changed while its reload transaction was in progress"
                    }
                    active[id] = module
                    generations[module.handle.generation] = module
                }
                completed = true
                staged.clear()
            }

            override fun rollback() = synchronized(lock) {
                if (completed) return@synchronized
                completed = true
                staged.clear()
            }
        }
    }

    private fun load(
        id: String,
        staged: MutableMap<String, LoadedModule>,
        forceFresh: Set<String>,
        visiting: MutableSet<String>,
    ): LoadedModule {
        staged[id]?.let { return it }
        if (id !in forceFresh) active[id]?.let { return it }
        check(visiting.add(id)) { "cyclic Android module dependency: ${(visiting + id).joinToString(" -> ")}" }

        try {
            val descriptor = requireDescriptor(id)
            verifyDescriptor(descriptor)
            val dependencies = descriptor.dependencies.map { dependency ->
                load(dependency, staged, forceFresh, visiting)
            }
            val classLoader = IsolatedPluginClassLoader(
                descriptor = descriptor,
                optimizedDirectory = File(codeCacheDirectory, "${descriptor.id}/${descriptor.version}"),
                hostClassLoader = hostClassLoader,
                dependencyClassLoaders = dependencies.map { it.classLoader },
            )
            val instance = instantiatePluginClass(classLoader.loadClass(descriptor.entryClass))
            require(instance is Plugin<*>) {
                "Android module ${descriptor.id} entry ${descriptor.entryClass} does not implement org.cordis.Plugin; " +
                    "the plugin APK must not package its own Cordis API"
            }
            return LoadedModule(AndroidModuleHandle(descriptor, classLoader, instance, NEXT_GENERATION.getAndIncrement())).also {
                if (id in forceFresh) {
                    staged[id] = it
                } else {
                    active[id] = it
                    generations[it.handle.generation] = it
                }
            }
        } finally {
            visiting.remove(id)
        }
    }

    private fun requireDescriptor(id: String): AndroidModuleDescriptor =
        descriptors[id] ?: throw IllegalArgumentException("Android module $id is not registered")

    private fun verifyDescriptor(descriptor: AndroidModuleDescriptor) {
        val actual = androidModuleSha256(descriptor.file)
        require(actual.equals(descriptor.expectedSha256, ignoreCase = true)) {
            "SHA-256 mismatch for Android module ${descriptor.id}: expected ${descriptor.expectedSha256}, got $actual"
        }
        verifier.verify(descriptor)
    }

    private data class LoadedModule(
        val handle: AndroidModuleHandle,
    ) {
        val descriptor get() = handle.descriptor
        val classLoader get() = handle.classLoader
        val instance get() = handle.plugin
    }
}

private class IsolatedPluginClassLoader(
    descriptor: AndroidModuleDescriptor,
    optimizedDirectory: File,
    private val hostClassLoader: ClassLoader,
    private val dependencyClassLoaders: List<ClassLoader>,
) : DexClassLoader(
    descriptor.file.path,
    optimizedDirectory.apply(File::mkdirs).path,
    descriptor.nativeLibraryDirectory?.path,
    hostClassLoader,
) {
    private val sharedPackages = DEFAULT_SHARED_PACKAGES + descriptor.sharedHostPackages
    private val platformClassLoader = hostClassLoader.parent

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        findLoadedClass(name)?.let { return@synchronized it }

        if (sharedPackages.any { name == it || name.startsWith("$it.") }) {
            return@synchronized hostClassLoader.loadClass(name)
        }

        val failures = mutableListOf<ClassNotFoundException>()
        runCatching { findClass(name) }.getOrElse { error ->
            if (error is ClassNotFoundException) failures += error else throw error
            dependencyClassLoaders.forEach { dependency ->
                try {
                    return@synchronized dependency.loadClass(name)
                } catch (dependencyError: ClassNotFoundException) {
                    failures += dependencyError
                }
            }
            platformClassLoader?.let { platform ->
                try {
                    return@synchronized platform.loadClass(name)
                } catch (platformError: ClassNotFoundException) {
                    failures += platformError
                }
            }
            throw ClassNotFoundException(name).also { result -> failures.forEach(result::addSuppressed) }
        }.also { if (resolve) resolveClass(it) }
    }
}

private fun instantiatePluginClass(type: Class<*>): Any {
    val singleton = runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
    return singleton ?: type.getDeclaredConstructor().newInstance()
}

private fun AndroidModuleDescriptor.canonicalized() = copy(
    file = file.canonicalFile,
    nativeLibraryDirectory = nativeLibraryDirectory?.canonicalFile,
    expectedSha256 = expectedSha256.lowercase(),
)

private fun AndroidModuleDescriptor.fileUrl(): String = file.canonicalFile.toURI().toString()

private fun File.isInside(root: File): Boolean =
    path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)

private fun pluginId(value: String): String? = value
    .takeIf { it.startsWith(PLUGIN_PREFIX) }
    ?.removePrefix(PLUGIN_PREFIX)
    ?.takeIf(ID_PATTERN::matches)

internal fun validateAndroidModuleDescriptor(descriptor: AndroidModuleDescriptor, trustedRoot: File) {
    require(ID_PATTERN.matches(descriptor.id)) { "invalid Android module id: ${descriptor.id}" }
    require(VERSION_PATTERN.matches(descriptor.version)) { "invalid Android module version: ${descriptor.version}" }
    require(CLASS_NAME_PATTERN.matches(descriptor.entryClass)) {
        "invalid Android module entry class: ${descriptor.entryClass}"
    }
    require(descriptor.dependencies.distinct().size == descriptor.dependencies.size) {
        "Android module ${descriptor.id} contains duplicate dependencies"
    }
    require(descriptor.id !in descriptor.dependencies) { "Android module ${descriptor.id} depends on itself" }
    require(SHA256_PATTERN.matches(descriptor.expectedSha256)) {
        "expectedSha256 must contain exactly 64 hexadecimal characters"
    }
    descriptor.sharedHostPackages.forEach { packageName ->
        require(PACKAGE_PATTERN.matches(packageName)) { "invalid shared host package: $packageName" }
    }
    descriptor.packageName?.let { packageName ->
        require(PACKAGE_PATTERN.matches(packageName)) { "invalid plugin package name: $packageName" }
    }
    require(
        descriptor.packageName != null ||
            (descriptor.activities.isEmpty() && descriptor.services.isEmpty()),
    ) { "component-enabled Android module ${descriptor.id} must declare packageName" }
    descriptor.activities.forEach { (className, theme) ->
        require(CLASS_NAME_PATTERN.matches(className)) { "invalid plugin Activity class: $className" }
        require(theme >= 0) { "plugin Activity theme must be a resource id or zero: $className" }
    }
    descriptor.services.forEach { className ->
        require(CLASS_NAME_PATTERN.matches(className)) { "invalid plugin Service class: $className" }
    }

    val canonicalRoot = trustedRoot.canonicalFile
    val canonicalFile = descriptor.file.canonicalFile
    require(canonicalFile.isFile) { "Android module file does not exist: $canonicalFile" }
    require(canonicalFile.isInside(canonicalRoot)) {
        "Android module file must be in app-private trusted root $canonicalRoot: $canonicalFile"
    }
    descriptor.nativeLibraryDirectory?.canonicalFile?.let { nativeDirectory ->
        require(nativeDirectory.isDirectory) { "native library directory does not exist: $nativeDirectory" }
        require(nativeDirectory.isInside(canonicalRoot)) {
            "native library directory must be in app-private trusted root $canonicalRoot: $nativeDirectory"
        }
    }
}

internal fun androidModuleSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private const val CLASS_PREFIX = "class:"
private const val PLUGIN_PREFIX = "android-plugin:"
private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
private val CLASS_NAME_PATTERN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
private val PACKAGE_PATTERN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")
private val DEFAULT_SHARED_PACKAGES = setOf(
    "java",
    "javax",
    "android",
    "dalvik",
    "kotlin",
    "kotlinx.coroutines",
    "org.cordis",
)
private val NEXT_GENERATION = AtomicLong(1)

internal actual fun platformModuleLoader(): ModuleLoader? = null
