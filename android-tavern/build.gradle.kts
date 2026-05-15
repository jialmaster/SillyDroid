plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
}

val externalBuildRoot = providers.environmentVariable("SILLYDROID_TAVERN_ANDROID_BUILD_ROOT").orNull
    ?: providers.environmentVariable("SILLYDROID_ANDROID_BUILD_ROOT").orNull

if (!externalBuildRoot.isNullOrBlank()) {
    // 独立 tavern Android 工程也支持把 Gradle build 输出放到外部目录，避免 Windows/DrvFs 文件锁影响调试包产出。
    layout.buildDirectory.set(file("$externalBuildRoot/root"))

    subprojects {
        // 现在存在 :data:settings 与 :feature:settings 这类同名模块；必须按完整 project.path 分目录，
        // 否则不同模块会共享同一个 build 输出根并互相覆盖生成物，最终触发 Gradle 任务依赖校验失败。
        val uniqueBuildPath = project.path.removePrefix(":").replace(":", "/")
        layout.buildDirectory.set(file("$externalBuildRoot/$uniqueBuildPath"))
    }
}
