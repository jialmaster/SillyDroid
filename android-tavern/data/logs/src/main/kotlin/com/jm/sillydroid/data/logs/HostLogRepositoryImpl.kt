package com.jm.sillydroid.data.logs

import android.content.Context
import android.net.Uri
import com.jm.sillydroid.core.model.logs.HostLogBundleExportResult
import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogSnapshot
import com.jm.sillydroid.domain.logs.HostLogRepository

class HostLogRepositoryImpl(context: Context) : HostLogRepository {
    private val appContext = context.applicationContext

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

    override fun exportToUri(targetUri: Uri): HostLogBundleExportResult {
        return HostLogManager.exportToUri(appContext, targetUri)
    }

    override fun exportToPublicDownloads(): HostLogBundleExportResult {
        return HostLogManager.exportToPublicDownloads(appContext)
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
}
