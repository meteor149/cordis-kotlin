# Cordis Android Timer Demo

This standalone Android composite build uses a timer to demonstrate the Android dynamic plugin support in `cordis-kotlin`. The host application only owns the theme API. The forest, ocean, and sunset themes are built as separate APKs and loaded in isolation at runtime by `AndroidModuleLoader`.

## Modules

- `app`: Timer host, plugin catalog, and Activity proxy entry point.
- `theme-api`: The shared `TimerTheme` / `TimerThemeSink` contract used by the host and plugins.
- `plugins:forest`: Tranquil Forest theme and its standalone resource preview page.
- `plugins:ocean`: Deep Ocean theme and its standalone resource preview page.
- `plugins:sunset`: Sunset Glow theme and its standalone resource preview page.

Building `app` first builds all three plugin APKs and then synchronizes them into the host's `assets/plugins` directory. At startup, the application atomically copies each APK into private storage, makes it read-only, calculates its SHA-256 digest, and registers it with `AndroidModuleLoader`. Selecting a theme card replaces the active Cordis entry. Selecting **Open plugin resource page** displays the plugin's own layout and drawables through `CordisProxyActivity`.

Plugin implementations use the `dev.cordis.demo.plugins.*` namespace. The Loader treats `org.cordis.*` as a host-shared API boundary by default, so plugin implementation classes must not use that prefix. The `org.cordis.demo.api` package is explicitly shared through `sharedHostPackages`, preserving type identity for the service contract.

Release builds must keep this binary boundary stable. The host keeps the default shared packages and the custom `theme-api`; each plugin keeps its entry point and component implementations and excludes the host-provided Kotlin standard library from its runtime classpath. The ProGuard and Gradle configuration in this demo can be used as a template for additional plugin modules.

## Build and run

Run these commands from the repository root:

```powershell
.\gradlew.bat -p demo\android :app:assembleDebug
.\gradlew.bat -p demo\android :app:installDebug
```

The demo requires Android SDK 35, JDK 17 or newer, and a device running Android 8.0 (API 26) or newer.

You can also open `demo/android` directly in Android Studio. Its `settings.gradle.kts` uses `includeBuild("../..")` to consume the Cordis `loader` from the current workspace, so publishing to a local Maven repository is not required.
