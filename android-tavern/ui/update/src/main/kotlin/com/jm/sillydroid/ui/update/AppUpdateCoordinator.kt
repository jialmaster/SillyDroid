package com.jm.sillydroid.ui.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.jm.sillydroid.domain.bootstrap.RuntimeMetadataRepository
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
    private val overlayUi: OverlayUi? = null,
    private val aboutUi: AboutUi? = null
) : DefaultLifecycleObserver {
    data class OverlayUi(
        val container: View,
        val button: ImageButton,
        val badgeView: View
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

    private var receiverRegistered = false
    private var syncJob: Job? = null

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (completedId != appUpdateRepository.cachedDownloadState()?.downloadId) {
                return
            }

            activity.lifecycleScope.launch {
                syncDownloadState(showErrors = true, openInstallerIfReady = true)
            }
        }
    }

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
        registerDownloadReceiver()
        activity.lifecycleScope.launch {
            syncDownloadState(showErrors = false, openInstallerIfReady = false)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        unregisterDownloadReceiver()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun handleUpdateAction() {
        val currentDownload = appUpdateRepository.cachedDownloadState()
        if (currentDownload != null) {
            when (queryDownloadStatus(currentDownload.downloadId)) {
                AppDownloadStatus.PENDING,
                AppDownloadStatus.PAUSED,
                AppDownloadStatus.RUNNING -> {
                    showMessage(R.string.app_update_download_running)
                    return
                }

                AppDownloadStatus.SUCCESSFUL -> {
                    verifyAndMaybeOpenDownload(currentDownload, openInstallerIfReady = true, showErrors = true)
                    return
                }

                AppDownloadStatus.FAILED,
                AppDownloadStatus.MISSING -> Unit
            }

            clearDownloadState(removeDownload = true)
        }

        var checkSucceeded = true
        val availableRelease = appUpdateRepository.cachedAvailableRelease() ?: run {
            checkSucceeded = checkForUpdates(silent = false)
            appUpdateRepository.cachedAvailableRelease()
        }

        if (availableRelease == null) {
            if (checkSucceeded) {
                showMessage(R.string.app_update_no_updates)
            }
            return
        }

        withContext(dispatchers.io) {
            appUpdateRepository.startDownload(availableRelease)
        }
        renderState()
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
            renderState()
        }.onFailure { exception ->
            val errorMessage = formatUpdateCheckError(exception)
            appUpdateRepository.checkErrorMessage = errorMessage
            if (!silent) {
                showMessage(activity.getString(R.string.app_update_check_failed_with_reason, errorMessage))
            }
            renderState()
        }

        return result.isSuccess
    }

    private suspend fun syncDownloadState(showErrors: Boolean, openInstallerIfReady: Boolean) {
        val currentDownload = appUpdateRepository.cachedDownloadState() ?: run {
            renderState()
            return
        }

        when (queryDownloadStatus(currentDownload.downloadId)) {
            AppDownloadStatus.PENDING,
            AppDownloadStatus.PAUSED,
            AppDownloadStatus.RUNNING -> {
                renderState()
            }

            AppDownloadStatus.SUCCESSFUL -> {
                verifyAndMaybeOpenDownload(currentDownload, openInstallerIfReady, showErrors)
            }

            AppDownloadStatus.FAILED,
            AppDownloadStatus.MISSING -> {
                clearDownloadState(removeDownload = true)
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
        val currentDownload = appUpdateRepository.cachedDownloadState()
        val availableRelease = appUpdateRepository.cachedAvailableRelease()
        overlayUi?.container?.isVisible = false
        overlayUi?.badgeView?.isVisible = false
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
                        R.string.bootstrap_settings_about_update_status_downloading,
                        currentDownload.versionName
                    )
                    actionText = activity.getString(R.string.bootstrap_settings_about_update_action_downloading)
                    actionEnabled = false
                }

                availableRelease != null -> {
                    statusText = activity.getString(
                        R.string.bootstrap_settings_about_update_status_available,
                        availableRelease.versionName
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

    private suspend fun queryDownloadStatus(downloadId: Long): AppDownloadStatus {
        return withContext(dispatchers.io) {
            appUpdateRepository.queryDownloadRecord(downloadId).status
        }
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
}
