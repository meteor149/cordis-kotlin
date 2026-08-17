package org.cordis.loader

import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.Plugin

actual class ClasspathModuleLoader actual constructor() : ModuleLoader {
    actual override suspend fun import(specifier: String, parentUrl: String?): Any? =
        instantiateJvmClass(Class.forName(specifier.removePrefix(CLASS_PREFIX)))

    actual override fun resolve(specifier: String, parentUrl: String?): ResolveResult =
        ResolveResult(ModuleFormat.MODULE, specifier)
}

/** One checksummed private dependency or native library belonging to a JVM plugin. */
data class JvmModuleArtifact(
    val file: File,
    val expectedSha256: String,
)

/**
 * Describes one installed desktop/JVM plugin release.
 *
 * Every file must be immutable, reside below the loader's trusted root, and match its trusted
 * SHA-256 digest. [classpath] contains private JAR dependencies. [nativeLibraries] contains exact
 * JNI library files that may be resolved by `System.loadLibrary` from plugin code.
 */
data class JvmModuleDescriptor(
    val id: String,
    val version: String,
    val entryClass: String,
    val file: File,
    val expectedSha256: String,
    val dependencies: List<String> = emptyList(),
    val sharedHostPackages: Set<String> = emptySet(),
    val classpath: List<JvmModuleArtifact> = emptyList(),
    val nativeLibraries: List<JvmModuleArtifact> = emptyList(),
)

/** Immutable view of one loaded JVM plugin generation. */
class JvmModuleHandle internal constructor(
    val descriptor: JvmModuleDescriptor,
    val classLoader: ClassLoader,
    val plugin: Plugin<*>,
    val generation: Long,
)

/** Additional host hook for signature, publisher, or release-policy verification. */
fun interface JvmModuleVerifier {
    fun verify(descriptor: JvmModuleDescriptor)
}

/**
 * Isolated desktop/JVM [ModuleLoader] for plugins installed as JAR files.
 *
 * Plugin implementation classes and resources are child-first. JDK, Kotlin, Cordis, and packages
 * explicitly named in [JvmModuleDescriptor.sharedHostPackages] retain host type identity. Other
 * host implementation packages are hidden. Plugin dependencies are searched after the plugin's
 * own private class path.
 *
 * Specifiers use `jvm-plugin:<id>`. Registering a new descriptor only stages release metadata;
 * the active generation changes after a successful [beginReload] transaction. Rolled-back and
 * superseded URL class loaders are closed as soon as no active dependent still references them.
 */
class JvmModuleLoader(
    trustedRoot: File,
    private val hostClassLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: JvmModuleLoader::class.java.classLoader,
    private val verifier: JvmModuleVerifier = JvmModuleVerifier { },
    externalFiles: Set<File> = emptySet(),
) : ModuleLoader, AutoCloseable {
    private val lock = Any()
    private val trustedRoot = trustedRoot.canonicalFile
    private val externalUrls = externalFiles.mapTo(linkedSetOf()) { it.canonicalFile.toPath().toUri().toString() }
    private val descriptors = linkedMapOf<String, JvmModuleDescriptor>()
    private val active = linkedMapOf<String, LoadedModule>()
    private val roots = linkedSetOf<String>()
    private val retired = mutableListOf<LoadedModule>()
    private var closed = false

    /** Verifies and registers installed metadata without replacing a running generation. */
    suspend fun register(descriptor: JvmModuleDescriptor) = withContext(Dispatchers.IO) {
        val canonical = descriptor.canonicalized()
        validateJvmModuleDescriptor(canonical, trustedRoot)
        verifyDescriptor(canonical)
        synchronized(lock) {
            ensureOpen()
            descriptors[canonical.id] = canonical
        }
    }

    /** Removes release metadata after the module is no longer directly active. */
    fun unregister(id: String): JvmModuleDescriptor? = synchronized(lock) {
        ensureOpen()
        require(!isRetained(id)) { "JVM module $id is active or retained by a dependent; release it first" }
        descriptors.remove(id)
    }

    /** Releases a directly imported module and any dependencies no other imported module retains. */
    fun release(id: String) = synchronized(lock) {
        ensureOpen()
        roots.remove(id)
        pruneActive()
        pruneRetired()
    }

    fun registered(): List<JvmModuleDescriptor> = synchronized(lock) {
        ensureOpen()
        descriptors.values.toList()
    }

    fun moduleUrl(id: String): String {
        require(ID_PATTERN.matches(id)) { "invalid JVM module id: $id" }
        return "$PLUGIN_PREFIX$id"
    }

    /** Returns the active generation, or null before its first successful import. */
    fun activeModule(id: String): JvmModuleHandle? = synchronized(lock) {
        ensureOpen()
        active[id]?.handle
    }

    override suspend fun import(specifier: String, parentUrl: String?): Any? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureOpen()
            val id = parsePluginId(specifier)
            if (id == null) {
                return@synchronized instantiateJvmClass(
                    Class.forName(specifier.removePrefix(CLASS_PREFIX), true, hostClassLoader),
                )
            }

            val staged = linkedMapOf<String, LoadedModule>()
            try {
                val result = load(id, staged, emptySet(), linkedSetOf()).instance
                install(staged)
                roots += id
                result
            } catch (error: Throwable) {
                closeModules(staged.values)
                throw error
            }
        }
    }

    override fun resolve(specifier: String, parentUrl: String?): ResolveResult {
        val id = parsePluginId(specifier)
            ?: return ResolveResult(ModuleFormat.MODULE, specifier)
        synchronized(lock) {
            ensureOpen()
            requireDescriptor(id)
        }
        return ResolveResult(ModuleFormat.MODULE, moduleUrl(id))
    }

    override fun contains(url: String): Boolean = synchronized(lock) {
        ensureOpen()
        parsePluginId(url, strict = false)?.let(descriptors::containsKey) == true ||
            descriptors.values.any { descriptor -> descriptor.linkedArtifacts().any { it.fileUrl() == url } }
    }

    override fun linked(url: String): List<String> = synchronized(lock) {
        ensureOpen()
        val id = parsePluginId(url, strict = false) ?: return@synchronized emptyList()
        val descriptor = descriptors[id] ?: return@synchronized emptyList()
        buildList {
            descriptor.linkedArtifacts().mapTo(this) { it.fileUrl() }
            descriptor.dependencies.mapTo(this) { moduleUrl(it) }
        }
    }

    override fun externals(): Set<String> = externalUrls.toSet()

    override fun peek(url: String): Any? = synchronized(lock) {
        ensureOpen()
        parsePluginId(url, strict = false)?.let(active::get)?.instance
    }

    override fun beginReload(urls: Set<String>): ReloadTransaction {
        val affected = synchronized(lock) {
            ensureOpen()
            descriptors.values.mapNotNullTo(linkedSetOf()) { descriptor ->
                descriptor.id.takeIf { moduleUrl(it) in urls || descriptor.linkedArtifacts().any { it.fileUrl() in urls } }
            }
        }
        val staged = linkedMapOf<String, LoadedModule>()
        val importedRoots = linkedSetOf<String>()
        var completed = false

        return object : ReloadTransaction {
            override suspend fun import(url: String): Any? = withContext(Dispatchers.IO) {
                synchronized(lock) {
                    ensureOpen()
                    check(!completed) { "reload transaction is already complete" }
                    val id = parsePluginId(url)
                    if (id == null) {
                        return@synchronized instantiateJvmClass(
                            Class.forName(url.removePrefix(CLASS_PREFIX), true, hostClassLoader),
                        )
                    }
                    load(id, staged, affected + id, linkedSetOf()).instance.also { importedRoots += id }
                }
            }

            override fun commit() = synchronized(lock) {
                ensureOpen()
                check(!completed) { "reload transaction is already complete" }
                install(staged)
                roots += importedRoots
                completed = true
                staged.clear()
                importedRoots.clear()
            }

            override fun rollback() = synchronized(lock) {
                if (completed) return@synchronized
                completed = true
                closeModules(staged.values)
                staged.clear()
                importedRoots.clear()
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        closeModules(active.values + retired)
        active.clear()
        roots.clear()
        retired.clear()
        descriptors.clear()
    }

    private fun load(
        id: String,
        staged: MutableMap<String, LoadedModule>,
        forceFresh: Set<String>,
        visiting: MutableSet<String>,
    ): LoadedModule {
        staged[id]?.let { return it }
        if (id !in forceFresh) active[id]?.let { return it }
        check(visiting.add(id)) { "cyclic JVM module dependency: ${(visiting + id).joinToString(" -> ")}" }

        try {
            val descriptor = requireDescriptor(id)
            verifyDescriptor(descriptor)
            val dependencies = descriptor.dependencies.map { dependency ->
                load(dependency, staged, forceFresh, visiting)
            }
            val classLoader = IsolatedJvmPluginClassLoader(
                descriptor.classLoaderArtifacts().map { it.file.toURI().toURL() }.toTypedArray(),
                descriptor.nativeLibraries.map { it.file },
                hostClassLoader,
                dependencies.map { it.classLoader },
                DEFAULT_SHARED_PACKAGES + descriptor.sharedHostPackages,
            )
            try {
                val instance = withThreadContextClassLoader(classLoader) {
                    instantiateJvmClass(Class.forName(descriptor.entryClass, true, classLoader))
                }
                require(instance is Plugin<*>) {
                    "JVM module ${descriptor.id} entry ${descriptor.entryClass} does not implement org.cordis.Plugin; " +
                        "the plugin must share the host Cordis API"
                }
                return LoadedModule(
                    JvmModuleHandle(descriptor, classLoader, instance, NEXT_GENERATION.getAndIncrement()),
                    dependencies,
                ).also { staged[id] = it }
            } catch (error: Throwable) {
                classLoader.close()
                throw error
            }
        } finally {
            visiting.remove(id)
        }
    }

    private fun install(staged: Map<String, LoadedModule>) {
        staged.forEach { (id, module) ->
            check(descriptors[id] == module.descriptor) {
                "JVM module $id changed while its load transaction was in progress"
            }
        }
        staged.forEach { (id, module) ->
            active.put(id, module)?.takeUnless { it === module }?.let(::retire)
        }
        pruneRetired()
    }

    private fun retire(module: LoadedModule) {
        if (retired.none { it === module }) retired += module
    }

    private fun pruneActive() {
        val retained = identitySet<LoadedModule>()
        fun visit(module: LoadedModule) {
            if (!retained.add(module)) return
            module.dependencies.forEach(::visit)
        }
        roots.mapNotNull(active::get).forEach(::visit)
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val (_, module) = iterator.next()
            if (module !in retained) {
                retire(module)
                iterator.remove()
            }
        }
    }

    private fun pruneRetired() {
        val reachable = identitySet<LoadedModule>()
        fun visit(module: LoadedModule) {
            if (!reachable.add(module)) return
            module.dependencies.forEach(::visit)
        }
        active.values.forEach(::visit)
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val module = iterator.next()
            if (module !in reachable) {
                module.close()
                iterator.remove()
            }
        }
    }

    private fun closeModules(modules: Collection<LoadedModule>) {
        val closedModules = identitySet<LoadedModule>()
        modules.forEach { module -> if (closedModules.add(module)) module.close() }
    }

    private fun isRetained(id: String): Boolean {
        val visited = identitySet<LoadedModule>()
        fun visit(module: LoadedModule): Boolean {
            if (!visited.add(module)) return false
            return module.descriptor.id == id || module.dependencies.any(::visit)
        }
        return active.values.any(::visit)
    }

    private fun requireDescriptor(id: String): JvmModuleDescriptor =
        descriptors[id] ?: throw IllegalArgumentException("JVM module $id is not registered")

    private fun verifyDescriptor(descriptor: JvmModuleDescriptor) {
        descriptor.linkedArtifacts().forEach { artifact ->
            val actual = jvmModuleSha256(artifact.file)
            require(actual.equals(artifact.expectedSha256, ignoreCase = true)) {
                "SHA-256 mismatch for JVM module ${descriptor.id} artifact ${artifact.file.name}: " +
                    "expected ${artifact.expectedSha256}, got $actual"
            }
        }
        verifier.verify(descriptor)
    }

    private fun ensureOpen() = check(!closed) { "JVM module loader is closed" }

    private data class LoadedModule(
        val handle: JvmModuleHandle,
        val dependencies: List<LoadedModule>,
    ) {
        val descriptor get() = handle.descriptor
        val classLoader get() = handle.classLoader
        val instance get() = handle.plugin

        fun close() {
            // Loader cleanup must not turn an already committed runtime replacement into a
            // partially failed transaction if the VM cannot release an individual JAR handle.
            runCatching { (classLoader as? AutoCloseable)?.close() }
        }
    }
}

private fun <T : Any> identitySet(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

private class IsolatedJvmPluginClassLoader(
    urls: Array<URL>,
    private val nativeLibraries: List<File>,
    private val hostClassLoader: ClassLoader,
    private val dependencyClassLoaders: List<ClassLoader>,
    private val sharedPackages: Set<String>,
) : URLClassLoader(urls, hostClassLoader.parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        findLoadedClass(name)?.let { return@synchronized it }

        if (sharedPackages.any { name == it || name.startsWith("$it.") }) {
            return@synchronized hostClassLoader.loadClass(name)
        }

        try {
            return@synchronized findClass(name).also { if (resolve) resolveClass(it) }
        } catch (_: ClassNotFoundException) {
            dependencyClassLoaders.forEach { dependency ->
                try {
                    return@synchronized dependency.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    // Continue through the explicitly declared dependency chain.
                }
            }
            return@synchronized super.loadClass(name, resolve)
        }
    }

    override fun getResource(name: String): URL? =
        findResource(name)
            ?: dependencyClassLoaders.firstNotNullOfOrNull { it.getResource(name) }
            ?: parent?.getResource(name)

    override fun getResources(name: String): java.util.Enumeration<URL> {
        val resources = linkedSetOf<URL>()
        findResources(name).toList().forEach(resources::add)
        dependencyClassLoaders.forEach { it.getResources(name).toList().forEach(resources::add) }
        parent?.getResources(name)?.toList()?.forEach(resources::add)
        return Collections.enumeration(resources)
    }

    override fun findLibrary(libname: String): String? {
        val filename = System.mapLibraryName(libname)
        return nativeLibraries.firstOrNull { it.name == filename }?.absolutePath
    }
}

private inline fun <T> withThreadContextClassLoader(classLoader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = classLoader
    return try {
        block()
    } finally {
        thread.contextClassLoader = previous
    }
}

private fun instantiateJvmClass(type: Class<*>): Any {
    return try {
        type.getField("INSTANCE").get(null)
    } catch (_: NoSuchFieldException) {
        type.getDeclaredConstructor().newInstance()
    }
}

private fun JvmModuleDescriptor.canonicalized() = copy(
    file = file.canonicalFile,
    expectedSha256 = expectedSha256.lowercase(),
    classpath = classpath.map(JvmModuleArtifact::canonicalized),
    nativeLibraries = nativeLibraries.map(JvmModuleArtifact::canonicalized),
)

private fun JvmModuleArtifact.canonicalized() = copy(
    file = file.canonicalFile,
    expectedSha256 = expectedSha256.lowercase(),
)

private fun JvmModuleDescriptor.classLoaderArtifacts(): List<JvmModuleArtifact> =
    listOf(JvmModuleArtifact(file, expectedSha256)) + classpath

private fun JvmModuleDescriptor.linkedArtifacts(): List<JvmModuleArtifact> =
    classLoaderArtifacts() + nativeLibraries

private fun JvmModuleArtifact.fileUrl(): String = file.toPath().toUri().toString()

private fun File.isInside(root: File): Boolean =
    canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) && canonicalFile != root.canonicalFile

private fun parsePluginId(value: String, strict: Boolean = true): String? {
    if (!value.startsWith(PLUGIN_PREFIX)) return null
    val id = value.removePrefix(PLUGIN_PREFIX)
    if (ID_PATTERN.matches(id)) return id
    if (strict) throw IllegalArgumentException("invalid JVM module specifier: $value")
    return null
}

internal fun validateJvmModuleDescriptor(descriptor: JvmModuleDescriptor, trustedRoot: File) {
    require(trustedRoot.canonicalFile.isDirectory) { "JVM module trusted root does not exist: $trustedRoot" }
    require(ID_PATTERN.matches(descriptor.id)) { "invalid JVM module id: ${descriptor.id}" }
    require(VERSION_PATTERN.matches(descriptor.version)) { "invalid JVM module version: ${descriptor.version}" }
    require(CLASS_NAME_PATTERN.matches(descriptor.entryClass)) {
        "invalid JVM module entry class: ${descriptor.entryClass}"
    }
    require(descriptor.dependencies.distinct().size == descriptor.dependencies.size) {
        "JVM module ${descriptor.id} contains duplicate dependencies"
    }
    descriptor.dependencies.forEach { dependency ->
        require(ID_PATTERN.matches(dependency)) { "invalid JVM module dependency id: $dependency" }
    }
    require(descriptor.id !in descriptor.dependencies) { "JVM module ${descriptor.id} depends on itself" }
    descriptor.sharedHostPackages.forEach { packageName ->
        require(PACKAGE_PATTERN.matches(packageName)) { "invalid shared host package: $packageName" }
    }

    val artifacts = descriptor.linkedArtifacts()
    require(artifacts.map { it.file }.distinct().size == artifacts.size) {
        "JVM module ${descriptor.id} contains duplicate artifacts"
    }
    artifacts.forEach { artifact ->
        require(SHA256_PATTERN.matches(artifact.expectedSha256)) {
            "expectedSha256 must contain exactly 64 hexadecimal characters: ${artifact.file.name}"
        }
        val canonicalFile = artifact.file.canonicalFile
        require(canonicalFile.isFile) { "JVM module artifact does not exist: $canonicalFile" }
        require(canonicalFile.isInside(trustedRoot)) {
            "JVM module artifact must be in trusted root ${trustedRoot.canonicalFile}: $canonicalFile"
        }
    }
}

internal fun jvmModuleSha256(file: File): String {
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
private const val PLUGIN_PREFIX = "jvm-plugin:"
private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
private val CLASS_NAME_PATTERN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
private val PACKAGE_PATTERN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")
private val DEFAULT_SHARED_PACKAGES = setOf(
    "java",
    "javax",
    "jdk",
    "sun",
    "com.sun",
    "kotlin",
    "kotlinx.coroutines",
    "kotlinx.atomicfu",
    "kotlinx.datetime",
    "org.cordis",
)
private val NEXT_GENERATION = AtomicLong(1)

internal actual fun platformModuleLoader(): ModuleLoader? = null
