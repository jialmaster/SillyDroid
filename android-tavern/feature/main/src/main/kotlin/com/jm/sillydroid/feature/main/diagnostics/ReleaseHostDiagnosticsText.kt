package com.jm.sillydroid.feature.main.diagnostics

import android.content.ComponentCallbacks2

/**
 * release 诊断日志会跨机型、跨版本长期导出流转，
 * 这里把常见系统枚举和可空文本统一格式化成稳定、可 grep 的单行文本，
 * 避免各调用点各自拼接，导致字段名称和含义漂移。
 */
@Suppress("DEPRECATION")
fun formatTrimMemoryLevel(level: Int): String {
    return when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
        else -> "UNKNOWN"
    }
}

fun normalizeDiagnosticValue(value: String?): String {
    return value.orEmpty()
        .replace("\r\n", "\n")
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .joinToString(separator = " ")
        .ifBlank { "-" }
}
