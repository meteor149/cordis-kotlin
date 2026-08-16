plugins {
    kotlin("multiplatform") version "2.1.21" apply false
    id("com.android.library") version "8.7.2" apply false
}

allprojects {
    group = "org.cordis"
    version = "4.0.0-rc.8-kotlin.1"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "com.android.library")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        jvmToolchain(21)
        jvm {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                javaParameters.set(true)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        androidTarget {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
        js(IR) {
            nodejs()
            binaries.library()
        }
        iosArm64()
        iosSimulatorArm64()
        macosArm64()
        applyDefaultHierarchyTemplate()

        sourceSets {
            commonTest.dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
            jvmTest {
                dependencies {
                    implementation(kotlin("test-junit5"))
                    implementation("org.junit.jupiter:junit-jupiter:5.12.2")
                }
            }
        }
    }

    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "org.cordis.${project.name.replace('-', '.')}"
        compileSdk = 35
        defaultConfig {
            minSdk = 26
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
    }
}

tasks.register("test") {
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:jvmTest" })
}
