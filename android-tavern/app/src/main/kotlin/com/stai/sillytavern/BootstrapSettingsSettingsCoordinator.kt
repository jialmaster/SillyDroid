package com.stai.sillytavern

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BootstrapSettingsSettingsCoordinator(
    private val activity: AppCompatActivity,
    private val configRepository: TavernConfigRepository,
    private val formController: BootstrapSettingsFormController,
    private val screenController: BootstrapSettingsScreenController,
    private val onStartBootstrapConfirmed: () -> Unit
) {
    private var currentRoot = linkedMapOf<String, Any?>()
    private var initialFormSnapshot = ""

    fun loadConfiguration(infoMessage: String? = null) {
        activity.lifecycleScope.launch {
            screenController.setBusy(true)
            val loadedConfig = withContext(Dispatchers.IO) {
                configRepository.loadConfig()
            }
            applyLoadedConfig(loadedConfig, resetSnapshot = true)
            when {
                !infoMessage.isNullOrBlank() -> screenController.showBanner(infoMessage)
                !loadedConfig.warningMessage.isNullOrBlank() -> screenController.showBanner(loadedConfig.warningMessage)
                else -> screenController.showBanner(null)
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
        val updatedRoot = configRepository.copyRoot(currentRoot)
        val typedValues = try {
            formController.collectTypedValues(BootstrapSettingsFormValidator::coerceFieldValue)
        } catch (exception: IllegalArgumentException) {
            screenController.showMessage(exception.message ?: activity.getString(R.string.bootstrap_settings_validation_failed))
            return
        }

        for ((fieldPath, typedValue) in typedValues) {
            configRepository.writeValue(updatedRoot, fieldPath, typedValue)
        }

        val validationIssue = BootstrapSettingsFormValidator.validate(typedValues)
            ?: configRepository.validateConfig(updatedRoot)?.let { message ->
                BootstrapSettingsValidationIssue(message = message)
            }
        if (validationIssue != null) {
            showValidationIssue(validationIssue)
            return
        }

        activity.lifecycleScope.launch {
            screenController.setBusy(true)
            val saveError = withContext(Dispatchers.IO) {
                runCatching {
                    configRepository.saveConfig(updatedRoot)
                }.exceptionOrNull()?.message
            }
            screenController.setBusy(false)

            if (saveError != null) {
                showValidationIssue(BootstrapSettingsValidationIssue(message = saveError))
                return@launch
            }

            currentRoot = updatedRoot
            initialFormSnapshot = formController.captureSnapshot()
            refreshDirtyState()
            onStartBootstrapConfirmed()
        }
    }

    fun attemptFinish() {
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