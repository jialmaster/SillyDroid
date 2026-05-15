import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        // 当前环境里 maven.google.com / google() 对真实 artifact 会跳转到 dl.google.com，
        // 而非浏览器 TLS 链路对 dl.google.com 握手不稳定；这里先显式尝试 dl-ssl，再保留官方默认仓库顺序。
        maven(url = "https://dl-ssl.google.com/android/maven2")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 依赖解析与插件解析保持同一组 Google Maven 入口，避免不同阶段命中不同域名导致构建行为分叉。
        maven(url = "https://dl-ssl.google.com/android/maven2")
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
