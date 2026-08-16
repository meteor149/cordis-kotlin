package org.cordis.loader

actual class ClasspathModuleLoader actual constructor() : ModuleLoader {
    actual override suspend fun import(specifier: String, parentUrl: String?): Any? {
        val className = specifier.removePrefix("class:")
        val type = Class.forName(className)
        val singleton = runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
        return singleton ?: type.getDeclaredConstructor().newInstance()
    }

    actual override fun resolve(specifier: String, parentUrl: String?): ResolveResult =
        ResolveResult(ModuleFormat.MODULE, specifier)
}

internal actual fun platformModuleLoader(): ModuleLoader? = null
