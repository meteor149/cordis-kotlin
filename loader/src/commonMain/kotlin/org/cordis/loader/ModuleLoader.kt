package org.cordis.loader

enum class ModuleFormat { BUILTIN, COMMONJS, JSON, MODULE, WASM }
data class ResolveResult(val format: ModuleFormat, val url: String)

/** Platform module resolver. Dynamic loading implementations are supplied per target. */
interface ModuleLoader {
    suspend fun import(specifier: String, parentUrl: String?): Any?
    fun resolve(specifier: String, parentUrl: String?): ResolveResult
    fun contains(url: String): Boolean = false
    fun linked(url: String): List<String> = emptyList()
    /** Framework/main-entry dependencies whose changes require process reload. */
    fun externals(): Set<String> = emptySet()
    fun peek(url: String): Any? = null
    fun beginReload(urls: Set<String>): ReloadTransaction = object : ReloadTransaction {
        override suspend fun import(url: String): Any? = this@ModuleLoader.import(url, null)
        override fun commit() = Unit
        override fun rollback() = Unit
    }

    companion object {
        fun fromInternal(): ModuleLoader? = platformModuleLoader()
    }
}

interface ReloadTransaction {
    suspend fun import(url: String): Any?
    fun commit()
    fun rollback()
}

interface RefreshableEntryTree {
    val filename: String
    suspend fun refresh()
}

expect class ClasspathModuleLoader() : ModuleLoader {
    override suspend fun import(specifier: String, parentUrl: String?): Any?
    override fun resolve(specifier: String, parentUrl: String?): ResolveResult
}

internal expect fun platformModuleLoader(): ModuleLoader?
