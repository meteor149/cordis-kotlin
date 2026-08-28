kotlin { sourceSets.commonMain.dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
} }
