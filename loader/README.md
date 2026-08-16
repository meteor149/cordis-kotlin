# Cordis Loader

## Android plugin APKs

`AndroidModuleLoader` loads Cordis plugins from APK, JAR, or raw dex containers already installed
in the application's private data directory. Its optional component runtime follows Shadow's
class-loader, host-container, lifecycle-delegate, and plugin-resource separation.

Each plugin release is described by trusted host metadata:

```kotlin
val modules = AndroidModuleLoader(applicationContext)
modules.register(
    AndroidModuleDescriptor(
        id = "example",
        version = "1.0.0",
        entryClass = "com.example.ExamplePlugin",
        file = File(applicationContext.filesDir, "plugins/example/1.0.0/plugin.apk"),
        expectedSha256 = trustedReleaseMetadata.sha256,
        dependencies = listOf("shared-api"),
        sharedHostPackages = setOf("com.example.host.api"),
        packageName = "com.example.plugin",
        activities = mapOf("com.example.plugin.MainActivity" to 0),
        services = setOf("com.example.plugin.SyncService"),
    ),
)
loader.internal = modules

loader.create(EntryOptions(name = "android-plugin:example"))

// Install once in the host Application, after constructing the loader.
val components = AndroidPluginComponents.install(modules)
components.startActivity(this, "example", "com.example.plugin.MainActivity")
```

The entry class must be a Kotlin `object` or have a public no-argument constructor, and it must
implement the host's `org.cordis.Plugin`. Plugin builds must compile against, but must not package,
their own copy of the Cordis API. If the plugin build uses R8, keep the configured entry class:

```proguard
-keep class com.example.ExamplePlugin { *; }
```

Class loading is child-first for plugin implementation code. JDK, Android, Kotlin, coroutines, and
Cordis API packages are shared from the host. Add only stable API packages to `sharedHostPackages`;
host implementation packages remain hidden. Dependency plugin class loaders are consulted after
the plugin's own classes.

Registering another descriptor with the same id stages new release metadata without replacing the
running instance. The HMR service can reload it transactionally:

```kotlin
modules.register(nextDescriptor)
hmr.stash(modules.moduleUrl(nextDescriptor.id))
```

The new class loader becomes active only after all replacement plugin imports succeed. Rollback
retains the old loader and plugin instance. Component routes carry an internal generation token:
already-created Activities and Services keep using their old class loader, while new host launches
use the committed generation. Call `modules.release(id)` only after old plugin components have been
destroyed; it invalidates every retained generation for that module id.

### Activity, Service, and resources

The AAR contributes two non-exported host containers, `CordisProxyActivity` and
`CordisProxyService`. The descriptor is the component allowlist; plugin components do not need to
be installed or registered in the host manifest.

Plugin Activities extend the explicit Cordis delegate rather than `android.app.Activity`:

```kotlin
class MainActivity : CordisPluginActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.main)
        findViewById<View>(R.id.close)?.setOnClickListener { finish() }
    }
}
```

Plugin Services use the corresponding delegate. Started and bound services are multiplexed by the
host container while keeping independent lifecycle state:

```kotlin
class SyncService : CordisPluginService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent): IBinder = SyncBinder()
}

components.startService(context, "example", SyncService::class.java.name)
components.bindService(
    context,
    "example",
    SyncService::class.java.name,
    connection = connection,
    flags = Context.BIND_AUTO_CREATE,
)
```

`AndroidPluginContext` supplies the plugin APK's `AssetManager`, `Resources`, class loader, package
identity, code path, and a cloned `LayoutInflater`. Explicit intents targeting classes in the
descriptor are automatically rewritten to the host containers. Resource tables stay isolated, so
a normal plugin APK package id works; plugin layouts must not directly reference private host
resources. Share stable host APIs through `sharedHostPackages` instead.

The plugin APK should compile against the loader API without packaging it again. With a conventional
Android plugin project this means `compileOnly("io.github.meteor149:loader:<version>")`. Keep the
component constructors and Cordis entry when shrinking:

```proguard
-keep class com.example.ExamplePlugin { *; }
-keep public class * extends org.cordis.loader.CordisPluginActivity { public <init>(); }
-keep public class * extends org.cordis.loader.CordisPluginService { public <init>(); }
```

This runtime deliberately uses an explicit component base class instead of Shadow's legacy Gradle
bytecode transform, which is not compatible with the project's AGP 8 pipeline. It currently covers
framework Activity lifecycle, started/bound in-process Services, plugin resources and nested
component routing; it does not emulate every Activity API, remote-process Services, providers, or
broadcast receivers.

`AndroidModuleLoader` does not download files or establish remote trust by itself. The host should
download through its own release manager, verify signed metadata, copy to immutable app-private
storage, and then call `register`. Google Play distribution must also comply with its restrictions
on downloading executable code.
