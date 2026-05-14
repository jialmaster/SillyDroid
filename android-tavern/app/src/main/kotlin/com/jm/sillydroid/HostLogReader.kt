package com.jm.sillydroid

@Deprecated("Use HostLogManager directly.", ReplaceWith("HostLogManager"))
internal object HostLogReader {
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
        logFile: java.io.File,
        maxChars: Int = 320_000,
        maxBytes: Int = 768 * 1024,
        maxLines: Int = 6_000,
        tailWindowProfile: HostLogTailWindowProfile = HostLogTailWindowProfile.FULL,
        entry: HostLogEntry? = null
    ): HostLogSnapshot? {
        return HostLogManager.readSnapshot(
            context = context,
            logFile = logFile,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            tailWindowProfile = tailWindowProfile,
            entry = entry
        )
    }

    fun readLastNonBlankLine(logFile: java.io.File, maxChars: Int = 220): String {
        return HostLogManager.readLastNonBlankLine(logFile, maxChars)
    }
}
