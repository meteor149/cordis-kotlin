kotlin { sourceSets.commonMain.dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
} }
