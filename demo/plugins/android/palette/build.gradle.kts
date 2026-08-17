plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.cordis.demo.plugins.palette"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.cordis.demo.plugins.palette"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly("io.github.meteor149:loader:0.0.1-SNAPSHOT")
}
