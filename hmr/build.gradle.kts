kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core"))
        api(project(":loader"))
        api(project(":timer"))
        implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
    }
}
