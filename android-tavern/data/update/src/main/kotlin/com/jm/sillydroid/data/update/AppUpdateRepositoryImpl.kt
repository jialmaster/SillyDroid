package com.jm.sillydroid.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.jm.sillydroid.core.model.update.AppDownloadRecord
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AppDownloadStatus
import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import com.jm.sillydroid.core.model.update.AvailableAppRelease
import com.jm.sillydroid.domain.update.AppUpdateRepository
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

class AppUpdateRepositoryImpl(
    context: Context,
    private val downloadManager: DownloadManager,
    private val stateStore: AppUpdateStateStore = AppUpdateStateStore(context),
    private val downloadDescription: String
) : AppUpdateRepository {
    private val appContext = context.applicationContext
    private val updatesDirectory by lazy {
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
    }

    override var checkErrorMessage: String?
        get() = stateStore.checkErrorMessage
        set(value) {
            stateStore.checkErrorMessage = value
        }

    override fun cachedAvailableRelease(): AvailableAppRelease? {
        return stateStore.availableRelease
    }

    override fun cachedDownloadState(): AppDownloadState? {
        return stateStore.downloadState
    }

    override fun cacheAvailableRelease(release: AvailableAppRelease?) {
        stateStore.availableRelease = release
    }

    override suspend fun fetchLatestAvailableRelease(config: AppUpdateRequestConfig): AvailableAppRelease? {
        // The public site JSON is the only repo-owned latest pointer.
        // Device-side update checks must consume it directly so release delete /
        // edit events stop depending on GitHub `latest` redirects or API scans.
        val latestReleaseState = fetchJsonObject(config.latestReleaseMetadataUrl)
        val statusCode = latestReleaseState.optJSONObject("status")?.optString("code")?.trim().orEmpty()
        if (!statusCode.equals("ready", ignoreCase = true)) {
            return null
        }

        val release = latestReleaseState.optJSONObject("release") ?: return null
        if (!release.optString("buildType").trim().equals(config.buildType, ignoreCase = true)) {
            return null
        }

        val releaseTag = release.optString("tag").trim()
        val releaseTitle = release.optString("title").trim().ifBlank { releaseTag }
        val versionName = release.optString("versionName").trim()
        val hostVersion = release.optString("hostVersion").trim()
        val apk = release.optJSONObject("apk") ?: return null
        val apkAssetName = apk.optString("assetName").trim()
        val apkDownloadUrl = apk.optString("downloadUrl").trim()
        val apkSha256 = apk.optString("sha256").trim().lowercase(Locale.US)
        if (
            releaseTag.isBlank() ||
            releaseTitle.isBlank() ||
            versionName.isBlank() ||
            hostVersion.isBlank() ||
            apkAssetName.isBlank() ||
            apkDownloadUrl.isBlank() ||
            apkSha256.length != 64 ||
            compareVersionNames(versionName, config.currentVersionName) <= 0
        ) {
            return null
        }

        return AvailableAppRelease(
            releaseTag = releaseTag,
            releaseTitle = releaseTitle,
            versionName = versionName,
            hostVersion = hostVersion,
            apkAssetName = apkAssetName,
            apkDownloadUrl = apkDownloadUrl,
            apkSha256 = apkSha256
        )
    }

    override suspend fun startDownload(release: AvailableAppRelease): AppDownloadState {
        resolveUpdateTargetFile(release.apkAssetName).delete()
        val request = DownloadManager.Request(Uri.parse(release.apkDownloadUrl)).apply {
            setTitle(release.releaseTitle)
            setDescription(downloadDescription)
            // 更新下载改由宿主统一通知出口接管完成态；
            // 这里保留下载中的可见性，避免触发系统隐藏通知所需的额外权限约束。
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            @Suppress("DEPRECATION")
            setVisibleInDownloadsUi(true)
            setMimeType(apkMimeType)
            addRequestHeader("Accept", "application/octet-stream")
            addRequestHeader("User-Agent", userAgent)
            setDestinationUri(Uri.fromFile(resolveUpdateTargetFile(release.apkAssetName)))
        }

        val downloadState = AppDownloadState(
            downloadId = downloadManager.enqueue(request),
            releaseTag = release.releaseTag,
            releaseTitle = release.releaseTitle,
            versionName = release.versionName,
            hostVersion = release.hostVersion,
            apkAssetName = release.apkAssetName,
            apkDownloadUrl = release.apkDownloadUrl,
            apkSha256 = release.apkSha256,
            verifiedReadyToInstall = false
        )
        stateStore.downloadState = downloadState
        return downloadState
    }

    override fun queryDownloadRecord(downloadId: Long): AppDownloadRecord {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                return AppDownloadRecord(status = AppDownloadStatus.MISSING)
            }

            val status = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> AppDownloadStatus.PENDING
                DownloadManager.STATUS_PAUSED -> AppDownloadStatus.PAUSED
                DownloadManager.STATUS_RUNNING -> AppDownloadStatus.RUNNING
                DownloadManager.STATUS_SUCCESSFUL -> AppDownloadStatus.SUCCESSFUL
                else -> AppDownloadStatus.FAILED
            }
            return AppDownloadRecord(
                status = status,
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            )
        }
    }

    override fun verifyDownloadedApk(downloadState: AppDownloadState): Boolean {
        return computeDownloadedApkSha256(downloadState)?.equals(downloadState.apkSha256, ignoreCase = true) == true
    }

    override fun markDownloadVerified(downloadState: AppDownloadState): AppDownloadState {
        val verifiedState = downloadState.copy(verifiedReadyToInstall = true)
        stateStore.downloadState = verifiedState
        return verifiedState
    }

    override fun downloadedApkPath(downloadState: AppDownloadState): String {
        return resolveUpdateTargetFile(downloadState.apkAssetName).absolutePath
    }

    override fun clearAvailableRelease() {
        stateStore.availableRelease = null
    }

    override fun clearDownloadState(removeDownload: Boolean) {
        val currentDownload = stateStore.downloadState
        if (removeDownload && currentDownload != null) {
            runCatching { downloadManager.remove(currentDownload.downloadId) }
        }
        stateStore.downloadState = null
    }

    override fun claimInstallerLaunch(downloadId: Long): Boolean {
        synchronized(installerLaunchLock) {
            val now = System.currentTimeMillis()
            if (lastInstallerLaunchedDownloadId == downloadId &&
                now - lastInstallerLaunchedAtMillis < installerLaunchDedupWindowMillis
            ) {
                return false
            }
            lastInstallerLaunchedDownloadId = downloadId
            lastInstallerLaunchedAtMillis = now
            return true
        }
    }

    private fun resolveUpdateTargetFile(apkAssetName: String): File {
        return File(updatesDirectory, apkAssetName)
    }

    private fun computeDownloadedApkSha256(downloadState: AppDownloadState): String? {
        val updateFile = resolveUpdateTargetFile(downloadState.apkAssetName)
        if (!updateFile.isFile) {
            return null
        }

        val digest = MessageDigest.getInstance("SHA-256")
        updateFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) {
                    break
                }
                if (bytesRead == 0) {
                    continue
                }
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
    }

    private fun fetchJsonObject(url: String): JSONObject {
        return JSONObject(fetchText(url))
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
        }

        try {
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { reader -> reader.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode: $responseText")
            }

            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftTokens = left.split(Regex("[^0-9A-Za-z]+"))
            .filter { token -> token.isNotBlank() }
        val rightTokens = right.split(Regex("[^0-9A-Za-z]+"))
            .filter { token -> token.isNotBlank() }
        val maxSize = maxOf(leftTokens.size, rightTokens.size)
        for (index in 0 until maxSize) {
            val leftToken = leftTokens.getOrElse(index) { "0" }
            val rightToken = rightTokens.getOrElse(index) { "0" }
            val leftNumber = leftToken.toIntOrNull()
            val rightNumber = rightToken.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> 1
                rightNumber != null -> -1
                else -> leftToken.compareTo(rightToken, ignoreCase = true)
            }
            if (comparison != 0) {
                return comparison
            }
        }
        return 0
    }

    private companion object {
        private const val apkMimeType = "application/vnd.android.package-archive"
        private const val userAgent = "SillyDroid-Android-Updater"

        // 5.2: 进程内安装器去重窗口与状态。
        private const val installerLaunchDedupWindowMillis = 5_000L
        private val installerLaunchLock = Any()

        @Volatile
        private var lastInstallerLaunchedDownloadId: Long = -1L

        @Volatile
        private var lastInstallerLaunchedAtMillis: Long = 0L
    }
}
