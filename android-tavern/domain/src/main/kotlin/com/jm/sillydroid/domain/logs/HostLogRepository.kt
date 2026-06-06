package com.jm.sillydroid.domain.logs

import android.net.Uri
import com.jm.sillydroid.core.model.logs.HostLogBundleAttachment
import com.jm.sillydroid.core.model.logs.HostLogBundleExportResult
import com.jm.sillydroid.core.model.logs.HostLogBundleUploadRequestConfig
import com.jm.sillydroid.core.model.logs.HostLogBundleUploadResult
import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogExportOption
import com.jm.sillydroid.core.model.logs.HostLogSnapshot

interface HostLogRepository {
    fun initializeForAppStart()
    fun installCrashLogCapture()
    fun refreshApplicationExitInfoAsync()
    fun buildBundleFileName(): String
    fun listEntries(): List<HostLogEntry>
    fun listExportOptions(): List<HostLogExportOption>
    fun readPreferredSnapshot(preferTavernServerLog: Boolean, entries: List<HostLogEntry>? = null): HostLogSnapshot?
    fun readPreferredRealtimeSnapshot(preferTavernServerLog: Boolean, entries: List<HostLogEntry>? = null): HostLogSnapshot?
    fun readSnapshot(entry: HostLogEntry): HostLogSnapshot?
    fun readRealtimeSnapshot(entry: HostLogEntry): HostLogSnapshot?
    fun clearAllLogs()
    fun currentCrashAutoUploadKey(): String?
    fun exportToUri(targetUri: Uri, includedRelativePaths: Set<String>? = null): HostLogBundleExportResult
    fun exportToPublicDownloads(includedRelativePaths: Set<String>? = null): HostLogBundleExportResult
    suspend fun uploadBundle(
        config: HostLogBundleUploadRequestConfig,
        includedRelativePaths: Set<String>? = null
    ): HostLogBundleUploadResult
    suspend fun uploadCrashBundle(config: HostLogBundleUploadRequestConfig): HostLogBundleUploadResult
    suspend fun uploadFeedbackBundle(
        config: HostLogBundleUploadRequestConfig,
        feedbackText: String?,
        attachments: List<HostLogBundleAttachment> = emptyList()
    ): HostLogBundleUploadResult
    /**
     * 追加一行 WebView JS 报错/资源加载错误到当前 session 的 js-error 日志文件。
     * 传入的是完整一行（不含末尾换行），由实现用 [HostLogManager.AsyncWriter] 异步写盘。
     */
    fun recordWebViewJsError(line: String)
    /**
     * 追加一条宿主 release 诊断事件到当前 session 的 host-diagnostics 日志文件。
     * 调用方只传分类与正文，时间戳与单行格式由实现统一补齐，避免各组件自己拼接漂移。
     */
    fun recordHostDiagnostic(category: String, body: String)
    fun subscribeToLogChanges(
        matcher: (String?) -> Boolean = { path -> path == null || path.endsWith(".log", ignoreCase = true) },
        onChanged: () -> Unit
    ): AutoCloseable
}
