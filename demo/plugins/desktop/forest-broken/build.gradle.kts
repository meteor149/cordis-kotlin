plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly("io.github.meteor149:loader:0.0.1-SNAPSHOT")
}
