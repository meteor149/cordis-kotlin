import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.cordis.demo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.cordis.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/plugin-assets/debug"))
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("generated/plugin-assets/release"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

dependencies {
    implementation(project(":composeApp"))
    implementation("androidx.activity:activity-compose:1.10.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}

val pluginProjects = mapOf(
    "palette" to ":plugins:android:palette",
    "forest" to ":plugins:android:forest",
    "forest-next" to ":plugins:android:forest-next",
    "forest-broken" to ":plugins:android:forest-broken",
    "ocean" to ":plugins:android:ocean",
    "sunset" to ":plugins:android:sunset",
)

fun registerPluginAssets(variant: String, apkPattern: String) = tasks.register<Sync>(
    "sync${variant.replaceFirstChar(Char::uppercase)}PluginAssets",
) {
    pluginProjects.forEach { (assetName, projectPath) ->
        dependsOn("$projectPath:assemble${variant.replaceFirstChar(Char::uppercase)}")
        from(project(projectPath).layout.buildDirectory.dir("outputs/apk/$variant")) {
            include(apkPattern)
            rename { "$assetName.apk" }
        }
    }
    into(layout.buildDirectory.dir("generated/plugin-assets/$variant/plugins/android"))
}

val syncDebugPluginAssets = registerPluginAssets("debug", "*-debug.apk")
val syncReleasePluginAssets = registerPluginAssets("release", "*-release-unsigned.apk")

tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(syncDebugPluginAssets) }
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { dependsOn(syncReleasePluginAssets) }
tasks.matching { it.name.contains("Debug") && it.name.contains("Lint", ignoreCase = true) }.configureEach {
    dependsOn(syncDebugPluginAssets)
}
tasks.matching { it.name.contains("Release") && it.name.contains("Lint", ignoreCase = true) }.configureEach {
    dependsOn(syncReleasePluginAssets)
}
