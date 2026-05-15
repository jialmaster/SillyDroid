package com.jm.sillydroid.domain.logs

import android.net.Uri
import com.jm.sillydroid.core.model.logs.HostLogBundleExportResult
import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogSnapshot

interface HostLogRepository {
    fun initializeForAppStart()
    fun installCrashLogCapture()
    fun refreshApplicationExitInfoAsync()
    fun buildBundleFileName(): String
    fun listEntries(): List<HostLogEntry>
    fun readPreferredSnapshot(preferTavernServerLog: Boolean, entries: List<HostLogEntry>? = null): HostLogSnapshot?
    fun readPreferredRealtimeSnapshot(preferTavernServerLog: Boolean, entries: List<HostLogEntry>? = null): HostLogSnapshot?
    fun readSnapshot(entry: HostLogEntry): HostLogSnapshot?
    fun readRealtimeSnapshot(entry: HostLogEntry): HostLogSnapshot?
    fun clearAllLogs()
    fun exportToUri(targetUri: Uri): HostLogBundleExportResult
    fun exportToPublicDownloads(): HostLogBundleExportResult
    fun subscribeToLogChanges(
        matcher: (String?) -> Boolean = { path -> path == null || path.endsWith(".log", ignoreCase = true) },
        onChanged: () -> Unit
    ): AutoCloseable
}
