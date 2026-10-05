# Cordis Gradle packager

`packager` is a JVM Gradle plugin, separate from the Cordis runtime. Plugin ID:
`io.github.meteor149.cordis.packager`. It reuses `packages` for manifest validation and deterministic
`.kplugin` archives. It never loads plugin entry points or changes a running plugin tree.

Run Gradle on JDK 21. The platform adapters target Kotlin Multiplatform 2.1.21 and AGP
8.10.0 / Android Build Tools 35.0.0. Kotlin 2.3.21 is also exercised by a local composite
build for both targets. Android tests require SDK platform 35. Other toolchain
versions require their own compatibility verification. The generic archive tasks do not
require Kotlin or Android plugins to be applied.

## Maven snapshot

The implementation is published as `io.github.meteor149:packager:0.0.1-SNAPSHOT`.
The plugin marker is
`io.github.meteor149.cordis.packager:io.github.meteor149.cordis.packager.gradle.plugin:0.0.1-SNAPSHOT`.
Both use the project's existing Maven Central namespace.

```kotlin
// settings.gradle.kts in the consuming build
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts in the source module
plugins {
    id("io.github.meteor149.cordis.packager") version "0.0.1-SNAPSHOT"
}
```

The snapshot repository must be in `pluginManagement.repositories`, because Gradle
resolves the plugin marker and its implementation there. Google Maven supplies the
Android manifest tooling dependencies. This does not require Gradle Plugin Portal
publication or a local composite build.

### Publish

In GitHub Actions, run **Publish to Maven Central** with `publish_target` set to
`packager-snapshot`. It tests the packager and package format, then publishes the
implementation, marker, and the `packages` root/JVM dependencies. The default `all`
target and release events continue to publish the full project.

For the same publication from Windows, with the existing Maven Central credentials
and signing properties configured:

```powershell
.\gradlew.bat :packager:publishToMavenCentral :packages:publishKotlinMultiplatformPublicationToMavenCentralRepository :packages:publishJvmPublicationToMavenCentralRepository --no-configuration-cache
```

The workflow reuses `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
`SIGNING_KEY_ID`, `SIGNING_PASSWORD`, and `GPG_KEY_CONTENT` repository secrets.
Sources, Javadoc archives, POM metadata, and signatures use the same publishing
configuration as the Cordis libraries. The configured version is `0.0.1-SNAPSHOT`;
change it in the root build when preparing another version.

For local development, make Cordis available to plugin resolution:

```kotlin
// settings.gradle.kts in the consuming build
pluginManagement {
    includeBuild("../cordis-kotlin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

## A KMP module owns its releases

Apply the packager to the existing source module. No separate Android application wrapper
or second compilation of `commonMain` is required.

```kotlin
import org.cordis.packages.PackageTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("io.github.meteor149.cordis.packager")
}

kotlin {
    jvm("desktop")
    androidTarget()
}

android {
    namespace = "example.search"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

cordisPackages {
    releases {
        register("search") {
            packageId.set("feature.search")
            packageVersion.set("1.0.0")
            variants {
                register("desktop") {
                    jvmTarget.set("desktop")
                    entryPoint.set("example.search.DesktopSearchPlugin")
                    runtimeMinVersion.set("17")
                    target(PackageTarget("windows", listOf("arm", "x86")))
                    target(PackageTarget("macos", listOf("arm", "x86")))
                    target(PackageTarget("linux", listOf("arm", "x86")))
                }
                register("android") {
                    androidVariant.set("release")
                    entryPoint.set("example.search.AndroidSearchPlugin")
                    androidPackageName.set("example.search.plugin")
                    target(PackageTarget("android", listOf("arm", "x86"), minSystemVersion = "8"))
                }
            }
        }
    }
}
```

`packageSearch` produces `build/cordis/packages/feature.search-1.0.0.kplugin` and a
`.sha256` sidecar. `packagePlugins` builds every declared release. A module may declare
several releases with different entries, platform support, dependency exclusions and assets.
Each release has a distinct package ID; version defaults to the project's version.
Set `contentVersion` to `true` to append `+content.<sha256>` to the base version. The
digest covers the complete resolved manifest, payload checksums and file extensions,
including host ABI/configuration metadata. Rebuilding unchanged inputs keeps the same
identity. The declared output filename stays stable; catalog filenames use the resolved
immutable version. Use a base version without existing build metadata for this option.

Targets are explicit support declarations, not inferred from the list of KMP targets.
`runtimeMinVersion` is a runtime requirement (JVM version or Android API level).
The JVM adapter defaults it from the target's bytecode level and rejects private classes
requiring a newer Java version than declared. Override it when private dependencies require
a higher runtime or compilation options are configured directly on tasks.
`minSystemVersion` is the operating system release (for example Android `8`), not API `26`.
Native APKs are checked against the architecture/bitness advertised in the manifest.
Declaring an iOS target or another runtime does not create a corresponding loader.

## Private and host dependencies

The JVM adapter merges the target's compiled JAR and private runtime dependencies. It
preserves resources, merges `META-INF/services`, strips signatures and module descriptors,
rejects duplicate private classes/conflicting resources, and checks that the entry exists.
Multi-release dependencies fail by default. Set `ignoreMultiReleaseEntries` explicitly on a
variant to use only dependency base classes and discard `META-INF/versions` implementations.
Verify that those base classes support the selected runtime. Compiler-only `.kotlin_module`
metadata is omitted. Distinct license/notice/dependency notices are retained in deterministic
merged documents; private classes and ordinary resource conflicts still fail.

The Android library adapter consumes the existing AAR and private AAR/JAR dependencies.
It merges manifests, links a standalone resource table with SDK AAPT2, regenerates namespace
R classes, compiles DEX using a standalone D8 tool, and preserves Java resources, assets and JNI files.
It produces an unsigned APK for private dynamic loading, not an installed application.
The resource table is stored uncompressed and the APK is aligned with SDK zipalign.
D8 defaults to `com.android.tools:r8:8.13.19`, resolved from the consumer's Google Maven
repository, independently of AGP/SDK versions. This supports Kotlin 2.3 metadata according
to the [Android toolchain matrix](https://developer.android.com/build/kotlin-support).
Set `cordisPackages.androidD8Version` to select another version, or supply a custom tool
dependency through the `cordisD8` configuration. Tool JARs are tracked as task inputs.
Apply `io.github.meteor149.cordis.packager` to local Android library dependencies as well, even if they
declare no releases: each module publishes its complete AAR alongside AGP's intermediate
artifacts. External AAR dependencies already provide complete archives.
The module's own resources override dependency resources. Entry classes are not shrunk
by this adapter. An existing Android application module can instead use its AGP-built APK;
split APKs require an explicit custom artifact.

Prefer compile-only dependencies for host APIs. Where an existing KMP module also links
these APIs statically, declare `hostModules` exclusions: `group:module` for Maven artifacts
and `project::api` for a project at `:api`. These exclude the named component, not every
transitive dependency reachable from it. Declare all other host-provided components too.
The standard Kotlin/coroutines/atomicfu/datetime runtime components are excluded by default.

`sharedPackages` lists host-owned package prefixes; `sharedClasses` lists exact host class
names (including their nested classes). The Cordis loader's standard shared packages are
the defaults. These classes are removed from generated private artifacts. Extend these
rules to match the actual host SDK. SDK dependencies and SDK resources must also be excluded
through compile-only dependencies or `hostModules`; class filtering does not identify which
resources belong to an SDK. Private implementations must live outside shared namespaces.

Android namespace, minimum API and target API default to the library module's settings.
`androidManifest` optionally supplies a higher-priority application manifest. Library manifest
placeholders must already be resolved by AGP, except `applicationId`, which is set by the
packager. `excludedPayloadPaths` can omit selected assets/JNI directories from one release,
for example `assets/ubuntu` and `lib/arm64-v8a`, without changing the source module.

## Generic artifacts and host extensions

Instead of `jvmTarget` or `androidVariant`, set a variant's `artifact` to a file provider,
and declare `runtimeId`, `entryPoint`, targets, and optional `runtimeMetadata` JSON.
Task-backed file providers automatically carry their build dependencies. Custom runtimes
and prebuilt artifacts stay opaque to the archive layer.

Both releases and variants accept `manifestExtensions` JSON and an optional
`extensionsFile` provider. The file is tracked as a task input; duplicate keys are rejected.
An application SDK adapter can generate this file for ABI/configuration/capability metadata
without adding that application's types to Cordis. `payloadDirectory` adds explicit extra
files such as README/LICENSE. Payload collisions, symlinks and unsafe paths are rejected.
Use `dependency(id, exactVersion)` to declare logical package dependencies; Gradle library
dependencies and Cordis service injection are separate mechanisms.

## Bundles consume packages as Gradle artifacts

Each release publishes a consumable `cordis<Name>Elements` configuration. The bundle owns
its dependency selection, resource destination, and runtime composition configuration:

```kotlin
import org.cordis.packager.PluginCatalogTask

val bundledPlugins by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    bundledPlugins(project(path = ":plugins:search", configuration = "cordisSearchElements"))
}
tasks.register<PluginCatalogTask>("stageBundledPlugins") {
    archives.from(bundledPlugins)
    destinationDirectory.set(layout.buildDirectory.dir("bundled/plugins"))
}
```

The catalog verifies all archives, rejects duplicate package identities, stages immutable
release filenames and writes `index.json` with IDs, paths, SHA-256 digests and variants.
Unselected/stale files are removed from its owned output directory. It does not choose
default features, encode installation state, or generate Cordis mount configuration.

## Verification

```powershell
.\gradlew.bat :packager:test :packager:validatePlugins
```

TestKit covers archive checksums, host extension invalidation, configuration-cache reuse,
consumable package artifacts, catalog cleanup, private class/service handling, a loadable
KMP JVM artifact, and KMP Android library packaging with independent resources/assets and
private/host project dependencies. Android tests skip explicitly when SDK 35 is unavailable.
DEX/resource inspection is build evidence; it does not establish on-device loader behavior.
