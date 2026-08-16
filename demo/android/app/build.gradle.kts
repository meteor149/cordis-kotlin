import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.cordis.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.cordis.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(project(":theme-api"))
    implementation("io.github.meteor149:loader:0.0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

val pluginNames = listOf("forest", "ocean", "sunset")

fun registerPluginAssets(variant: String, apkPattern: String) = tasks.register<Sync>(
    "sync${variant.replaceFirstChar(Char::uppercase)}PluginAssets",
) {
    pluginNames.forEach { pluginName ->
        dependsOn(":plugins:$pluginName:assemble${variant.replaceFirstChar(Char::uppercase)}")
        from(rootProject.file("plugins/$pluginName/build/outputs/apk/$variant")) {
            include(apkPattern)
            rename { "$pluginName.apk" }
        }
    }
    into(layout.buildDirectory.dir("generated/plugin-assets/$variant/plugins"))
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
