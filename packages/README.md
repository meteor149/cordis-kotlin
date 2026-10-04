# Cordis plugin packages

Optional distribution metadata independent of Cordis core, application SDKs and execution
technology. The manifest declares `formatVersion: 1`. `PluginPackageManifest` describes a logical release and its artifact
variants. The common model does not require JVM classes, JAR/APK suffixes, Java, Android API
levels or iOS frameworks.

Each variant has `targets`, an opaque `artifact` path, a `runtime` descriptor and optional
host extensions. Runtime IDs are open identities: for example `jvm`, `android-dex`,
`native-library`, `wasm` or an application-defined script loader. `entryPoint` may be a class,
native symbol or script entry; only that loader interprets it. `minVersion` constrains the
advertised loader/runtime version, while `metadata` carries loader-specific inert data.
No declaration installs or implements a loader.

```json
{"id":"linux-native","artifact":"targets/linux/plugin.so",
 "runtime":{"id":"native-library","entryPoint":"kcode_plugin_init","minVersion":"1"},
 "targets":[{"system":"linux","arch":["x86"],"bits":[64],
   "minSystemVersion":"6.5","maxSystemVersion":"6.8.12",
   "distribution":{"id":"ubuntu","minVersion":"22.04","maxVersion":"24.04"},
   "requiredFeatures":["linux.io-uring"]}]}
```

Systems are Windows/macOS/Linux/Android/iOS (`windows`, `macos`, `linux`, `android`, `ios`).
Architecture families are `arm`/`x86`; `bits` defaults to 32 and 64. ARM64 requires `arm`
and 64 bits. Optional system and Linux distribution bounds are inclusive. Numeric versions
compare component by component, with missing components padded with zero (`22.04` equals
`22.4.0`). A short maximum is an exact numeric bound, not a wildcard. Inverted ranges fail
validation. Unknown versions cannot satisfy either bound. Linux kernel and distribution
release requirements are independent, and distribution IDs match exactly.

`requiredFeatures` lists open system feature IDs which the host must actually advertise;
OS versions do not establish feature availability or permission. `PackageHost` contains
actual system/process facts, optional Linux distribution, features and a map of available
runtime IDs to versions. A native host need not advertise or run Java.

Variants using the same runtime cannot overlap in system, family, bitness and version
ranges. Different distribution IDs or disjoint ranges can separate variants. Positive
feature requirements do not make ranges disjoint. A host supporting several runtime
alternatives must resolve its policy explicitly; selection still requires exactly one
matching variant. All metadata remains inert until a host loader accepts it.

```kotlin
implementation("io.github.meteor149:packages:0.0.1-SNAPSHOT")
```

`PluginPackageCodec` validates strict bounded UTF-8 JSON, unknown fields, duplicate keys,
SemVer, file paths, selectors and overlapping variants. `select(PackageHost, compatible)`
selects exactly one variant. The host callback checks its SDK/ABI requirements.
`resolvePackageGraph` orders exact-version package dependencies; these do not link class
loaders or satisfy Cordis service injection.

On JVM/Android, `PluginPackageArchive` inspects, packs and deploys ZIP `.kplugin` archives.
The root entry is `plugin.json`; all other entries are listed in `files` with size/hash.
Archives contain files only, use UTF-8 names and exclude encryption, ZIP64, symlinks, nested
plugin packages and duplicate/case-colliding or platform-unsafe paths. Host-provided limits
bound compressed/expanded sizes and entry counts. All variants are verified, not just the
selected one. The packer uses sorted entries and fixed timestamps.

```kotlin
val packages = PluginPackageArchive()
val release = packages.deploy(source, expectedArchiveSha256, appPrivateRoot)
val variant = release.manifest.select(host) { variant -> hostSdkAccepts(variant.extensions) }
val artifact = release.artifact(variant)
// The host dispatches the artifact and runtime descriptor to its own loader/manager.
```

Deployment copies the source to a private staging snapshot, verifies and extracts it, then
atomically publishes an immutable digest-named directory. Existing generations are reverified.
The application owns deployed files and must retain them while loaders/resources still use
them. `verifyDeployment` checks both the archive and extracted payload on restart.

The module does not download code, establish publisher trust, request permissions, persist
enable/configuration state, mutate a running plugin tree, or implement application install
transactions. Expected archive digests come from the caller's trusted release metadata or
explicit local import policy. A matching self-declared payload hash is not publisher trust.
Shared host SDK/framework classes must be excluded from artifacts. Native component routing
and class-loader transactions remain in `loader`; application compatibility and composition
remain with the host. JS/Apple can inspect metadata and resolve variants, but have no archive
deployment or native plugin loading added by this module.

## Build-tool entry point

From this repository run `:packages:packPlugin` with `-PpackageManifest=<plugin.json>`,
`-PpackagePayload=<directory>` and `-PpackageOutput=<output.kplugin>`, using
`--no-configuration-cache`. The manifest template lists payload paths; its valid placeholder
sizes/hashes are replaced from actual files. The packer verifies the resulting archive and
prints its SHA-256 for the trusted release channel. The JVM artifact also exposes
`org.cordis.packages.PluginPackageCliKt` with `pack` and `inspect` commands for external
Gradle/CLI integrations. Neither command executes plugin entry points.

Package IDs are case-sensitive dotted identities. Each segment accepts ASCII letters and
digits separated by single hyphens; case is preserved through codec and dependency lookup.
This permits existing Cordis mount identities such as `provider.llm.koog.OpenAI` without
renaming installations. IDs do not determine deployment directory names, which use archive
digests. Variant selectors retain their lowercase identifier grammar; archive path collision
checks remain independent of package identity.
