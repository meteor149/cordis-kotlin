plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.cordis.demo.plugins.forest"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.cordis.demo.plugins.forest"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0-broken"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

// The host owns Kotlin/Cordis API classes; plugin APKs must not package a second copy.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly("io.github.meteor149:loader:0.0.1-SNAPSHOT")
}
