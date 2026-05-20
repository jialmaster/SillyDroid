package com.jm.sillydroid.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.settings.model.SettingsTab
import com.jm.sillydroid.feature.settings.model.SettingsActivityUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsActivityViewModel(
    private val hostPreferencesRepository: HostPreferencesRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsActivityUiState(
            floatingLogsEnabled = hostPreferencesRepository.floatingLogBubbleEnabled,
            pullRefreshEnabled = hostPreferencesRepository.webViewPullRefreshEnabled,
            debugDiagnosticsEnabled = hostPreferencesRepository.debugDiagnosticsEnabled,
            unrestrictedFileImportSelectionEnabled = hostPreferencesRepository.unrestrictedFileImportSelectionEnabled
        )
    )

    val uiState: StateFlow<SettingsActivityUiState> = _uiState.asStateFlow()

    fun selectTab(tab: SettingsTab) {
        _uiState.update { current ->
            if (current.selectedTab == tab) current else current.copy(selectedTab = tab)
        }
    }

    fun setFloatingLogsEnabled(enabled: Boolean) {
        if (hostPreferencesRepository.floatingLogBubbleEnabled != enabled) {
            hostPreferencesRepository.floatingLogBubbleEnabled = enabled
        }
        _uiState.update { current -> current.copy(floatingLogsEnabled = enabled) }
    }

    fun setPullRefreshEnabled(enabled: Boolean) {
        if (hostPreferencesRepository.webViewPullRefreshEnabled != enabled) {
            hostPreferencesRepository.webViewPullRefreshEnabled = enabled
        }
        _uiState.update { current -> current.copy(pullRefreshEnabled = enabled) }
    }

    fun setDebugDiagnosticsEnabled(enabled: Boolean) {
        if (hostPreferencesRepository.debugDiagnosticsEnabled != enabled) {
            hostPreferencesRepository.debugDiagnosticsEnabled = enabled
        }
        _uiState.update { current -> current.copy(debugDiagnosticsEnabled = enabled) }
    }

    fun setUnrestrictedFileImportSelectionEnabled(enabled: Boolean) {
        if (hostPreferencesRepository.unrestrictedFileImportSelectionEnabled != enabled) {
            hostPreferencesRepository.unrestrictedFileImportSelectionEnabled = enabled
        }
        _uiState.update { current -> current.copy(unrestrictedFileImportSelectionEnabled = enabled) }
    }

    fun markResultFlags(
        shouldStartBootstrap: Boolean = false,
        shouldReloadTavernUi: Boolean = false
    ) {
        _uiState.update { current ->
            current.copy(
                shouldStartBootstrap = current.shouldStartBootstrap || shouldStartBootstrap,
                shouldReloadTavernUi = current.shouldReloadTavernUi || shouldReloadTavernUi
            )
        }
    }
}

class SettingsActivityViewModelFactory(
    private val hostPreferencesRepository: HostPreferencesRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsActivityViewModel::class.java)) {
            return SettingsActivityViewModel(hostPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
    }
}
