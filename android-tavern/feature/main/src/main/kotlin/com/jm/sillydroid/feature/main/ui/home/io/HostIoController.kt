package com.jm.sillydroid.feature.main.ui.home.io

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.feature.main.MainActivity
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadResult
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import com.jm.sillydroid.feature.main.ui.home.download.BlobDownloadController
import com.jm.sillydroid.feature.main.ui.home.download.BrowserDownloadController
import com.jm.sillydroid.feature.main.ui.home.notification.SystemNotificationController

/**
 * 把宿主侧 IO 相关的 launcher 与 controller 全部抽出来：
 * - 浏览器下载（WebView 普通链接）
 * - blob 下载桥（WebView 内 JS 触发）
 * - 系统通知通道 + Android 13+ 通知运行时权限
 * - WebView 文件选择器 launcher
 *
 * 必须在 Activity onCreate（STARTED 之前）构造，因为内部会调用 registerForActivityResult。
 */
class HostIoController(
    private val activity: AppCompatActivity,
    private val runtimeConfigRepository: RuntimeConfigRepository,
) {
    private val downloadManager by lazy { activity.getSystemService(DownloadManager::class.java) }

    val browserDownloadController: BrowserDownloadController by lazy {
        BrowserDownloadController(
            downloadManager = downloadManager,
            pendingDescription = { fileName -> activity.getString(R.string.download_status_pending, fileName) }
        )
    }

    val blobDownloadController: BlobDownloadController by lazy {
        BlobDownloadController(activity.contentResolver)
    }

    val systemNotificationController: SystemNotificationController by lazy {
        SystemNotificationController(
            context = activity,
            channelId = runtimeConfigRepository.systemNotificationChannelId,
            channelTitle = activity.getString(R.string.system_notification_channel_title),
            channelDescription = activity.getString(R.string.system_notification_channel_description),
            smallIconResId = android.R.drawable.stat_notify_chat,
            launchIntent = Intent(activity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            pendingIntentRequestCode = runtimeConfigRepository.notificationId
        )
    }

    // Android 13+ 的宿主通知需要显式运行时授权，否则 NotificationManager 会直接拒发。
    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op；用户授权与否都由后续真实通知时再决定 */ }

    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        callback.onReceiveValue(resolveFileChooserUris(result.resultCode, result.data))
    }

    fun ensureNotificationChannel() {
        systemNotificationController.ensureChannel()
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun launchFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>>) {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = callback
        fileChooserLauncher.launch(intent)
    }

    fun cancelPendingFileChooser() {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
    }

    @Suppress("DEPRECATION")
    fun handlePageDownload(request: BrowserDownloadRequest) {
        when (val result = browserDownloadController.enqueue(request)) {
            is BrowserDownloadResult.Started -> {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.download_started, result.fileName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            is BrowserDownloadResult.Failed -> {
                showDownloadFailure(
                    DownloadFailureReport(
                        fileName = result.fileName,
                        message = result.message.ifBlank { activity.getString(R.string.download_failed_unknown) }
                    )
                )
            }
            null -> Unit
        }
    }

    fun showDownloadFailure(report: DownloadFailureReport) {
        Toast.makeText(activity, buildDownloadFailureMessage(report), Toast.LENGTH_LONG).show()
    }

    private fun buildDownloadFailureMessage(report: DownloadFailureReport): String {
        val details = report.message.trim().ifBlank { activity.getString(R.string.download_failed_unknown) }
        return activity.getString(R.string.download_failed, report.fileName, details)
    }

    private fun resolveFileChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) {
            return null
        }
        val clipData = data?.clipData
        if (clipData != null) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }
        val selectedUri = data?.data ?: return emptyArray()
        return arrayOf(selectedUri)
    }
}
