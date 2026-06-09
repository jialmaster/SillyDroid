package com.jm.sillydroid.ui.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AppDownloadStatus
import com.jm.sillydroid.core.model.update.AppUpdateBuildConfig
import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import com.jm.sillydroid.core.model.update.AvailableAppRelease
import com.jm.sillydroid.domain.bootstrap.RuntimeMetadataRepository
import com.jm.sillydroid.domain.notification.HostDownloadNotificationCoordinator
import com.jm.sillydroid.domain.update.AppUpdateRepository
import com.jm.sillydroid.ui.update.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class AppUpdateCoordinator(
    private val activity: AppCompatActivity,
    private val appUpdateRepository: AppUpdateRepository,
    private val runtimeMetadataRepository: RuntimeMetadataRepository,
    private val buildConfig: AppUpdateBuildConfig,
    private val dispatchers: DispatcherProvider,
    private val hostDownloadNotificationCoordinator: HostDownloadNotificationCoordinator,
    private val overlayUi: OverlayUi? = null,
    private val aboutUi: AboutUi? = null
) : DefaultLifecycleObserver {
    data class OverlayUi(
        val container: View,
        val button: ImageButton,
        val badgeView: View,
        val settingsBadgeView: View? = null
    )

    data class AboutUi(
        val versionView: TextView,
        val statusView: TextView,
        val actionButton: MaterialButton
    )

    private data class AboutVersionInfo(
        val apkVersionName: String,
        val apkVersionCode: String,
        val runtimeVersion: String,
        val serverPayloadVersion: String
    )

    companion object {
        private const val apkMimeType = "application/vnd.android.package-archive"
    }

    private var syncJob: Job? = null

    fun initialize() {
        clearInstalledVersionState()
        overlayUi?.button?.setOnClickListener {
            activity.lifecycleScope.launch {
                handleUpdateAction()
            }
        }
        aboutUi?.actionButton?.setOnClickListener {
            activity.lifecycleScope.launch {
                handleUpdateAction()
            }
        }
        renderState()
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        syncJob?.cancel()
        syncJob = activity.lifecycleScope.launch {
            syncDownloadState(showErrors = false, openInstallerIfReady = false)
            syncCachedReleaseDownloadStateOnIo(verifyCompleteApk = true)
            checkForUpdatesOnStart()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
    }

    override fun onDestroy(owner: LifecycleOwner) {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun handleUpdateAction() {
        syncCachedReleaseDownloadState(verifyCompleteApk = false)
        val currentDownload = appUpdateRepository.cachedDownloadState()
        if (currentDownload != null) {
            when (currentDownload.status) {
                AppDownloadStatus.DOWNLOADING,
                AppDownloadStatus.PENDING,
                AppDownloadStatus.PAUSED,
                AppDownloadStatus.RUNNING -> {
                    showMessage(R.string.app_update_download_running)
                    return
                }

                AppDownloadStatus.READY_TO_INSTALL,
                AppDownloadStatus.SUCCESSFUL -> {
                    verifyAndMaybeOpenDownload(currentDownload, openInstallerIfReady = true, showErrors = true)
                    return
                }

                AppDownloadStatus.RESUMABLE,
                AppDownloadStatus.STALLED,
                AppDownloadStatus.FAILED,
                AppDownloadStatus.MISSING -> Unit
            }

            if (currentDownload.status == AppDownloadStatus.MISSING) {
                clearDownloadState(removeDownload = false)
            }
        }

        val cachedRelease = appUpdateRepository.cachedAvailableRelease()
        if (cachedRelease == null) {
            // 手动“检查更新”只刷新 latest 缓存和 UI，不在同一次点击里直接开始下载；
            // 发现新版本后按钮会切换成“下载更新”，由用户下一次明确点击触发下载。
            val checkSucceeded = checkForUpdates(silent = false)
            if (checkSucceeded && appUpdateRepository.cachedAvailableRelease() == null) {
                showMessage(R.string.app_update_no_updates)
            }
            return
        }

        withContext(dispatchers.io) {
            appUpdateRepository.cleanupDownloadCache(cachedRelease)
            appUpdateRepository.inspectDownloadState(cachedRelease, verifyCompleteApk = true)
                .let(appUpdateRepository::updateDownloadProgress)
        }.also { downloadState ->
            if (downloadState?.verifiedReadyToInstall == true) {
                verifyAndMaybeOpenDownload(downloadState, openInstallerIfReady = true, showErrors = true)
                return
            }
        }
        renderState()
        startAppUpdateDownloadService()
        showMessage(R.string.app_update_download_started)
    }

    private suspend fun checkForUpdates(silent: Boolean): Boolean {
        val result = withContext(dispatchers.io) {
            runCatching {
                appUpdateRepository.fetchLatestAvailableRelease(
                    AppUpdateRequestConfig(
                        latestReleaseMetadataUrl = buildConfig.latestReleaseMetadataUrl,
                        buildType = buildConfig.buildType,
                        currentVersionName = resolveCurrentVersionName()
                    )
                )
            }
        }

        result.onSuccess { release ->
            appUpdateRepository.checkErrorMessage = null
            if (release == null) {
                if (appUpdateRepository.cachedDownloadState() == null) {
                    appUpdateRepository.clearAvailableRelease()
                }
            } else {
                appUpdateRepository.cacheAvailableRelease(release)
            }
            if (release != null) {
                appUpdateRepository.cleanupDownloadCache(release)
                appUpdateRepository.inspectDownloadState(release, verifyCompleteApk = true)
                    .let(appUpdateRepository::updateDownloadProgress)
            }
            renderState()
        }.onFailure { exception ->
            val errorMessage = formatUpdateCheckError(exception)
            if (!silent) {
                appUpdateRepository.checkErrorMessage = errorMessage
                showMessage(activity.getString(R.string.app_update_check_failed_with_reason, errorMessage))
            }
            renderState()
        }

        return result.isSuccess
    }

    private suspend fun checkForUpdatesOnStart() {
        if (appUpdateRepository.cachedDownloadState() != null) {
            return
        }

        // 启动时自动刷新 latest 指针，让主界面右上角更新入口能及时显示红点和提示文案。
        checkForUpdates(silent = true)
    }

    private suspend fun syncDownloadState(showErrors: Boolean, openInstallerIfReady: Boolean) {
        val currentDownload = appUpdateRepository.cachedDownloadState() ?: run {
            renderState()
            return
        }

        when (queryDownloadStatus(currentDownload.downloadId)) {
            AppDownloadStatus.DOWNLOADING,
            AppDownloadStatus.RESUMABLE,
            AppDownloadStatus.PENDING,
            AppDownloadStatus.PAUSED,
            AppDownloadStatus.RUNNING -> {
                hostDownloadNotificationCoordinator.refreshAppUpdateDownload(currentDownload)
                renderState()
            }

            AppDownloadStatus.READY_TO_INSTALL,
            AppDownloadStatus.SUCCESSFUL -> {
                hostDownloadNotificationCoordinator.refreshAppUpdateDownload(currentDownload)
                verifyAndMaybeOpenDownload(currentDownload, openInstallerIfReady, showErrors)
            }

            AppDownloadStatus.STALLED,
            AppDownloadStatus.FAILED,
            AppDownloadStatus.MISSING -> {
                hostDownloadNotificationCoordinator.postAppUpdateDownloadFailed(
                    currentDownload.versionName,
                    currentDownload.failureReason
                )
                if (!currentDownload.resumable) {
                    clearDownloadState(removeDownload = false)
                }
                if (showErrors) {
                    showMessage(R.string.app_update_download_failed)
                }
                renderState()
            }
        }
    }

    private suspend fun verifyAndMaybeOpenDownload(
        downloadState: AppDownloadState,
        openInstallerIfReady: Boolean,
        showErrors: Boolean
    ) {
        val currentState = if (downloadState.verifiedReadyToInstall) {
            downloadState
        } else {
            val verified = withContext(dispatchers.io) {
                appUpdateRepository.verifyDownloadedApk(downloadState)
            }
            if (!verified) {
                hostDownloadNotificationCoordinator.postAppUpdateDownloadFailed(downloadState.versionName)
                clearDownloadState(removeDownload = true)
                if (showErrors) {
                    showMessage(R.string.app_update_sha_failed)
                }
                renderState()
                return
            }

            withContext(dispatchers.io) {
                appUpdateRepository.markDownloadVerified(downloadState)
            }
        }

        renderState()
        hostDownloadNotificationCoordinator.postAppUpdateReadyToInstall(
            apkPath = appUpdateRepository.downloadedApkPath(currentState),
            canRequestPackageInstalls = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                activity.packageManager.canRequestPackageInstalls()
        )
        if (openInstallerIfReady) {
            openVerifiedDownload(currentState)
        }
    }

    private fun openVerifiedDownload(downloadState: AppDownloadState) {
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

        val updateFile = File(appUpdateRepository.downloadedApkPath(downloadState))
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

        // 5.2: Main 与 Settings 两个 Activity 同时收到完成广播时，
        // 只让进程内首个 claim 成功的调用者拉起安装器，
        // 避免重复弹出系统安装页。
        if (!appUpdateRepository.claimInstallerLaunch(downloadState.downloadId)) {
            return
        }

        activity.startActivity(installIntent)
    }

    private fun clearInstalledVersionState() {
        val currentVersionName = resolveCurrentVersionName()
        val availableRelease = appUpdateRepository.cachedAvailableRelease()
        if (availableRelease != null && compareVersionNames(availableRelease.versionName, currentVersionName) <= 0) {
            appUpdateRepository.clearAvailableRelease()
        }

        val downloadState = appUpdateRepository.cachedDownloadState()
        if (downloadState != null && compareVersionNames(downloadState.versionName, currentVersionName) <= 0) {
            clearDownloadState(removeDownload = false)
        }
    }

    private fun clearDownloadState(removeDownload: Boolean) {
        appUpdateRepository.clearDownloadState(removeDownload)
        hostDownloadNotificationCoordinator.clearAppUpdateNotifications()
    }

    private fun renderState() {
        syncCachedReleaseDownloadState(verifyCompleteApk = false)
        val currentDownload = appUpdateRepository.cachedDownloadState()
        val availableRelease = appUpdateRepository.cachedAvailableRelease()
        val showOverlayUpdateEntry = currentDownload != null || availableRelease != null
        overlayUi?.container?.isVisible = showOverlayUpdateEntry
        overlayUi?.badgeView?.isVisible = showOverlayUpdateEntry
        overlayUi?.settingsBadgeView?.isVisible = showOverlayUpdateEntry
        overlayUi?.button?.isEnabled = true
        overlayUi?.button?.contentDescription = when {
            currentDownload?.verifiedReadyToInstall == true -> activity.getString(R.string.bootstrap_update_open)
            currentDownload != null -> activity.getString(R.string.app_update_download_running)
            availableRelease != null -> activity.getString(R.string.bootstrap_update_open)
            else -> activity.getString(R.string.bootstrap_update_open)
        }

        aboutUi?.let { ui ->
            val aboutVersionInfo = resolveAboutVersionInfo()
            val checkErrorMessage = appUpdateRepository.checkErrorMessage
            ui.versionView.text = buildString {
                append(activity.getString(R.string.bootstrap_settings_about_version_apk, aboutVersionInfo.apkVersionName))
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_about_version_code, aboutVersionInfo.apkVersionCode))
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_about_version_host, buildConfig.hostVersion))
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_about_version_runtime, aboutVersionInfo.runtimeVersion))
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_about_version_payload, aboutVersionInfo.serverPayloadVersion))
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_about_version_build, buildConfig.buildType.uppercase(Locale.US)))
            }

            val statusText: String
            val actionText: String
            val actionEnabled: Boolean
            when {
                currentDownload?.verifiedReadyToInstall == true -> {
                    statusText = activity.getString(
                        R.string.bootstrap_settings_about_update_status_ready,
                        currentDownload.versionName
                    )
                    actionText = activity.getString(R.string.bootstrap_settings_about_update_action_install)
                    actionEnabled = true
                }

                currentDownload != null -> {
                    statusText = activity.getString(
                        if (currentDownload.resumable &&
                            (currentDownload.status == AppDownloadStatus.RESUMABLE ||
                                currentDownload.status == AppDownloadStatus.FAILED ||
                                currentDownload.status == AppDownloadStatus.STALLED)
                        ) {
                            R.string.bootstrap_settings_about_update_status_resumable
                        } else {
                            R.string.bootstrap_settings_about_update_status_downloading
                        },
                        currentDownload.versionName
                    )
                    actionText = if (currentDownload.resumable &&
                        (currentDownload.status == AppDownloadStatus.RESUMABLE ||
                            currentDownload.status == AppDownloadStatus.FAILED ||
                            currentDownload.status == AppDownloadStatus.STALLED)
                    ) {
                        activity.getString(R.string.bootstrap_settings_about_update_action_resume_download)
                    } else {
                        activity.getString(R.string.bootstrap_settings_about_update_action_downloading)
                    }
                    actionEnabled = currentDownload.resumable &&
                        (currentDownload.status == AppDownloadStatus.RESUMABLE ||
                            currentDownload.status == AppDownloadStatus.FAILED ||
                            currentDownload.status == AppDownloadStatus.STALLED)
                }

                availableRelease != null -> {
                    statusText = appendReleaseNotes(
                        baseText = activity.getString(
                            R.string.bootstrap_settings_about_update_status_available,
                            availableRelease.versionName
                        ),
                        releaseNotesMarkdown = availableRelease.releaseNotesMarkdown
                    )
                    actionText = activity.getString(R.string.bootstrap_settings_about_update_action_download)
                    actionEnabled = true
                }

                !checkErrorMessage.isNullOrBlank() -> {
                    statusText = activity.getString(
                        R.string.bootstrap_settings_about_update_status_failed,
                        checkErrorMessage
                    )
                    actionText = activity.getString(R.string.bootstrap_settings_about_update_action_check)
                    actionEnabled = true
                }

                else -> {
                    statusText = activity.getString(R.string.bootstrap_settings_about_update_status_idle)
                    actionText = activity.getString(R.string.bootstrap_settings_about_update_action_check)
                    actionEnabled = true
                }
            }

            ui.statusView.text = statusText
            ui.actionButton.text = actionText
            ui.actionButton.isEnabled = actionEnabled
        }
    }

    private fun formatUpdateCheckError(exception: Throwable): String {
        return formatAppUpdateCheckError(
            exception = exception,
            unknownFallback = activity.getString(R.string.app_update_check_failed_unknown_reason)
        )
    }

    private fun appendReleaseNotes(baseText: String, releaseNotesMarkdown: String?): String {
        val notes = releaseNotesMarkdown?.trim()?.ifBlank { null } ?: return baseText
        return baseText + "\n\n" + activity.getString(R.string.bootstrap_settings_about_update_notes, notes)
    }

    private suspend fun queryDownloadStatus(downloadId: Long): AppDownloadStatus {
        return withContext(dispatchers.io) {
            appUpdateRepository.queryDownloadRecord(downloadId).status
        }
    }

    private fun syncCachedReleaseDownloadState(verifyCompleteApk: Boolean) {
        val release = appUpdateRepository.cachedAvailableRelease() ?: return
        val currentDownload = appUpdateRepository.cachedDownloadState()
        if (currentDownload != null && currentDownload.taskKey != release.taskKey()) {
            clearDownloadState(removeDownload = false)
        }
        if (currentDownload?.status == AppDownloadStatus.DOWNLOADING ||
            currentDownload?.status == AppDownloadStatus.PENDING ||
            currentDownload?.status == AppDownloadStatus.PAUSED ||
            currentDownload?.status == AppDownloadStatus.RUNNING
        ) {
            return
        }
        if (!verifyCompleteApk && currentDownload?.verifiedReadyToInstall == true) {
            return
        }
        val record = appUpdateRepository.inspectDownloadState(release, verifyCompleteApk = verifyCompleteApk)
        if (record.status != AppDownloadStatus.MISSING) {
            appUpdateRepository.updateDownloadProgress(record)
        }
    }

    private fun AvailableAppRelease.taskKey(): com.jm.sillydroid.core.model.update.AppDownloadTaskKey {
        return com.jm.sillydroid.core.model.update.AppDownloadTaskKey(
            releaseTag = releaseTag,
            versionName = versionName,
            apkAssetName = apkAssetName,
            apkDownloadUrl = apkDownloadUrl,
            apkSha256 = apkSha256
        )
    }

    private suspend fun syncCachedReleaseDownloadStateOnIo(verifyCompleteApk: Boolean) {
        withContext(dispatchers.io) {
            val release = appUpdateRepository.cachedAvailableRelease() ?: return@withContext
            appUpdateRepository.cleanupDownloadCache(release)
            appUpdateRepository.inspectDownloadState(release, verifyCompleteApk = verifyCompleteApk)
                .let(appUpdateRepository::updateDownloadProgress)
        }
        renderState()
    }

    private fun resolveAboutVersionInfo(): AboutVersionInfo {
        val packageInfo = resolveCurrentPackageInfo()
        val apkVersionName = packageInfo.versionName.orEmpty().trim().ifBlank {
            activity.getString(R.string.bootstrap_settings_about_version_unknown)
        }
        val apkVersionCode = packageInfo.longVersionCode.toString()
        val unknownVersion = activity.getString(R.string.bootstrap_settings_about_version_unknown)

        return AboutVersionInfo(
            apkVersionName = apkVersionName,
            apkVersionCode = apkVersionCode,
            runtimeVersion = runtimeMetadataRepository.resolveRuntimeVersionLabel() ?: unknownVersion,
            serverPayloadVersion = runtimeMetadataRepository.resolveServerPayloadVersionLabel(
                upstreamVersion = buildConfig.upstreamVersion,
                currentVersionName = packageInfo.versionName.orEmpty()
            ) ?: unknownVersion
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveCurrentPackageInfo(): android.content.pm.PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(activity.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }
    }

    private fun resolveCurrentVersionName(): String {
        return resolveCurrentPackageInfo().versionName.orEmpty().trim()
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

    private fun showMessage(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun startAppUpdateDownloadService() {
        val intent = Intent().setClassName(activity, "com.jm.sillydroid.AppUpdateDownloadService")
            .setAction("com.jm.sillydroid.action.UPDATE_DOWNLOAD_START")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }
}
