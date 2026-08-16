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

- JVM
- Android (minSdk 26)

## Android dynamic loading

The Android loader runs Cordis plugins from APK, JAR, or dex files in the app's private storage.

- Plugin implementation classes use an isolated, child-first class loader.
- Android, Kotlin, coroutines, Cordis APIs, and explicitly allowed host APIs are shared by the host.
- Each plugin gets its own resources, assets, class loader, and package context.
- Plugin Activities and Services run through non-exported host proxy components provided by the AAR.
- Plugin releases can be replaced transactionally; a failed reload keeps the previous generation active.

The loader does not download plugin files or establish trust. The host must download, verify, and store them securely before registration. See [loader/README.md](loader/README.md) for integration details and limitations.

Licensed under [MIT](LICENSE).
