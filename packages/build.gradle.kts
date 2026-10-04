plugins {
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)
    }
}
