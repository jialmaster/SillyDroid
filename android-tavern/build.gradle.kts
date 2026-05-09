plugins {
    id("com.android.application") version "9.2.0" apply false
}

val externalBuildRoot = providers.environmentVariable("SILLYDROID_TAVERN_ANDROID_BUILD_ROOT").orNull
    ?: providers.environmentVariable("SILLYDROID_ANDROID_BUILD_ROOT").orNull

if (!externalBuildRoot.isNullOrBlank()) {
    // 独立 tavern Android 工程也支持把 Gradle build 输出放到外部目录，避免 Windows/DrvFs 文件锁影响调试包产出。
    layout.buildDirectory.set(file("$externalBuildRoot/root"))

    subprojects {
        layout.buildDirectory.set(file("$externalBuildRoot/${project.name}"))
    }
}