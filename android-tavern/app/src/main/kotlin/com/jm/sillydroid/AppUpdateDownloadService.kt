package com.jm.sillydroid

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jm.sillydroid.core.model.notification.HostForegroundBehavior
import com.jm.sillydroid.core.model.notification.HostNotificationAction
import com.jm.sillydroid.core.model.notification.HostNotificationChannel
import com.jm.sillydroid.core.model.notification.HostNotificationKind
import com.jm.sillydroid.core.model.notification.HostNotificationProgress
import com.jm.sillydroid.core.model.notification.HostNotificationSpec
import com.jm.sillydroid.core.model.notification.HostNotificationTapSpec
import com.jm.sillydroid.core.model.update.AppDownloadStatus
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppUpdateDownloadService : Service() {
    companion object {
        private const val LOG_TAG = "SillyDroidUpdate"
        private const val ACTION_START = "com.jm.sillydroid.action.UPDATE_DOWNLOAD_START"
        private const val foregroundNotificationKey = "app-update-download"
        private const val foregroundNotificationId = 12038

        fun createStartIntent(context: Context): Intent {
            return Intent(context, AppUpdateDownloadService::class.java).apply {
                action = ACTION_START
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val graph = (applicationContext as SillyDroidAppGraphProvider).sillyDroidAppGraph
        val release = graph.appUpdateRepository.cachedAvailableRelease()
        if (release == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            graph.hostNotificationService.postForeground(this, buildForegroundSpec("准备下载更新包。", null, true))
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Update download foreground start was not allowed.", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val notificationJob = launch {
                while (isActive) {
                    val state = graph.appUpdateRepository.cachedDownloadState()
                    if (state != null) {
                        graph.hostNotificationService.postForeground(
                            this@AppUpdateDownloadService,
                            buildForegroundSpec(
                                body = buildProgressBody(state.versionName, state.downloadedBytes, state.totalBytes),
                                progress = buildProgress(state.downloadedBytes, state.totalBytes),
                                ongoing = true
                            )
                        )
                    }
                    delay(1_000L)
                }
            }
            val finalState = graph.appUpdateRepository.startOrResumeDownload(release)
            notificationJob.cancel()
            graph.hostDownloadNotificationCoordinator.refreshAppUpdateDownload(finalState)
            if (finalState.verifiedReadyToInstall) {
                graph.hostDownloadNotificationCoordinator.postAppUpdateReadyToInstall(
                    apkPath = graph.appUpdateRepository.downloadedApkPath(finalState),
                    canRequestPackageInstalls = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        packageManager.canRequestPackageInstalls()
                )
            }
            if (finalState.status == AppDownloadStatus.FAILED || finalState.status == AppDownloadStatus.STALLED) {
                graph.hostDownloadNotificationCoordinator.postAppUpdateDownloadFailed(
                    versionName = finalState.versionName,
                    failureReason = finalState.failureReason
                )
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        super.onDestroy()
    }

    private fun buildForegroundSpec(
        body: String,
        progress: HostNotificationProgress?,
        ongoing: Boolean
    ): HostNotificationSpec {
        return HostNotificationSpec(
            notificationKey = foregroundNotificationKey,
            kind = HostNotificationKind.APP_UPDATE_DOWNLOAD,
            channel = HostNotificationChannel.DOWNLOADS_INSTALL,
            title = "应用更新下载中",
            body = body,
            progress = progress ?: HostNotificationProgress.Indeterminate,
            ongoing = ongoing,
            autoCancel = false,
            tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_MAIN),
            smallIconResId = android.R.drawable.stat_sys_download,
            foregroundBehavior = HostForegroundBehavior(
                serviceNotificationId = foregroundNotificationId,
                foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    null
                }
            )
        )
    }

    private fun buildProgress(downloadedBytes: Long, totalBytes: Long?): HostNotificationProgress {
        return if (totalBytes != null && totalBytes > 0L) {
            HostNotificationProgress.Determinate(
                current = downloadedBytes.coerceAtMost(totalBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                max = totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            )
        } else {
            HostNotificationProgress.Indeterminate
        }
    }

    private fun buildProgressBody(versionName: String, downloadedBytes: Long, totalBytes: Long?): String {
        val percent = if (totalBytes != null && totalBytes > 0L) {
            ((downloadedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }
        return if (percent != null) {
            "正在下载更新“$versionName” ($percent%)。"
        } else {
            "正在下载更新“$versionName”。"
        }
    }
}
