package com.jm.sillydroid.data.logs

import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogSnapshot
import com.jm.sillydroid.core.model.logs.HostLogTailWindowProfile

@Deprecated("Use HostLogManager directly.", ReplaceWith("HostLogManager"))
object HostLogReader {
    fun listEntries(context: android.content.Context): List<HostLogEntry> {
        return HostLogManager.listEntries(context)
    }

    fun readPreferredSnapshot(
        context: android.content.Context,
        preferTavernServerLog: Boolean,
        maxChars: Int = 320_000,
        maxBytes: Int = 768 * 1024,
        maxLines: Int = 6_000,
        tailWindowProfile: HostLogTailWindowProfile = HostLogTailWindowProfile.FULL,
        entries: List<HostLogEntry>? = null
    ): HostLogSnapshot? {
        return HostLogManager.readPreferredSnapshot(
            context = context,
            preferTavernServerLog = preferTavernServerLog,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            tailWindowProfile = tailWindowProfile,
            entries = entries
        )
    }

    fun readSnapshot(
        context: android.content.Context,
        entry: HostLogEntry,
        maxChars: Int = 320_000,
        maxBytes: Int = 768 * 1024,
        maxLines: Int = 6_000,
        tailWindowProfile: HostLogTailWindowProfile = HostLogTailWindowProfile.FULL
    ): HostLogSnapshot? {
        return HostLogManager.readSnapshot(
            context = context,
            entry = entry,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            tailWindowProfile = tailWindowProfile
        )
    }

    fun readLastNonBlankLine(logFile: java.io.File, maxChars: Int = 220): String {
        return HostLogManager.readLastNonBlankLine(logFile, maxChars)
    }
}
