package com.jm.sillydroid.feature.main.ui.home.webview

internal const val TRIM_MEMORY_RUNNING_MODERATE_LEVEL = 5
internal const val TRIM_MEMORY_RUNNING_LOW_LEVEL = 10
internal const val TRIM_MEMORY_RUNNING_CRITICAL_LEVEL = 15
internal const val TRIM_MEMORY_UI_HIDDEN_LEVEL = 20
internal const val TRIM_MEMORY_BACKGROUND_LEVEL = 40
internal const val TRIM_MEMORY_MODERATE_LEVEL = 60
internal const val TRIM_MEMORY_COMPLETE_LEVEL = 80

internal fun shouldClearVolatileWebViewCacheForTrimMemory(level: Int): Boolean {
    // SillyTavern 是重前端页面，前台 RUNNING_LOW 就清 WebView 资源缓存容易造成插件资源反复重取和渲染抖动；
    // 前台只在 CRITICAL 清，后台/更深 LRU 压力再清。
    val isForegroundCritical = level >= TRIM_MEMORY_RUNNING_CRITICAL_LEVEL && level < TRIM_MEMORY_UI_HIDDEN_LEVEL
    val isBackgroundPressure = level >= TRIM_MEMORY_BACKGROUND_LEVEL
    return isForegroundCritical || isBackgroundPressure
}

internal fun shouldThrottleVolatileWebViewCacheClear(
    nowElapsedMs: Long,
    lastClearElapsedMs: Long,
    minIntervalMs: Long
): Boolean {
    return lastClearElapsedMs >= 0L && nowElapsedMs - lastClearElapsedMs < minIntervalMs
}
