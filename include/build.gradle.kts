kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core"))
        api(project(":loader"))
        implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        implementation("com.charleskorn.kaml:kaml:0.78.0")
    }
}
