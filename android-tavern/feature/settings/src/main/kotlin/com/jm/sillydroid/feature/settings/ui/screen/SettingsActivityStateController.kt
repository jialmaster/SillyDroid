package com.jm.sillydroid.feature.settings.ui.screen

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.jm.sillydroid.feature.settings.model.SettingsActivityUiState
import com.jm.sillydroid.feature.settings.viewmodel.SettingsActivityViewModel
import kotlinx.coroutines.launch

class SettingsActivityStateController(
    private val activity: AppCompatActivity,
    private val viewModel: SettingsActivityViewModel,
    private val floatingLogsSwitch: MaterialSwitch,
    private val pullRefreshSwitch: MaterialSwitch,
    private val debugDiagnosticsSwitch: MaterialSwitch,
    private val unrestrictedFileImportSelectionSwitch: MaterialSwitch,
    private val renderResultFlags: (SettingsActivityUiState) -> Unit
) {
    fun initialize() {
        val initialState = viewModel.uiState.value
        floatingLogsSwitch.isChecked = initialState.floatingLogsEnabled
        floatingLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFloatingLogsEnabled(isChecked)
        }
        pullRefreshSwitch.isChecked = initialState.pullRefreshEnabled
        pullRefreshSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPullRefreshEnabled(isChecked)
        }
        debugDiagnosticsSwitch.isChecked = initialState.debugDiagnosticsEnabled
        debugDiagnosticsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDebugDiagnosticsEnabled(isChecked)
        }
        unrestrictedFileImportSelectionSwitch.isChecked = initialState.unrestrictedFileImportSelectionEnabled
        unrestrictedFileImportSelectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setUnrestrictedFileImportSelectionEnabled(isChecked)
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: SettingsActivityUiState) {
        if (floatingLogsSwitch.isChecked != state.floatingLogsEnabled) {
            floatingLogsSwitch.isChecked = state.floatingLogsEnabled
        }
        if (pullRefreshSwitch.isChecked != state.pullRefreshEnabled) {
            pullRefreshSwitch.isChecked = state.pullRefreshEnabled
        }
        if (debugDiagnosticsSwitch.isChecked != state.debugDiagnosticsEnabled) {
            debugDiagnosticsSwitch.isChecked = state.debugDiagnosticsEnabled
        }
        if (unrestrictedFileImportSelectionSwitch.isChecked != state.unrestrictedFileImportSelectionEnabled) {
            unrestrictedFileImportSelectionSwitch.isChecked = state.unrestrictedFileImportSelectionEnabled
        }
        renderResultFlags(state)
    }
}
