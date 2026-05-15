package com.jm.sillydroid.feature.settings.ui.data

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jm.sillydroid.core.model.settings.LoadedTavernConfig
import com.jm.sillydroid.core.model.settings.TavernDataArchiveKind
import com.jm.sillydroid.core.model.settings.TavernDataArchivePreview
import com.jm.sillydroid.core.model.settings.TavernDataImportResult
import com.jm.sillydroid.domain.settings.DataArchiveRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.domain.settings.SettingsConfigRepository
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootstrapSettingsDataCoordinator(
    private val activity: AppCompatActivity,
    private val dispatchers: DispatcherProvider,
    private val configRepository: SettingsConfigRepository,
    private val archiveManager: DataArchiveRepository,
    private val hostConfigStore: HostPreferencesRepository,
    private val stopBootstrapForSettings: suspend (String) -> Unit,
    private val defaultServicePort: Int,
    private val setBusy: (Boolean) -> Unit,
    private val applyDraft: (LoadedTavernConfig) -> Unit,
    private val replaceLoadedConfiguration: (LoadedTavernConfig, String?) -> Unit,
    private val showDataError: (String) -> Unit,
    private val showBanner: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val updateDirtyState: () -> Unit,
    private val restartBootstrap: () -> Unit,
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
            val loadedConfig = withContext(dispatchers.io) {
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
            val result = withContext(dispatchers.io) {
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
            val result = withContext(dispatchers.io) {
                runCatching {
                    stopBootstrapForSettings(activity.getString(R.string.bootstrap_settings_import_stop_timeout))
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
                    restartBootstrap()
                }
                showDataError(exception.message ?: activity.getString(R.string.bootstrap_settings_import_failed))
            }
        }
    }

    fun clearDataAndRestart(onRestart: () -> Unit) {
        activity.lifecycleScope.launch {
            setBusy(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    stopBootstrapForSettings(activity.getString(R.string.bootstrap_settings_import_stop_timeout))
                    archiveManager.clearManagedBootstrapState()
                    hostConfigStore.servicePort = defaultServicePort
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
            val result = withContext(dispatchers.io) {
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
}
