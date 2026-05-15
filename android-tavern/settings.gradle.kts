pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SillyDroid"
include(":app")
include(":core:common")
include(":core:model")
include(":core:ui")
include(":domain")
include(":data:runtime")
include(":data:settings")
include(":data:logs")
include(":data:extensions")
include(":data:update")
include(":feature:main")
include(":feature:settings")
include(":ui:update")
