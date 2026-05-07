package com.stai.sillytavern

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal class AppUpdateCoordinator(
    private val activity: AppCompatActivity,
    private val updateButtonContainer: View,
    private val updateButton: ImageButton,
    private val updateBadgeView: View,
    private val downloadManager: DownloadManager
) {
    private data class DownloadRecord(
        val status: Int,
        val reason: Int,
        val localUri: String?
    )

    companion object {
        private const val githubApiBaseUrl = "https://api.github.com"
        private const val metadataAssetSuffix = ".update.json"
        private const val apkMimeType = "application/vnd.android.package-archive"
        private const val userAgent = "STAI-Android-Updater"
    }

    private val stateStore = AppUpdateStateStore(activity)
    private val updatesDirectory by lazy {
        File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
    }
    private var receiverRegistered = false
    private var hasCheckedForUpdates = false
    private var syncJob: Job? = null

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (completedId != stateStore.downloadState?.downloadId) {
                return
            }

            activity.lifecycleScope.launch {
                syncDownloadState(showErrors = true, openInstallerIfReady = true)
            }
        }
    }

    fun initialize() {
        clearInstalledVersionState()
        updateButton.setOnClickListener {
            activity.lifecycleScope.launch {
                handleUpdateButtonClick()
            }
        }
        renderState()
    }

    fun onStart() {
        registerDownloadReceiver()
        activity.lifecycleScope.launch {
            syncDownloadState(showErrors = false, openInstallerIfReady = true)
            if (!hasCheckedForUpdates) {
                hasCheckedForUpdates = true
                checkForUpdates(silent = true)
            }
        }
    }

    fun onResume() {
        activity.lifecycleScope.launch {
            maybeOpenVerifiedDownload()
        }
    }

    fun onStop() {
        unregisterDownloadReceiver()
    }

    fun onDestroy() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun handleUpdateButtonClick() {
        val currentDownload = stateStore.downloadState
        if (currentDownload != null) {
            when (queryDownloadRecord(currentDownload.downloadId)?.status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_RUNNING -> {
                    showMessage(R.string.app_update_download_running)
                    return
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    verifyAndMaybeOpenDownload(currentDownload, openInstallerIfReady = true, showErrors = true)
                    return
                }
            }

            clearDownloadState(removeDownload = true)
        }

        val availableRelease = stateStore.availableRelease ?: run {
            checkForUpdates(silent = false)
            stateStore.availableRelease
        } ?: return

        startDownload(availableRelease)
    }

    private suspend fun checkForUpdates(silent: Boolean) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                fetchLatestAvailableRelease()
            }
        }

        result.onSuccess { release ->
            if (release == null) {
                if (stateStore.downloadState == null) {
                    stateStore.availableRelease = null
                }
            } else {
                stateStore.availableRelease = release
            }
            renderState()
        }.onFailure {
            if (!silent) {
                showMessage(R.string.app_update_check_failed)
            }
            renderState()
        }
    }

    private suspend fun fetchLatestAvailableRelease(): AppUpdateStateStore.AvailableRelease? {
        val releases = fetchJsonArray("$githubApiBaseUrl/repos/${BuildConfig.STAI_GITHUB_REPOSITORY}/releases?per_page=12")
        val currentVersionName = resolveCurrentVersionName()

        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            val releaseTag = release.optString("tag_name").trim()
            if (!releaseTag.endsWith("-${BuildConfig.BUILD_TYPE}")) {
                continue
            }

            val releaseTitle = release.optString("name").trim().ifBlank { releaseTag }
            val assets = release.optJSONArray("assets") ?: continue
            val metadataAsset = findAssetBySuffix(assets, metadataAssetSuffix) ?: continue
            val metadata = fetchJsonObject(metadataAsset.optString("browser_download_url").trim())
            if (!metadata.optString("build_type").trim().equals(BuildConfig.BUILD_TYPE, ignoreCase = true)) {
                continue
            }

            val versionName = metadata.optString("version_name").trim()
            val hostVersion = metadata.optString("host_version").trim()
            val apkAssetName = metadata.optString("apk_asset_name").trim()
            val apkSha256 = metadata.optString("apk_sha256").trim().lowercase(Locale.US)
            if (
                versionName.isBlank() ||
                hostVersion.isBlank() ||
                apkAssetName.isBlank() ||
                apkSha256.length != 64 ||
                compareVersionNames(versionName, currentVersionName) <= 0
            ) {
                continue
            }

            val apkAsset = findAssetByName(assets, apkAssetName) ?: continue
            val apkDownloadUrl = apkAsset.optString("browser_download_url").trim()
            if (apkDownloadUrl.isBlank()) {
                continue
            }

            return AppUpdateStateStore.AvailableRelease(
                releaseTag = releaseTag,
                releaseTitle = releaseTitle,
                versionName = versionName,
                hostVersion = hostVersion,
                apkAssetName = apkAssetName,
                apkDownloadUrl = apkDownloadUrl,
                apkSha256 = apkSha256
            )
        }

        return null
    }

    private suspend fun startDownload(release: AppUpdateStateStore.AvailableRelease) {
        withContext(Dispatchers.IO) {
            resolveUpdateTargetFile(release.apkAssetName).delete()
        }

        val request = DownloadManager.Request(Uri.parse(release.apkDownloadUrl)).apply {
            setTitle(release.releaseTitle)
            setDescription(activity.getString(R.string.app_update_download_started))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setVisibleInDownloadsUi(true)
            setMimeType(apkMimeType)
            addRequestHeader("Accept", "application/octet-stream")
            addRequestHeader("User-Agent", userAgent)
            setDestinationUri(Uri.fromFile(resolveUpdateTargetFile(release.apkAssetName)))
        }

        val downloadId = downloadManager.enqueue(request)
        stateStore.downloadState = AppUpdateStateStore.DownloadState(
            downloadId = downloadId,
            releaseTag = release.releaseTag,
            releaseTitle = release.releaseTitle,
            versionName = release.versionName,
            hostVersion = release.hostVersion,
            apkAssetName = release.apkAssetName,
            apkDownloadUrl = release.apkDownloadUrl,
            apkSha256 = release.apkSha256,
            verifiedReadyToInstall = false
        )
        renderState()
        showMessage(R.string.app_update_download_started)
    }

    private suspend fun syncDownloadState(showErrors: Boolean, openInstallerIfReady: Boolean) {
        val currentDownload = stateStore.downloadState ?: run {
            renderState()
            return
        }

        when (queryDownloadRecord(currentDownload.downloadId)?.status) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_RUNNING -> {
                renderState()
            }

            DownloadManager.STATUS_SUCCESSFUL -> {
                verifyAndMaybeOpenDownload(currentDownload, openInstallerIfReady, showErrors)
            }

            else -> {
                clearDownloadState(removeDownload = true)
                if (showErrors) {
                    showMessage(R.string.app_update_download_failed)
                }
                renderState()
            }
        }
    }

    private suspend fun maybeOpenVerifiedDownload() {
        val currentDownload = stateStore.downloadState ?: return
        if (!currentDownload.verifiedReadyToInstall) {
            return
        }

        openVerifiedDownload(currentDownload)
    }

    private suspend fun verifyAndMaybeOpenDownload(
        downloadState: AppUpdateStateStore.DownloadState,
        openInstallerIfReady: Boolean,
        showErrors: Boolean
    ) {
        val currentState = if (downloadState.verifiedReadyToInstall) {
            downloadState
        } else {
            val verified = withContext(Dispatchers.IO) {
                computeDownloadedApkSha256(downloadState)?.equals(downloadState.apkSha256, ignoreCase = true) == true
            }
            if (!verified) {
                clearDownloadState(removeDownload = true)
                if (showErrors) {
                    showMessage(R.string.app_update_sha_failed)
                }
                renderState()
                return
            }

            downloadState.copy(verifiedReadyToInstall = true).also {
                stateStore.downloadState = it
            }
        }

        renderState()
        if (openInstallerIfReady) {
            openVerifiedDownload(currentState)
        }
    }

    private fun openVerifiedDownload(downloadState: AppUpdateStateStore.DownloadState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            showMessage(R.string.app_update_install_permission_required)
            return
        }

        val updateFile = resolveUpdateTargetFile(downloadState.apkAssetName)
        if (!updateFile.isFile) {
            clearDownloadState(removeDownload = true)
            showMessage(R.string.app_update_install_prepare_failed)
            renderState()
            return
        }

        val installUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            updateFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(installUri, apkMimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (installIntent.resolveActivity(activity.packageManager) == null) {
            showMessage(R.string.app_update_install_prepare_failed)
            return
        }

        activity.startActivity(installIntent)
    }

    private fun clearInstalledVersionState() {
        val currentVersionName = resolveCurrentVersionName()
        val availableRelease = stateStore.availableRelease
        if (availableRelease != null && compareVersionNames(availableRelease.versionName, currentVersionName) <= 0) {
            stateStore.availableRelease = null
        }

        val downloadState = stateStore.downloadState
        if (downloadState != null && compareVersionNames(downloadState.versionName, currentVersionName) <= 0) {
            clearDownloadState(removeDownload = false)
        }
    }

    private fun clearDownloadState(removeDownload: Boolean) {
        val currentDownload = stateStore.downloadState
        if (removeDownload && currentDownload != null) {
            runCatching { downloadManager.remove(currentDownload.downloadId) }
        }
        stateStore.downloadState = null
    }

    private fun registerDownloadReceiver() {
        if (receiverRegistered) {
            return
        }

        ContextCompat.registerReceiver(
            activity,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterDownloadReceiver() {
        if (!receiverRegistered) {
            return
        }

        activity.unregisterReceiver(downloadCompleteReceiver)
        receiverRegistered = false
    }

    private fun renderState() {
        val currentDownload = stateStore.downloadState
        val availableRelease = stateStore.availableRelease
        updateButtonContainer.isVisible = currentDownload != null || availableRelease != null
        updateBadgeView.isVisible = currentDownload == null && availableRelease != null
        updateButton.isEnabled = true
        updateButton.contentDescription = when {
            currentDownload?.verifiedReadyToInstall == true -> activity.getString(R.string.bootstrap_update_open)
            currentDownload != null -> activity.getString(R.string.app_update_download_running)
            availableRelease != null -> activity.getString(R.string.bootstrap_update_open)
            else -> activity.getString(R.string.bootstrap_update_open)
        }
    }

    private fun resolveUpdateTargetFile(apkAssetName: String): File {
        return File(updatesDirectory, apkAssetName)
    }

    private fun queryDownloadRecord(downloadId: Long): DownloadRecord? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }

            return DownloadRecord(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            )
        }
    }

    private fun computeDownloadedApkSha256(downloadState: AppUpdateStateStore.DownloadState): String? {
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

    private fun fetchJsonArray(url: String): JSONArray {
        return JSONArray(fetchText(url))
    }

    private fun fetchJsonObject(url: String): JSONObject {
        return JSONObject(fetchText(url))
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
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

    private fun findAssetBySuffix(assets: JSONArray, suffix: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").trim().endsWith(suffix)) {
                return asset
            }
        }
        return null
    }

    private fun findAssetByName(assets: JSONArray, name: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").trim() == name) {
                return asset
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun resolveCurrentVersionName(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(activity.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }
        return packageInfo.versionName.orEmpty().trim()
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

    private fun showMessage(stringResId: Int) {
        Toast.makeText(activity, stringResId, Toast.LENGTH_SHORT).show()
    }
}