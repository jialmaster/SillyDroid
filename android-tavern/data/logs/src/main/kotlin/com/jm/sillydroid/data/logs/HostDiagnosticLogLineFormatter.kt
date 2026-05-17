package com.jm.sillydroid.data.logs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * release 诊断日志按“一行一个事件”输出，便于用户直接导出后 grep，
 * 也避免宿主/UI 组件各自拼接时间戳与换行规则，导致格式漂移。
 */
internal object HostDiagnosticLogLineFormatter {
    fun buildLine(
        category: String,
        body: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val safeCategory = category.trim().ifBlank { "unknown" }
        val safeBody = body
            .replace("\r\n", "\n")
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .joinToString(separator = " ")
            .ifBlank { "-" }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(nowMillis))
        return "[$timestamp] [$safeCategory] $safeBody\n"
    }
}
