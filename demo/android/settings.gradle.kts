pluginManagement {
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
}

rootProject.name = "cordis-android-demo"

include(":app")
include(":theme-api")
include(":plugins:forest")
include(":plugins:ocean")
include(":plugins:sunset")
