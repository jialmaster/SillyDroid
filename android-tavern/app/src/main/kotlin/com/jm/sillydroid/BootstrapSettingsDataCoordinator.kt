package com.jm.sillydroid

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BootstrapSettingsDataCoordinator(
    private val activity: AppCompatActivity,
    private val configRepository: TavernConfigRepository,
    private val archiveManager: TavernDataArchiveManager,
    private val hostConfigStore: BootstrapHostConfigStore,
    private val processManager: HostProcessManager,
    private val setBusy: (Boolean) -> Unit,
    private val applyDraft: (LoadedTavernConfig) -> Unit,
    private val replaceLoadedConfiguration: (LoadedTavernConfig, String?) -> Unit,
    private val showDataError: (String) -> Unit,
    private val showBanner: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val updateDirtyState: () -> Unit,
    private val onBootstrapRestartRequired: () -> Unit
) {
    private data class ImportArchiveOutcome(
        val importResult: TavernDataImportResult,
        val importedPort: Int,
        val loadedConfig: LoadedTavernConfig
    )

    fun restoreDefaults() {
        activity.lifecycleScope.launch {
            setBusy(true)
            val loadedConfig = withContext(Dispatchers.IO) {
                configRepository.loadDefaultConfig()
            }
            applyDraft(loadedConfig)
            showBanner(activity.getString(R.string.bootstrap_settings_restore_defaults_success))
            updateDirtyState()
            setBusy(false)
        }
    }

    fun inspectArchive(sourceUri: Uri, onReady: (TavernDataArchivePreview) -> Unit) {
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    archiveManager.inspectDataArchive(sourceUri)
                }
            }
            setBusy(false)

            result.onSuccess(onReady)
                .onFailure { exception ->
                    showDataError(exception.message ?: activity.getString(R.string.bootstrap_settings_import_failed))
                }
        }
    }

    fun importArchive(sourceUri: Uri, preview: TavernDataArchivePreview) {
        activity.lifecycleScope.launch {
            setBusy(true)
            val previousPort = hostConfigStore.servicePort
            var serviceWasStopped = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    processManager.stopForSettingsAndAwait(activity.getString(R.string.bootstrap_settings_import_stop_timeout))
                    serviceWasStopped = true
                    val importResult = archiveManager.importDataArchive(sourceUri)
                    val loadedConfig = configRepository.loadConfig()
                    val importedPort = configRepository.readConfiguredPort(loadedConfig.root)
                    ImportArchiveOutcome(importResult, importedPort, loadedConfig)
                }
            }
            setBusy(false)

            result.onSuccess { outcome ->
                val successMessage = buildImportMessage(
                    preview,
                    outcome.importResult,
                    previousPort,
                    outcome.importedPort
                )
                replaceLoadedConfiguration(
                    outcome.loadedConfig,
                    successMessage
                )
                showMessage(successMessage)
                onBootstrapRestartRequired()
            }.onFailure { exception ->
                if (serviceWasStopped) {
                    processManager.restart()
                }
                showDataError(exception.message ?: activity.getString(R.string.bootstrap_settings_import_failed))
            }
        }
    }

    fun clearDataAndRestart(onRestart: () -> Unit) {
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    processManager.stopForSettingsAndAwait(activity.getString(R.string.bootstrap_settings_import_stop_timeout))
                    clearManagedBootstrapState()
                    hostConfigStore.servicePort = BootConfig.defaultServicePort
                }
            }
            setBusy(false)

            result.onSuccess {
                onRestart()
            }.onFailure { exception ->
                showDataError(exception.message ?: activity.getString(R.string.bootstrap_settings_clear_data_failed))
            }
        }
    }

    fun exportArchive(targetUri: Uri) {
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    archiveManager.exportDataArchive(targetUri)
                }
            }
            setBusy(false)

            result.onSuccess {
                val successMessage = activity.getString(R.string.bootstrap_settings_export_success)
                showBanner(successMessage)
                showMessage(successMessage)
            }.onFailure { exception ->
                showDataError(exception.message ?: activity.getString(R.string.bootstrap_settings_export_failed))
            }
        }
    }

    private fun buildImportMessage(
        preview: TavernDataArchivePreview,
        importResult: TavernDataImportResult,
        previousPort: Int,
        importedPort: Int
    ): String {
        val baseMessage = when (importResult.archiveKind) {
            TavernDataArchiveKind.USER_BACKUP -> activity.getString(
                R.string.bootstrap_settings_import_success_user,
                importResult.importedFileCount,
                preview.sourceUserId ?: activity.getString(R.string.bootstrap_settings_import_unknown_user),
                preview.targetUserId ?: activity.getString(R.string.bootstrap_settings_import_unknown_user)
            )

            TavernDataArchiveKind.HOST_FULL_SNAPSHOT -> activity.getString(
                R.string.bootstrap_settings_import_success_host,
                importResult.importedFileCount
            )
        }

        return if (importedPort != previousPort) {
            baseMessage + "\n" + activity.getString(
                R.string.bootstrap_settings_import_port_changed_suffix,
                previousPort,
                importedPort
            )
        } else {
            baseMessage
        }
    }

    private fun clearManagedBootstrapState() {
        val paths = HostPaths.from(activity)
        paths.ensureWorkingDirectories()
        File(paths.serverDataDir, ".sillydroid-maintenance").deleteRecursively()
        paths.serverDir.deleteRecursively()
        for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
            File(paths.serverDataDir, directoryName).deleteRecursively()
        }
    }
}
