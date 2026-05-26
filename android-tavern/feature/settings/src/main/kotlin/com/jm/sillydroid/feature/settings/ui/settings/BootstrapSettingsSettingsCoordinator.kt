package com.jm.sillydroid.feature.settings.ui.settings

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jm.sillydroid.core.model.settings.LoadedTavernConfig
import com.jm.sillydroid.domain.settings.SettingsConfigRepository
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.BootstrapSettingsValidationIssue
import com.jm.sillydroid.feature.settings.model.SettingsNavigationPolicy
import com.jm.sillydroid.feature.settings.ui.form.BootstrapSettingsFormController
import com.jm.sillydroid.feature.settings.ui.screen.BootstrapSettingsScreenController
import com.jm.sillydroid.feature.settings.validation.BootstrapSettingsFormValidator
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootstrapSettingsSettingsCoordinator(
    private val activity: AppCompatActivity,
    private val dispatchers: DispatcherProvider,
    private val configRepository: SettingsConfigRepository,
    private val formController: BootstrapSettingsFormController,
    private val screenController: BootstrapSettingsScreenController,
    private val stopBootstrapForSettings: suspend (String) -> Unit,
    private val defaultServicePort: Int,
    private val onStartBootstrapConfirmed: () -> Unit
) {
    private data class SaveAndRestartResult(
        val configSaved: Boolean,
        val failureMessage: String?
    )

    private var currentRoot = linkedMapOf<String, Any?>()
    private var initialFormSnapshot = ""

    fun loadConfiguration(infoMessage: String? = null) {
        activity.lifecycleScope.launch {
            screenController.setBusy(true)
            val result = withContext(dispatchers.io) {
                runCatching { configRepository.loadConfig() }
            }
            result.onSuccess { loadedConfig ->
                applyLoadedConfig(loadedConfig, resetSnapshot = true)
                when {
                    !infoMessage.isNullOrBlank() -> screenController.showBanner(infoMessage)
                    !loadedConfig.warningMessage.isNullOrBlank() -> screenController.showBanner(loadedConfig.warningMessage)
                    else -> screenController.showBanner(null)
                }
            }.onFailure { exception ->
                screenController.showBanner(exception.message ?: activity.getString(R.string.bootstrap_settings_load_config_failed))
            }
            screenController.setBusy(false)
        }
    }

    fun applyDraftConfiguration(loadedConfig: LoadedTavernConfig) {
        applyLoadedConfig(loadedConfig, resetSnapshot = false)
    }

    fun replaceLoadedConfiguration(loadedConfig: LoadedTavernConfig, infoMessage: String? = null) {
        applyLoadedConfig(loadedConfig, resetSnapshot = true)
        when {
            !infoMessage.isNullOrBlank() -> screenController.showBanner(infoMessage)
            !loadedConfig.warningMessage.isNullOrBlank() -> screenController.showBanner(loadedConfig.warningMessage)
            else -> screenController.showBanner(null)
        }
    }

    fun saveAndStart() {
        clearBlockingFeedback()
        val typedValues = collectTypedValuesOrShowError() ?: return
        val updatedRoot = buildUpdatedRoot(typedValues)

        val validationIssue = validateUpdatedRoot(updatedRoot, typedValues)
        if (validationIssue != null) {
            showValidationIssue(validationIssue)
            return
        }

        saveUpdatedRootAndRestart(updatedRoot)
    }

    fun currentTypedValues(): LinkedHashMap<String, Any?>? {
        return collectTypedValuesOrShowError()
    }

    fun applyProgrammaticValues(valuesByPath: Map<String, Any?>) {
        formController.applyProgrammaticValues(valuesByPath)
    }

    private fun collectTypedValuesOrShowError(): LinkedHashMap<String, Any?>? {
        return try {
            formController.currentTypedValues(BootstrapSettingsFormValidator::coerceFieldValue)
        } catch (exception: IllegalArgumentException) {
            screenController.showMessage(exception.message ?: activity.getString(R.string.bootstrap_settings_validation_failed))
            null
        }
    }

    private fun buildUpdatedRoot(typedValues: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
        val updatedRoot = configRepository.copyRoot(currentRoot)

        for ((fieldPath, typedValue) in typedValues) {
            configRepository.writeValue(updatedRoot, fieldPath, typedValue)
        }
        return updatedRoot
    }

    private fun validateUpdatedRoot(
        updatedRoot: LinkedHashMap<String, Any?>,
        typedValues: Map<String, Any?>
    ): BootstrapSettingsValidationIssue? {
        return BootstrapSettingsFormValidator.validate(
            values = typedValues,
            defaultServicePort = defaultServicePort
        )
            ?: configRepository.validateConfig(updatedRoot)?.let { message ->
                BootstrapSettingsValidationIssue(message = message)
            }
    }

    private fun saveUpdatedRootAndRestart(updatedRoot: LinkedHashMap<String, Any?>) {
        activity.lifecycleScope.launch {
            screenController.setBusy(true)
            val saveResult = withContext(dispatchers.io) {
                var configSaved = false
                val failureMessage = runCatching {
                    configRepository.saveConfig(updatedRoot)
                    configSaved = true
                    stopBootstrapForSettings(activity.getString(R.string.bootstrap_settings_restart_stop_timeout))
                }.exceptionOrNull()?.message
                SaveAndRestartResult(configSaved = configSaved, failureMessage = failureMessage)
            }
            screenController.setBusy(false)

            if (saveResult.configSaved) {
                currentRoot = updatedRoot
                initialFormSnapshot = formController.captureSnapshot()
                refreshDirtyState()
            }

            if (saveResult.failureMessage != null) {
                showValidationIssue(BootstrapSettingsValidationIssue(message = saveResult.failureMessage))
                return@launch
            }

            onStartBootstrapConfirmed()
        }
    }

    fun attemptFinish() {
        if (!SettingsNavigationPolicy.canFinish(screenController.isBusy())) {
            screenController.showMessage(activity.getString(R.string.bootstrap_settings_busy_return_blocked))
            return
        }

        if (!hasUnsavedChanges()) {
            activity.finish()
            return
        }

        screenController.confirmDiscardChanges {
            activity.finish()
        }
    }

    fun refreshDirtyState() {
        screenController.updateDirtyState(hasUnsavedChanges())
    }

    fun clearBlockingFeedback(changedFieldPath: String? = null) {
        formController.clearFieldErrors(changedFieldPath)
        screenController.clearErrorBanner()
    }

    fun showValidationMessage(message: String) {
        showValidationIssue(BootstrapSettingsValidationIssue(message = message))
    }

    private fun showValidationIssue(issue: BootstrapSettingsValidationIssue) {
        issue.fieldPath?.let { fieldPath ->
            screenController.focusValidationTab(formController.isQuickField(fieldPath))
        }
        formController.showValidationIssue(issue)
        screenController.showBanner(issue.message, isError = true)
        screenController.showMessage(issue.message)
    }

    private fun applyLoadedConfig(loadedConfig: LoadedTavernConfig, resetSnapshot: Boolean) {
        currentRoot = loadedConfig.root
        screenController.setConfigPath(loadedConfig.filePath)
        formController.render(loadedConfig.root)
        if (resetSnapshot) {
            initialFormSnapshot = formController.captureSnapshot()
        }
        refreshDirtyState()
    }

    private fun hasUnsavedChanges(): Boolean {
        return formController.captureSnapshot() != initialFormSnapshot
    }
}
