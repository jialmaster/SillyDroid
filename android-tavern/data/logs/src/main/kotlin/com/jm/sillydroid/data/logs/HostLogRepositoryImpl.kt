package com.jm.sillydroid.data.logs

import android.content.Context
import android.net.Uri
import com.jm.sillydroid.core.model.logs.HostLogBundleExportResult
import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogExportOption
import com.jm.sillydroid.core.model.logs.HostLogSnapshot
import com.jm.sillydroid.domain.logs.HostLogRepository
import java.util.concurrent.atomic.AtomicLong

class HostLogRepositoryImpl(context: Context) : HostLogRepository {
    private val appContext = context.applicationContext

    // 诊断/JS 错误都属于“当前 app session 的附属日志”。
    // 统一复用同一套 AsyncWriter 初始化与 session token 逻辑，避免不同日志类型各自维护一套易漂移。
    private val jsErrorWriter = SessionScopedAsyncLogWriter {
        HostLogManager.currentJsErrorLogFile(appContext)
    }
    private val hostDiagnosticsWriter = SessionScopedAsyncLogWriter {
        HostLogManager.currentHostDiagnosticsLogFile(appContext)
    }

    override fun initializeForAppStart() {
        HostLogManager.initializeForAppStart(appContext)
    }

    override fun installCrashLogCapture() {
        CrashLogStore.install(appContext)
    }

    override fun refreshApplicationExitInfoAsync() {
        ApplicationExitInfoLogStore.refreshAsync(appContext)
    }

    override fun buildBundleFileName(): String {
        return HostLogManager.buildBundleFileName()
    }

    override fun listEntries(): List<HostLogEntry> {
        return HostLogManager.listEntries(appContext)
    }

    override fun listExportOptions(): List<HostLogExportOption> {
        return HostLogManager.listExportOptions(appContext)
    }

    override fun readPreferredSnapshot(
        preferTavernServerLog: Boolean,
        entries: List<HostLogEntry>?
    ): HostLogSnapshot? {
        return HostLogManager.readPreferredSnapshot(
            context = appContext,
            preferTavernServerLog = preferTavernServerLog,
            entries = entries
        )
    }

    override fun readPreferredRealtimeSnapshot(
        preferTavernServerLog: Boolean,
        entries: List<HostLogEntry>?
    ): HostLogSnapshot? {
        return HostLogManager.readPreferredRealtimeSnapshot(
            context = appContext,
            preferTavernServerLog = preferTavernServerLog,
            entries = entries
        )
    }

    override fun readSnapshot(entry: HostLogEntry): HostLogSnapshot? {
        return HostLogManager.readSnapshot(appContext, entry)
    }

    override fun readRealtimeSnapshot(entry: HostLogEntry): HostLogSnapshot? {
        return HostLogManager.readRealtimeSnapshot(appContext, entry)
    }

    override fun clearAllLogs() {
        HostLogManager.clearAllLogs(appContext)
    }

    override fun exportToUri(targetUri: Uri, includedRelativePaths: Set<String>?): HostLogBundleExportResult {
        return HostLogManager.exportToUri(appContext, targetUri, includedRelativePaths = includedRelativePaths)
    }

    override fun exportToPublicDownloads(includedRelativePaths: Set<String>?): HostLogBundleExportResult {
        return HostLogManager.exportToPublicDownloads(appContext, includedRelativePaths = includedRelativePaths)
    }

    override fun recordWebViewJsError(line: String) {
        jsErrorWriter.append(line)
    }

    override fun recordHostDiagnostic(category: String, body: String) {
        hostDiagnosticsWriter.append(
            HostDiagnosticLogLineFormatter.buildLine(category = category, body = body)
        )
    }

    override fun subscribeToLogChanges(
        matcher: (String?) -> Boolean,
        onChanged: () -> Unit
    ): AutoCloseable {
        return HostLogManager.subscribeToLogChanges(
            context = appContext,
            matcher = matcher,
            onChanged = onChanged
        )
    }

    private class SessionScopedAsyncLogWriter(
        private val logFileProvider: () -> java.io.File
    ) {
        private val sessionToken = AtomicLong(0L)
        private val writer by lazy {
            HostLogManager.AsyncWriter(logFileProvider).also { asyncWriter ->
                val token = sessionToken.incrementAndGet()
                val logFile = logFileProvider()
                if (!logFile.exists()) {
                    asyncWriter.reset(token)
                } else {
                    val previous = runCatching { logFile.readText() }.getOrDefault("")
                    asyncWriter.reset(token)
                    if (previous.isNotEmpty()) {
                        asyncWriter.append(token, previous)
                    }
                }
            }
        }

        fun append(line: String) {
            val token = sessionToken.get().takeIf { it > 0L } ?: run {
                writer
                sessionToken.get()
            }
            writer.append(token, line)
        }
    }
}
