pluginManagement {
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "cordis-plugin-lab"

includeBuild("..")

include(":composeApp")
include(":android")
include(":plugin-api")

include(":plugins:android:palette")
include(":plugins:android:forest")
include(":plugins:android:forest-next")
include(":plugins:android:forest-broken")
include(":plugins:android:ocean")
include(":plugins:android:sunset")

include(":plugins:desktop:palette")
include(":plugins:desktop:forest")
include(":plugins:desktop:forest-next")
include(":plugins:desktop:forest-broken")
include(":plugins:desktop:ocean")
include(":plugins:desktop:sunset-support")
include(":plugins:desktop:sunset")
