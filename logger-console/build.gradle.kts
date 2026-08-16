kotlin { sourceSets.commonMain.dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
} }
