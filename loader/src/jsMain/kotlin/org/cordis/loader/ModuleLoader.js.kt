package org.cordis.loader

actual class ClasspathModuleLoader actual constructor() : ModuleLoader {
    actual override suspend fun import(specifier: String, parentUrl: String?): Any? =
        throw UnsupportedOperationException(
            "No JavaScript ModuleLoader is configured for $specifier; assign Loader.internal before loading entries",
        )

    actual override fun resolve(specifier: String, parentUrl: String?): ResolveResult =
        ResolveResult(ModuleFormat.MODULE, specifier)
}

internal actual fun platformModuleLoader(): ModuleLoader? = null
