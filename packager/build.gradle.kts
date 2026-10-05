import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("plugin.sam.with.receiver")
    `java-gradle-plugin`
}

samWithReceiver { annotation("org.gradle.api.HasImplicitReceiver") }

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":packages"))
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
    compileOnly("com.android.tools.build:gradle:8.10.0")
    implementation("com.android.tools.build:manifest-merger:31.10.0")
    implementation("com.android.tools:common:31.10.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation(gradleTestKit())
}

// TestKit injects the plugin in a parent loader; optional platform APIs must be
// injected alongside it for tests, without becoming published runtime dependencies.
val platformTestPlugins = configurations.create("platformTestPlugins") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    platformTestPlugins("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
    platformTestPlugins("com.android.tools.build:gradle:8.10.0")
}
tasks.pluginUnderTestMetadata { pluginClasspath.from(platformTestPlugins) }

val androidDexToolForTests = configurations.create("androidDexToolForTests") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { androidDexToolForTests("com.android.tools:r8:8.13.19") }

gradlePlugin {
    plugins {
        create("cordisPackager") {
            id = "io.github.meteor149.cordis.packager"
            implementationClass = "org.cordis.packager.CordisPackagerPlugin"
            displayName = "Cordis plugin packager"
            description = "Build independent JVM/Android artifacts and runtime-neutral .kplugin releases"
        }
    }
}

val androidSdk = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.map { text ->
        Properties().apply { load(text.reader()) }.getProperty("sdk.dir", "")
    })

tasks.test {
    useJUnitPlatform()
    systemProperty("cordis.test.androidSdk", androidSdk.getOrElse(""))
    systemProperty("cordis.test.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
    // Nested builds run offline against the same Gradle cache.
    inputs.files(androidDexToolForTests)
}
