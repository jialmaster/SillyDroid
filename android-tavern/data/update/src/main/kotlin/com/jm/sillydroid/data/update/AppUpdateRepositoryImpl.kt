package com.jm.sillydroid.data.update

import android.content.Context
import android.os.Environment
import com.jm.sillydroid.core.model.update.AppDownloadRecord
import com.jm.sillydroid.core.model.update.AppDownloadFailureReason
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AppDownloadStatus
import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import com.jm.sillydroid.core.model.update.AvailableAppRelease
import com.jm.sillydroid.domain.update.AppDownloadCacheCleanupResult
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
    private val stateStore: AppUpdateStateStore = AppUpdateStateStore(context)
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
        return SiteLatestReleaseParser.parseLatestAvailableRelease(
            latestReleaseState = fetchJsonObject(config.latestReleaseMetadataUrl),
            config = config
        )
    }

    override suspend fun startDownload(release: AvailableAppRelease): AppDownloadState {
        return startOrResumeDownload(release)
    }

    override suspend fun startOrResumeDownload(release: AvailableAppRelease): AppDownloadState {
        cleanupDownloadCache(currentRelease = release)
        val initialRecord = inspectDownloadState(release)
        if (initialRecord.status == AppDownloadStatus.READY_TO_INSTALL) {
            return markDownloadVerified(createDownloadState(release, initialRecord))
        }

        val targetFile = resolveUpdateTargetFile(release.apkAssetName)
        val partFile = resolveUpdatePartFile(release.apkAssetName)
        val resumeBytes = partFile.length().takeIf { value -> value > 0L } ?: 0L
        val startedAtMillis = System.currentTimeMillis()
        var lastProgressAtMillis = startedAtMillis
        var lastStateWriteAtMillis = 0L
        var totalBytes: Long? = null
        stateStore.downloadState = createDownloadState(
            release = release,
            record = AppDownloadRecord(
                status = AppDownloadStatus.DOWNLOADING,
                downloadedBytes = resumeBytes,
                totalBytes = null,
                lastProgressAtMillis = lastProgressAtMillis,
                resumable = resumeBytes > 0L
            )
        )

        try {
            val result = AppUpdateHttpDownloader(userAgent = userAgent).download(
                url = release.apkDownloadUrl,
                partFile = partFile,
                stalledTimeoutMillis = stalledTimeoutMillis
            ) { progress ->
                totalBytes = progress.totalBytes
                lastProgressAtMillis = progress.lastProgressAtMillis
                val now = System.currentTimeMillis()
                if (now - lastStateWriteAtMillis >= progressPersistIntervalMillis) {
                    lastStateWriteAtMillis = now
                    stateStore.downloadState = createDownloadState(
                        release,
                        AppDownloadRecord(
                            status = AppDownloadStatus.DOWNLOADING,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            lastProgressAtMillis = progress.lastProgressAtMillis,
                            resumable = partFile.length() > 0L || progress.resumed
                        )
                    )
                }
            }
            if (result is AppUpdateHttpDownloadResult.Stalled) {
                val stalledRecord = AppDownloadRecord(
                    status = AppDownloadStatus.STALLED,
                    downloadedBytes = result.downloadedBytes,
                    totalBytes = result.totalBytes,
                    lastProgressAtMillis = result.lastProgressAtMillis,
                    failureReason = AppDownloadFailureReason.STALLED,
                    resumable = partFile.length() > 0L
                )
                stateStore.downloadState = createDownloadState(release, stalledRecord)
                return stateStore.downloadState ?: createDownloadState(release, stalledRecord)
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!partFile.renameTo(targetFile)) {
                throw IOException("Failed to move update package into place.")
            }
            val completedRecord = AppDownloadRecord(
                status = AppDownloadStatus.SUCCESSFUL,
                downloadedBytes = targetFile.length(),
                totalBytes = totalBytes ?: targetFile.length(),
                lastProgressAtMillis = System.currentTimeMillis(),
                resumable = false
            )
            stateStore.downloadState = createDownloadState(release, completedRecord)
            return if (verifyDownloadedApk(stateStore.downloadState ?: createDownloadState(release, completedRecord))) {
                markDownloadVerified(stateStore.downloadState ?: createDownloadState(release, completedRecord))
            } else {
                targetFile.delete()
                val failedRecord = completedRecord.copy(
                    status = AppDownloadStatus.FAILED,
                    failureReason = AppDownloadFailureReason.CHECKSUM,
                    resumable = false
                )
                createDownloadState(release, failedRecord).also { state -> stateStore.downloadState = state }
            }
        } catch (error: IOException) {
            val failedRecord = AppDownloadRecord(
                status = AppDownloadStatus.FAILED,
                downloadedBytes = partFile.length().coerceAtLeast(0L),
                totalBytes = totalBytes,
                lastProgressAtMillis = lastProgressAtMillis,
                failureReason = AppDownloadFailureReason.NETWORK,
                resumable = partFile.length() > 0L
            )
            return createDownloadState(release, failedRecord).also { state -> stateStore.downloadState = state }
        } catch (error: SecurityException) {
            val failedRecord = AppDownloadRecord(
                status = AppDownloadStatus.FAILED,
                downloadedBytes = partFile.length().coerceAtLeast(0L),
                totalBytes = totalBytes,
                lastProgressAtMillis = lastProgressAtMillis,
                failureReason = AppDownloadFailureReason.STORAGE,
                resumable = partFile.length() > 0L
            )
            return createDownloadState(release, failedRecord).also { state -> stateStore.downloadState = state }
        }
    }

    override fun inspectDownloadState(
        release: AvailableAppRelease,
        verifyCompleteApk: Boolean
    ): AppDownloadRecord {
        val targetFile = resolveUpdateTargetFile(release.apkAssetName)
        if (targetFile.isFile) {
            if (!verifyCompleteApk) {
                return AppDownloadRecord(
                    status = AppDownloadStatus.SUCCESSFUL,
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length()
                )
            }
            val syntheticState = createDownloadState(
                release = release,
                record = AppDownloadRecord(
                    status = AppDownloadStatus.SUCCESSFUL,
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length()
                )
            )
            return if (verifyDownloadedApk(syntheticState)) {
                AppDownloadRecord(
                    status = AppDownloadStatus.READY_TO_INSTALL,
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length()
                )
            } else {
                targetFile.delete()
                AppDownloadRecord(
                    status = AppDownloadStatus.FAILED,
                    failureReason = AppDownloadFailureReason.CHECKSUM
                )
            }
        }

        val partFile = resolveUpdatePartFile(release.apkAssetName)
        if (partFile.isFile && partFile.length() > 0L) {
            return AppDownloadRecord(
                status = AppDownloadStatus.RESUMABLE,
                downloadedBytes = partFile.length(),
                totalBytes = null,
                lastProgressAtMillis = partFile.lastModified().coerceAtLeast(0L),
                resumable = true
            )
        }

        return AppDownloadRecord(status = AppDownloadStatus.MISSING)
    }

    override fun updateDownloadProgress(record: AppDownloadRecord): AppDownloadState? {
        val currentState = stateStore.downloadState ?: return null
        val nextState = currentState.copy(
            status = record.status,
            downloadedBytes = record.downloadedBytes,
            totalBytes = record.totalBytes,
            lastProgressAtMillis = record.lastProgressAtMillis,
            failureReason = record.failureReason,
            resumable = record.resumable,
            verifiedReadyToInstall = record.status == AppDownloadStatus.READY_TO_INSTALL
        )
        stateStore.downloadState = nextState
        return nextState
    }

    override fun cleanupDownloadCache(currentRelease: AvailableAppRelease?): AppDownloadCacheCleanupResult {
        updatesDirectory.mkdirs()
        var scanned = 0
        var deleted = 0
        var freedBytes = 0L
        updatesDirectory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) {
                return@forEach
            }
            scanned += 1
            val keep = AppUpdateDownloadCachePolicy.shouldKeepCacheFile(file.name, currentRelease)
            if (!keep) {
                val length = file.length().coerceAtLeast(0L)
                if (file.delete()) {
                    deleted += 1
                    freedBytes += length
                }
            }
        }
        return AppDownloadCacheCleanupResult(
            scannedFileCount = scanned,
            deletedFileCount = deleted,
            freedBytes = freedBytes
        )
    }

    private fun createDownloadState(
        release: AvailableAppRelease,
        record: AppDownloadRecord
    ): AppDownloadState {
        return AppDownloadState(
            downloadId = release.taskId(),
            releaseTag = release.releaseTag,
            releaseTitle = release.releaseTitle,
            versionName = release.versionName,
            hostVersion = release.hostVersion,
            releaseNotesMarkdown = release.releaseNotesMarkdown,
            apkAssetName = release.apkAssetName,
            apkDownloadUrl = release.apkDownloadUrl,
            apkSha256 = release.apkSha256,
            verifiedReadyToInstall = record.status == AppDownloadStatus.READY_TO_INSTALL,
            status = record.status,
            downloadedBytes = record.downloadedBytes,
            totalBytes = record.totalBytes,
            lastProgressAtMillis = record.lastProgressAtMillis,
            failureReason = record.failureReason,
            resumable = record.resumable
        )
    }

    override fun queryDownloadRecord(downloadId: Long): AppDownloadRecord {
        val currentState = stateStore.downloadState
        if (currentState == null || currentState.downloadId != downloadId) {
            return AppDownloadRecord(status = AppDownloadStatus.MISSING)
        }
        return AppDownloadRecord(
            status = currentState.status,
            downloadedBytes = currentState.downloadedBytes,
            totalBytes = currentState.totalBytes,
            lastProgressAtMillis = currentState.lastProgressAtMillis,
            failureReason = currentState.failureReason,
            resumable = currentState.resumable
        )
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
            resolveUpdatePartFile(currentDownload.apkAssetName).delete()
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

    private fun resolveUpdatePartFile(apkAssetName: String): File {
        return File(updatesDirectory, "$apkAssetName.part")
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

    private companion object {
        private const val apkMimeType = "application/vnd.android.package-archive"
        private const val userAgent = "SillyDroid-Android-Updater"
        private const val stalledTimeoutMillis = 10 * 60 * 1_000L
        private const val progressPersistIntervalMillis = 1_000L
        private const val httpRequestedRangeNotSatisfiable = 416

        // 5.2: 进程内安装器去重窗口与状态。
        private const val installerLaunchDedupWindowMillis = 5_000L
        private val installerLaunchLock = Any()

        @Volatile
        private var lastInstallerLaunchedDownloadId: Long = -1L

        @Volatile
        private var lastInstallerLaunchedAtMillis: Long = 0L

        private fun AvailableAppRelease.taskId(): Long {
            val key = AppUpdateDownloadCachePolicy.taskKey(this)
            val seed = listOf(key.releaseTag, key.versionName, key.apkAssetName, key.apkDownloadUrl, key.apkSha256)
                .joinToString(separator = "|")
                .hashCode()
            return if (seed == Int.MIN_VALUE) Int.MAX_VALUE.toLong() else kotlin.math.abs(seed).toLong() + 1L
        }
    }
}
