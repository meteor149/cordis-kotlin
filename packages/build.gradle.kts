plugins {
    kotlin("plugin.serialization")
}

tasks.register<JavaExec>("packPlugin") {
    group = "distribution"
    description = "Build a .kplugin archive from -PpackageManifest, -PpackagePayload and -PpackageOutput"
    dependsOn("jvmMainClasses")
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath(compilation.output.allOutputs, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("org.cordis.packages.PluginPackageCliKt")
    doFirst {
        args("pack", project.providers.gradleProperty("packageManifest").get(), project.providers.gradleProperty("packagePayload").get(), project.providers.gradleProperty("packageOutput").get())
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)
    }
}
