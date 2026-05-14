package com.jm.sillydroid

@Deprecated("Use HostLogManager directly.", ReplaceWith("HostLogManager"))
internal object HostLogSessionStore {
    fun initializeForAppStart(context: android.content.Context) {
        HostLogManager.initializeForAppStart(context)
    }

    fun currentStartupLogFile(context: android.content.Context): java.io.File {
        return HostLogManager.currentStartupLogFile(context)
    }

    fun currentServerLogFile(context: android.content.Context): java.io.File {
        return HostLogManager.currentServerLogFile(context)
    }

    fun currentRootfsRuntimeLogFile(context: android.content.Context): java.io.File {
        return HostLogManager.currentRootfsRuntimeLogFile(context)
    }

    fun currentStartupLogFileName(context: android.content.Context): String {
        return HostLogManager.currentStartupLogFileName(context)
    }

    fun currentServerLogFileName(context: android.content.Context): String {
        return HostLogManager.currentServerLogFileName(context)
    }

    fun currentRootfsRuntimeLogFileName(context: android.content.Context): String {
        return HostLogManager.currentRootfsRuntimeLogFileName(context)
    }

    fun isCurrentSessionHostLogFileName(context: android.content.Context, fileName: String): Boolean {
        return HostLogManager.isCurrentSessionHostLogFileName(context, fileName)
    }

    fun isCurrentStartupLogFileName(context: android.content.Context, fileName: String): Boolean {
        return HostLogManager.isCurrentStartupLogFileName(context, fileName)
    }

    fun isCurrentServerLogFileName(context: android.content.Context, fileName: String): Boolean {
        return HostLogManager.isCurrentServerLogFileName(context, fileName)
    }

    fun isCurrentRootfsRuntimeLogFileName(context: android.content.Context, fileName: String): Boolean {
        return HostLogManager.isCurrentRootfsRuntimeLogFileName(context, fileName)
    }

    fun clearAllLogs(context: android.content.Context) {
        HostLogManager.clearAllLogs(context)
    }
}
