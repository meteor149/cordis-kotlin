import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm("desktop") {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":plugin-api"))
            implementation("io.github.meteor149:core:0.0.1-SNAPSHOT")
            implementation("io.github.meteor149:loader:0.0.1-SNAPSHOT")
            implementation("io.github.meteor149:hmr:0.0.1-SNAPSHOT")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:5.12.2")
            }
        }
    }
}

android {
    namespace = "org.cordis.demo.composeapp"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "org.cordis.demo.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Cordis Plugin Lab"
            packageVersion = "2.0.0"
        }
    }
}

val desktopPluginProjects = mapOf(
    "palette" to ":plugins:desktop:palette",
    "forest" to ":plugins:desktop:forest",
    "forest-next" to ":plugins:desktop:forest-next",
    "forest-broken" to ":plugins:desktop:forest-broken",
    "ocean" to ":plugins:desktop:ocean",
    "sunset-support" to ":plugins:desktop:sunset-support",
    "sunset" to ":plugins:desktop:sunset",
)

val syncDesktopPluginResources = tasks.register<Sync>("syncDesktopPluginResources") {
    desktopPluginProjects.forEach { (assetName, projectPath) ->
        val jarTask = project(projectPath).tasks.named("jar")
        dependsOn(jarTask)
        from(jarTask) { rename { "$assetName.jar" } }
    }
    into(layout.buildDirectory.dir("generated/desktop-plugin-resources/plugins/desktop"))
}

kotlin.sourceSets.getByName("desktopMain").resources.srcDir(
    layout.buildDirectory.dir("generated/desktop-plugin-resources"),
)
tasks.matching { it.name == "desktopProcessResources" }.configureEach {
    dependsOn(syncDesktopPluginResources)
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
