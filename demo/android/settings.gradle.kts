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
        mavenCentral()
    }
}

rootProject.name = "cordis-android-demo"

include(":app")
include(":theme-api")
include(":plugins:forest")
include(":plugins:ocean")
include(":plugins:sunset")

includeBuild("../..")
