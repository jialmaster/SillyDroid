package com.jm.sillydroid.domain.update

import com.jm.sillydroid.core.model.update.AppDownloadRecord
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import com.jm.sillydroid.core.model.update.AvailableAppRelease

interface AppUpdateRepository {
    fun cachedAvailableRelease(): AvailableAppRelease?
    fun cachedDownloadState(): AppDownloadState?
    var checkErrorMessage: String?
    fun cacheAvailableRelease(release: AvailableAppRelease?)
    suspend fun fetchLatestAvailableRelease(config: AppUpdateRequestConfig): AvailableAppRelease?
    suspend fun startDownload(release: AvailableAppRelease): AppDownloadState
    fun queryDownloadRecord(downloadId: Long): AppDownloadRecord
    fun verifyDownloadedApk(downloadState: AppDownloadState): Boolean
    fun markDownloadVerified(downloadState: AppDownloadState): AppDownloadState
    fun downloadedApkPath(downloadState: AppDownloadState): String
    fun clearAvailableRelease()
    fun clearDownloadState(removeDownload: Boolean)

    /**
     * 5.2: 进程内去重闸门。
     * 当 [MainActivity] 与 [BootstrapSettingsActivity] 同时订阅
     * `DOWNLOAD_COMPLETE` 广播并各自调用安装 Intent 时，
     * 首次调用返回 true，同一 downloadId 在去重窗口内的后续调用返回 false。
     */
    fun claimInstallerLaunch(downloadId: Long): Boolean
}

interface AppUpdateStateRepository {
    var availableRelease: AvailableAppRelease?
    var downloadState: AppDownloadState?
    var checkErrorMessage: String?
}
