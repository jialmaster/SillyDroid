package com.jm.sillydroid

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.jm.sillydroid.core.model.notification.HostNotificationAction
import com.jm.sillydroid.core.model.notification.HostNotificationChannel
import com.jm.sillydroid.core.model.notification.HostNotificationKind
import com.jm.sillydroid.core.model.notification.HostNotificationProgress
import com.jm.sillydroid.core.model.notification.HostNotificationSpec
import com.jm.sillydroid.core.model.notification.HostNotificationTapSpec
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.domain.notification.HostDownloadNotificationCoordinator
import com.jm.sillydroid.domain.notification.HostNotificationService
import java.io.File

/**
 * 统一协调宿主接管的下载类通知：
 * - 普通浏览器下载
 * - 应用更新下载
 * - 更新包校验完成后的安装就绪
 *
 * 这里只负责“下载状态 -> 统一通知 spec”的映射，不承载下载业务本身。
 */
class HostDownloadNotificationCoordinatorImpl(
    context: Context,
    private val hostNotificationService: HostNotificationService,
    private val downloadManager: DownloadManager
) : HostDownloadNotificationCoordinator {
    companion object {
        private const val browserDownloadPrefix = "browser-download:"
        private const val browserSavedDownloadPrefix = "browser-download-saved:"
        private const val updateDownloadKey = "app-update-download"
        private const val updateInstallReadyKey = "app-update-ready-to-install"
    }

    private val appContext = context.applicationContext
    private val stateStore = HostDownloadNotificationStateStore(appContext)

    override fun recordBrowserDownloadStarted(
        downloadId: Long,
        fileName: String,
        mimeType: String,
        localUri: String
    ) {
        val notificationKey = browserDownloadNotificationKey(downloadId)
        stateStore.saveBrowserDownload(
            HostDownloadNotificationStateStore.BrowserDownloadEntry(
                downloadId = downloadId,
                notificationKey = notificationKey,
                fileName = fileName,
                mimeType = mimeType,
                localUri = localUri
            )
        )
        hostNotificationService.post(
            HostNotificationSpec(
                notificationKey = notificationKey,
                kind = HostNotificationKind.BROWSER_DOWNLOAD,
                channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                title = "文件下载中",
                body = "已开始下载“$fileName”。",
                progress = HostNotificationProgress.Indeterminate,
                ongoing = true,
                autoCancel = false,
                tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_DOWNLOADS),
                smallIconResId = android.R.drawable.stat_sys_download
            )
        )
    }

    override fun refreshBrowserDownload(downloadId: Long) {
        val entry = stateStore.browserDownloadById(downloadId) ?: return
        val record = queryRecord(downloadId)
        when (record.status) {
            DownloadQueryStatus.PENDING,
            DownloadQueryStatus.PAUSED,
            DownloadQueryStatus.RUNNING -> {
                hostNotificationService.post(
                    HostNotificationSpec(
                        notificationKey = entry.notificationKey,
                        kind = HostNotificationKind.BROWSER_DOWNLOAD,
                        channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                        title = "文件下载中",
                        body = buildBrowserDownloadProgressBody(entry.fileName, record),
                        progress = record.progress,
                        ongoing = true,
                        autoCancel = false,
                        tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_DOWNLOADS),
                        smallIconResId = android.R.drawable.stat_sys_download
                    )
                )
            }

            DownloadQueryStatus.SUCCESSFUL -> {
                val displayPath = resolveBrowserDownloadDisplayPath(entry.localUri, entry.fileName)
                hostNotificationService.post(
                    HostNotificationSpec(
                        notificationKey = entry.notificationKey,
                        kind = HostNotificationKind.BROWSER_DOWNLOAD,
                        channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                        title = entry.fileName,
                        body = displayPath,
                        progress = HostNotificationProgress.None,
                        ongoing = false,
                        autoCancel = true,
                        tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_DOWNLOADS),
                        smallIconResId = android.R.drawable.stat_sys_download_done
                    )
                )
                stateStore.removeBrowserDownload(downloadId)
            }

            DownloadQueryStatus.FAILED,
            DownloadQueryStatus.MISSING -> {
                hostNotificationService.post(
                    HostNotificationSpec(
                        notificationKey = entry.notificationKey,
                        kind = HostNotificationKind.BROWSER_DOWNLOAD,
                        channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                        title = "下载失败",
                        body = "“${entry.fileName}”下载失败，请重试。",
                        progress = HostNotificationProgress.None,
                        ongoing = false,
                        autoCancel = true,
                        tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_DOWNLOADS),
                        smallIconResId = android.R.drawable.stat_notify_error
                    )
                )
                stateStore.removeBrowserDownload(downloadId)
            }
        }
    }

    override fun postBrowserDownloadSaved(
        fileName: String,
        mimeType: String,
        contentUri: String,
        displayPath: String
    ) {
        val resolvedFileName = fileName.trim().ifBlank { "download" }
        val resolvedDisplayPath = displayPath.trim().ifBlank { buildDownloadsDisplayPath(resolvedFileName) }
        val resolvedContentUri = contentUri.trim()
        // blob/data 导出由宿主直接写入 MediaStore，不会产生 DownloadManager 完成广播；
        // 保存成功后仍必须经由下载通知协调器转成统一通知 spec，保证 WebView 导出和普通下载的通知出口一致。
        hostNotificationService.post(
            HostNotificationSpec(
                notificationKey = savedBrowserDownloadNotificationKey(
                    fileName = resolvedFileName,
                    contentUri = resolvedContentUri,
                    displayPath = resolvedDisplayPath
                ),
                kind = HostNotificationKind.BROWSER_DOWNLOAD,
                channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                title = resolvedFileName,
                body = resolvedDisplayPath,
                progress = HostNotificationProgress.None,
                ongoing = false,
                autoCancel = true,
                tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_DOWNLOADS),
                smallIconResId = android.R.drawable.stat_sys_download_done
            )
        )
    }

    override fun postAppUpdateDownloadStarted(downloadState: AppDownloadState) {
        hostNotificationService.remove(updateInstallReadyKey)
        hostNotificationService.post(
            HostNotificationSpec(
                notificationKey = updateDownloadKey,
                kind = HostNotificationKind.APP_UPDATE_DOWNLOAD,
                channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                title = "应用更新下载中",
                body = "已开始下载更新“${downloadState.versionName}”。",
                progress = HostNotificationProgress.Indeterminate,
                ongoing = true,
                autoCancel = false,
                tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_MAIN),
                smallIconResId = android.R.drawable.stat_sys_download
            )
        )
    }

    override fun refreshAppUpdateDownload(downloadState: AppDownloadState) {
        val record = queryRecord(downloadState.downloadId)
        when (record.status) {
            DownloadQueryStatus.PENDING,
            DownloadQueryStatus.PAUSED,
            DownloadQueryStatus.RUNNING -> {
                hostNotificationService.post(
                    HostNotificationSpec(
                        notificationKey = updateDownloadKey,
                        kind = HostNotificationKind.APP_UPDATE_DOWNLOAD,
                        channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                        title = "应用更新下载中",
                        body = buildUpdateDownloadProgressBody(downloadState.versionName, record),
                        progress = record.progress,
                        ongoing = true,
                        autoCancel = false,
                        tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_MAIN),
                        smallIconResId = android.R.drawable.stat_sys_download
                    )
                )
            }

            DownloadQueryStatus.SUCCESSFUL -> {
                hostNotificationService.post(
                    HostNotificationSpec(
                        notificationKey = updateDownloadKey,
                        kind = HostNotificationKind.APP_UPDATE_DOWNLOAD,
                        channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                        title = "更新下载完成",
                        body = "更新“${downloadState.versionName}”已下载完成，正在校验。",
                        progress = HostNotificationProgress.None,
                        ongoing = true,
                        autoCancel = false,
                        tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_MAIN),
                        smallIconResId = android.R.drawable.stat_sys_download_done
                    )
                )
            }

            DownloadQueryStatus.FAILED,
            DownloadQueryStatus.MISSING -> {
                postAppUpdateDownloadFailed(downloadState.versionName)
            }
        }
    }

    override fun postAppUpdateReadyToInstall(apkPath: String, canRequestPackageInstalls: Boolean) {
        hostNotificationService.remove(updateDownloadKey)
        hostNotificationService.post(
            HostNotificationSpec(
                notificationKey = updateInstallReadyKey,
                kind = HostNotificationKind.APP_UPDATE_READY_TO_INSTALL,
                channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                title = "更新可安装",
                body = "更新包已准备完成，点击继续安装。",
                progress = HostNotificationProgress.None,
                ongoing = false,
                autoCancel = true,
                tapSpec = HostNotificationTapSpec(
                    action = if (canRequestPackageInstalls) {
                        HostNotificationAction.OPEN_UPDATE_INSTALLER
                    } else {
                        HostNotificationAction.OPEN_APP_INSTALL_PERMISSION_SETTINGS
                    },
                    payload = apkPath,
                    mimeType = "application/vnd.android.package-archive"
                ),
                smallIconResId = android.R.drawable.stat_sys_download_done
            )
        )
    }

    override fun postAppUpdateDownloadFailed(versionName: String) {
        hostNotificationService.remove(updateInstallReadyKey)
        hostNotificationService.post(
            HostNotificationSpec(
                notificationKey = updateDownloadKey,
                kind = HostNotificationKind.APP_UPDATE_DOWNLOAD,
                channel = HostNotificationChannel.DOWNLOADS_INSTALL,
                title = "更新下载失败",
                body = "更新“$versionName”下载失败，请重试。",
                progress = HostNotificationProgress.None,
                ongoing = false,
                autoCancel = true,
                tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_MAIN),
                smallIconResId = android.R.drawable.stat_notify_error
            )
        )
    }

    override fun clearAppUpdateNotifications() {
        hostNotificationService.remove(updateDownloadKey)
        hostNotificationService.remove(updateInstallReadyKey)
    }

    private fun browserDownloadNotificationKey(downloadId: Long): String {
        return "$browserDownloadPrefix$downloadId"
    }

    private fun savedBrowserDownloadNotificationKey(
        fileName: String,
        contentUri: String,
        displayPath: String
    ): String {
        val seed = contentUri.ifBlank { displayPath }.ifBlank { fileName }
        return "$browserSavedDownloadPrefix${seed.hashCode()}"
    }

    private fun resolveBrowserDownloadDisplayPath(localUri: String, fileName: String): String {
        val rawLocalUri = localUri.trim()
        val parsedUri = runCatching { Uri.parse(rawLocalUri) }.getOrNull()
        if (parsedUri?.scheme.equals("file", ignoreCase = true)) {
            val path = parsedUri?.path.orEmpty()
            if (path.isNotBlank()) {
                return File(path).absolutePath
            }
        }
        return rawLocalUri.ifBlank { buildDownloadsDisplayPath(fileName) }
    }

    @Suppress("DEPRECATION")
    private fun buildDownloadsDisplayPath(fileName: String): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve(fileName)
            .absolutePath
    }

    private fun buildBrowserDownloadProgressBody(
        fileName: String,
        record: DownloadQueryRecord
    ): String {
        val percent = record.progressPercent
        return if (percent != null) {
            "正在下载“$fileName” ($percent%)。"
        } else {
            "正在下载“$fileName”。"
        }
    }

    private fun buildUpdateDownloadProgressBody(
        versionName: String,
        record: DownloadQueryRecord
    ): String {
        val percent = record.progressPercent
        return if (percent != null) {
            "正在下载更新“$versionName” ($percent%)。"
        } else {
            "正在下载更新“$versionName”。"
        }
    }

    private fun queryRecord(downloadId: Long): DownloadQueryRecord {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                return DownloadQueryRecord(status = DownloadQueryStatus.MISSING)
            }

            val bytesSoFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val progress = if (totalBytes > 0L && bytesSoFar >= 0L) {
                val normalizedCurrent = bytesSoFar.coerceAtMost(totalBytes).toInt()
                val normalizedMax = totalBytes.toInt().coerceAtLeast(1)
                HostNotificationProgress.Determinate(
                    current = normalizedCurrent,
                    max = normalizedMax
                )
            } else {
                HostNotificationProgress.Indeterminate
            }
            val progressPercent = if (totalBytes > 0L && bytesSoFar >= 0L) {
                ((bytesSoFar.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                null
            }
            val status = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> DownloadQueryStatus.PENDING
                DownloadManager.STATUS_PAUSED -> DownloadQueryStatus.PAUSED
                DownloadManager.STATUS_RUNNING -> DownloadQueryStatus.RUNNING
                DownloadManager.STATUS_SUCCESSFUL -> DownloadQueryStatus.SUCCESSFUL
                DownloadManager.STATUS_FAILED -> DownloadQueryStatus.FAILED
                else -> DownloadQueryStatus.FAILED
            }
            return DownloadQueryRecord(
                status = status,
                progress = progress,
                progressPercent = progressPercent
            )
        }
    }

    private data class DownloadQueryRecord(
        val status: DownloadQueryStatus,
        val progress: HostNotificationProgress = HostNotificationProgress.None,
        val progressPercent: Int? = null
    )

    private enum class DownloadQueryStatus {
        PENDING,
        PAUSED,
        RUNNING,
        SUCCESSFUL,
        FAILED,
        MISSING
    }
}
