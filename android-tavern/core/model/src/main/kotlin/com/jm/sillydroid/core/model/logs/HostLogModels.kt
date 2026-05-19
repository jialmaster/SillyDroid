package com.jm.sillydroid.core.model.logs

data class HostLogSnapshot(
    val fileName: String,
    val displayName: String,
    val updatedAt: String,
    val content: String
)

data class HostLogEntry(
    val fileName: String,
    val displayName: String,
    val updatedAt: String,
    val lastModified: Long
)

/**
 * 导出日志时给 UI 的分组视图模型。
 * 一个分组代表一种日志类型，内部会携带当前命中的相对路径列表，供导出时精确筛选 ZIP 内容。
 */
data class HostLogExportOption(
    val typeKey: String,
    val displayName: String,
    val relativePaths: Set<String>,
    val selectedByDefault: Boolean,
    val containsSensitiveContent: Boolean
)

enum class HostLogTailWindowProfile {
    FULL,
    COMPACT
}

data class HostLogBundleExportResult(
    val bundleFileName: String,
    val zipPath: String? = null,
    val logFileCount: Int = 0
)
