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

enum class HostLogTailWindowProfile {
    FULL,
    COMPACT
}

data class HostLogBundleExportResult(
    val bundleFileName: String,
    val zipPath: String? = null,
    val logFileCount: Int = 0
)
