package com.jm.sillydroid

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider

/**
 * 统一接收 DownloadManager 完成广播，把普通下载和应用更新下载都切回宿主自管通知。
 */
class HostDownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            return
        }

        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (completedId <= 0L) {
            return
        }

        val appGraph = (context.applicationContext as? SillyDroidAppGraphProvider)?.sillyDroidAppGraph ?: return
        appGraph.hostDownloadNotificationCoordinator.refreshBrowserDownload(completedId)
    }
}
