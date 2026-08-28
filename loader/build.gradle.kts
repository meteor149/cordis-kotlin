kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core"))
        implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
    }
    sourceSets.androidInstrumentedTest.dependencies {
        implementation("androidx.test:runner:1.6.2")
        implementation("androidx.test.ext:junit:1.2.1")
    }
}

extensions.configure<com.android.build.gradle.LibraryExtension> {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}
