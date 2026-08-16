# AndroidModuleLoader delegates its default shared package boundary to the host class loader.
# Separately-built plugin bytecode references these binary names, so R8 must not rename them.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.cordis.** { *; }

# These types form the component boundary called by separately-built plugin APKs.
-keep public class org.cordis.loader.CordisPluginActivity { public protected *; }
-keep public class org.cordis.loader.CordisPluginService { public protected *; }

# Android instantiates these containers from the merged host manifest.
-keep class org.cordis.loader.CordisProxyActivity { *; }
-keep class org.cordis.loader.CordisProxyService { *; }
