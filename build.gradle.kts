import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("multiplatform") version "2.1.21" apply false
    id("com.android.library") version "8.10.0" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

allprojects {
    group = "io.github.meteor149"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "com.android.library")
    apply(plugin = "com.vanniktech.maven.publish")

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
            publishLibraryVariants("release")
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
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    val publicationGroup = group.toString()
    val publicationArtifactId = name
    val publicationVersion = version.toString()

    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(publicationGroup, publicationArtifactId, publicationVersion)

        pom {
            name.set("Cordis Kotlin - $publicationArtifactId")
            description.set("A Kotlin Multiplatform implementation of Cordis.")
            inceptionYear.set("2021")
            url.set("https://github.com/meteor149/cordis-kotlin")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("meteor149")
                    name.set("meteor149")
                    url.set("https://github.com/meteor149")
                }
            }

            scm {
                url.set("https://github.com/meteor149/cordis-kotlin")
                connection.set("scm:git:https://github.com/meteor149/cordis-kotlin.git")
                developerConnection.set("scm:git:ssh://git@github.com/meteor149/cordis-kotlin.git")
            }
        }
    }
}

tasks.register("test") {
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:jvmTest" })
}
