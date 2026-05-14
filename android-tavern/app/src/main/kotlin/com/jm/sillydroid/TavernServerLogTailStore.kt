package com.jm.sillydroid

@Deprecated("Use HostLogManager directly.", ReplaceWith("HostLogManager"))
internal object TavernServerLogTailStore {
    val latestLine = HostLogManager.latestTavernServerLine

    fun start(context: android.content.Context) {
        HostLogManager.startCurrentServerTail(context)
    }

    fun stop() {
        HostLogManager.stopCurrentServerTail()
    }
}
