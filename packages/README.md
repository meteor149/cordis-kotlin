# Cordis plugin packages

Optional offline distribution layer, independent of Cordis core and any application SDK.
`PluginPackageManifest` describes one release with one or more desktop JAR / Android APK
variants. `extensions` at package/variant level carries host-defined inert metadata.

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
val jarOrApk = release.artifact(variant)
// Host builds JvmModuleDescriptor/AndroidModuleDescriptor and mounts through its manager.
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
Gradle/CLI integrations. Neither command executes plugin entry classes.
