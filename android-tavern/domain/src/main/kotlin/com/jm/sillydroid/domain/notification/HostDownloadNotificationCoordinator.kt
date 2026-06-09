package com.jm.sillydroid.domain.notification

import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AppDownloadFailureReason

interface HostDownloadNotificationCoordinator {
    fun recordBrowserDownloadStarted(
        downloadId: Long,
        fileName: String,
        mimeType: String,
        localUri: String
    )

    fun refreshBrowserDownload(downloadId: Long)
    fun postBrowserDownloadSaved(
        fileName: String,
        mimeType: String,
        contentUri: String,
        displayPath: String
    )
    fun postAppUpdateDownloadStarted(downloadState: AppDownloadState)
    fun refreshAppUpdateDownload(downloadState: AppDownloadState)
    fun postAppUpdateReadyToInstall(apkPath: String, canRequestPackageInstalls: Boolean)
    fun postAppUpdateDownloadFailed(versionName: String, failureReason: AppDownloadFailureReason? = null)
    fun clearAppUpdateNotifications()
}
