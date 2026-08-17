# Cordis Kotlin

A Kotlin implementation of [Cordis](https://github.com/cordiverse/cordis).

> The project is under active development. APIs may change without notice.

## Install

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation("io.github.meteor149:loader:0.0.1-SNAPSHOT")
}
```

Plugin APKs should compile against the API without packaging it:

```kotlin
compileOnly("io.github.meteor149:loader:0.0.1-SNAPSHOT")
```

## Platforms

Currently supported:

- Desktop JVM
- Android (minSdk 26)

## Desktop JVM dynamic loading

The desktop loader runs Cordis plugins from verified JARs in an application-controlled directory.

- Plugin code, resources, private JAR dependencies, and JNI libraries are isolated per generation.
- JDK, Kotlin, Cordis APIs, and explicitly allowed host API packages preserve host type identity.
- Plugin module dependencies form an explicit class-loader graph; arbitrary host implementation
  packages are not visible.
- Plugin releases can be replaced transactionally through the HMR service. Failed imports or
  plugin activation roll back to the previous running generation.
- Superseded and rolled-back class loaders are closed when active dependents no longer retain them.

The host remains responsible for downloading releases and establishing trust before registration.
See [loader/README.md](loader/README.md#desktop-jvm-plugin-jars) for setup, packaging, and lifecycle
details.

## Android dynamic loading

The Android loader runs Cordis plugins from APK, JAR, or dex files in the app's private storage.

- Plugin implementation classes use an isolated, child-first class loader.
- Android, Kotlin, coroutines, Cordis APIs, and explicitly allowed host APIs are shared by the host.
- Each plugin gets its own resources, assets, class loader, and package context.
- Plugin Activities and Services run through non-exported host proxy components provided by the AAR.
- Plugin releases can be replaced transactionally; a failed reload keeps the previous generation active.

The loader does not download plugin files or establish trust. The host must download, verify, and store them securely before registration. See [loader/README.md](loader/README.md) for integration details and limitations.

## Compose Multiplatform plugin lab

The [demo](demo/README.md) is a shared Compose Multiplatform application for Desktop JVM and
Android. It builds isolated plugin JARs/APKs, exercises declared dependencies and private
classpaths, performs transactional hot replacement, proves failed-release rollback, and exposes
Android proxy Activity routing from the same host UI.

Licensed under [MIT](LICENSE).
