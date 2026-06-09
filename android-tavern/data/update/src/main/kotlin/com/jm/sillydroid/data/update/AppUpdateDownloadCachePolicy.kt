package com.jm.sillydroid.data.update

import com.jm.sillydroid.core.model.update.AppDownloadTaskKey
import com.jm.sillydroid.core.model.update.AvailableAppRelease

/**
 * 更新包缓存只能复用完全相同的任务；版本、URL、文件名或 sha 改变时，
 * 旧 .part 不能继续下载，避免把新版本安装包拼到旧文件尾部。
 */
internal object AppUpdateDownloadCachePolicy {
    fun taskKey(release: AvailableAppRelease): AppDownloadTaskKey {
        return AppDownloadTaskKey(
            releaseTag = release.releaseTag,
            versionName = release.versionName,
            apkAssetName = release.apkAssetName,
            apkDownloadUrl = release.apkDownloadUrl,
            apkSha256 = release.apkSha256
        )
    }

    fun isSameTask(left: AppDownloadTaskKey, right: AppDownloadTaskKey): Boolean {
        return left == right
    }

    fun shouldKeepCacheFile(fileName: String, currentRelease: AvailableAppRelease?): Boolean {
        val apkName = currentRelease?.apkAssetName?.trim()?.ifBlank { null } ?: return false
        return fileName == apkName || fileName == "$apkName.part"
    }
}
