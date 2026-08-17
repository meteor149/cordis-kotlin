# Cordis Loader

## Desktop JVM plugin JARs

`JvmModuleLoader` loads independently built Cordis plugins from JAR files already installed below
an application-controlled trusted directory. The host registers trusted release metadata, then
uses the `jvm-plugin:<id>` specifier in a normal Cordis loader entry:

```kotlin
val pluginRoot = appData.resolve("plugins").toFile()
val modules = JvmModuleLoader(pluginRoot)
modules.register(
    JvmModuleDescriptor(
        id = "example",
        version = "1.0.0",
        entryClass = "com.example.plugin.ExamplePlugin",
        file = pluginRoot.resolve("example/1.0.0/plugin.jar"),
        expectedSha256 = trustedReleaseMetadata.sha256,
        dependencies = listOf("shared-feature"),
        sharedHostPackages = setOf("com.example.host.api"),
    ),
)
loader.internal = modules
loader.create(EntryOptions(name = modules.moduleUrl("example")))
```

The entry class must be a Kotlin `object` or have a public no-argument constructor, and it must
implement the host's `org.cordis.Plugin`. Build plugin JARs against the API without packaging a
second Cordis runtime:

```kotlin
dependencies {
    compileOnly("io.github.meteor149:loader:<version>")
}
```

### Isolation and dependencies

Plugin classes and resources load child-first. JDK, Kotlin, coroutines, atomicfu, datetime, and
Cordis packages load from the host. Add only stable API packages to `sharedHostPackages`; all other
host application packages are hidden from plugin code. Types crossing the host/plugin boundary,
including Compose UI contracts, must be owned by one of these shared packages.

There are two dependency mechanisms:

- `dependencies` names other registered Cordis modules. Their class loaders are searched after the
  plugin's own classes, and HMR tracks the module graph transitively.
- `classpath` contains private, checksummed JARs owned by one plugin release. These JARs share that
  plugin's class loader and are also watched as reload inputs.

Exact checksummed JNI files can be supplied through `nativeLibraries`. A plugin can resolve them
with `System.loadLibrary`; native code is still subject to the JVM and operating system's normal
unloading limitations.

```kotlin
JvmModuleDescriptor(
    id = "example",
    version = "1.0.0",
    entryClass = "com.example.plugin.ExamplePlugin",
    file = pluginJar,
    expectedSha256 = pluginSha256,
    classpath = listOf(JvmModuleArtifact(privateLibrary, privateLibrarySha256)),
    nativeLibraries = listOf(JvmModuleArtifact(nativeLibrary, nativeLibrarySha256)),
)
```

Every artifact is canonicalized, required to remain below `pluginRoot`, and rechecked against its
SHA-256 digest on registration and before every load. `JvmModuleVerifier` is an additional hook for
publisher signatures, certificates, or host release policy. Downloading and signature trust remain
the host's responsibility; use immutable, versioned release directories rather than overwriting a
running JAR in place.

### Transactional reload and release

Registering a new descriptor with the same id stages metadata without changing the active plugin.
The HMR service watches every descriptor artifact and performs the runtime replacement and loader
commit as one transaction:

```kotlin
modules.register(nextDescriptor)
hmr.stash(nextDescriptor.file.toPath().toUri().toString())
```

If importing the new generation or applying its Cordis plugin fails, HMR rolls back the staged
class loaders and recreates the previous fibers. A successful commit closes superseded loaders as
soon as no active dependent references them. This releases JAR file handles, but classes become
garbage-collectable only after plugin code has also released threads, callbacks, UI nodes, and
other strong references. Put those cleanups in Cordis effects.

When an entry is permanently removed, dispose its Cordis fiber first, then release and unregister
the module. `release` also closes dependencies that are no longer reachable from another directly
imported module. Stop HMR before closing the loader during application shutdown:

```kotlin
modules.release("example")
modules.unregister("example")

hmr.stop()
modules.close()
```

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
