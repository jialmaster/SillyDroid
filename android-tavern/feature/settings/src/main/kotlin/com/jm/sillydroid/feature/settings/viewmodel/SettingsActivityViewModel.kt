package com.jm.sillydroid.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jm.sillydroid.core.model.settings.BrowserDataClearOptions
import com.jm.sillydroid.core.model.settings.HostDisplayMode
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
            hostDisplayMode = hostPreferencesRepository.hostDisplayMode,
            backgroundOnlyModeEnabled = !hostPreferencesRepository.launchWebViewOnReady,
            backgroundHealthCheckEnabled = hostPreferencesRepository.backgroundHealthCheckEnabled,
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

    fun setHostDisplayMode(mode: HostDisplayMode) {
        if (hostPreferencesRepository.hostDisplayMode != mode) {
            hostPreferencesRepository.hostDisplayMode = mode
        }
        _uiState.update { current -> current.copy(hostDisplayMode = mode) }
    }

    fun setBackgroundOnlyModeEnabled(enabled: Boolean) {
        // 纯后台模式是启动展示策略：服务照常启动，但 ready 后不自动加载宿主 WebView。
        val launchWebViewOnReady = !enabled
        if (hostPreferencesRepository.launchWebViewOnReady != launchWebViewOnReady) {
            hostPreferencesRepository.launchWebViewOnReady = launchWebViewOnReady
        }
        _uiState.update { current -> current.copy(backgroundOnlyModeEnabled = enabled) }
    }

    fun setBackgroundHealthCheckEnabled(enabled: Boolean) {
        // 后台健康检查会周期性探测本地 Tavern 服务；默认关闭，避免不通的机器反复触发重启刷新。
        if (hostPreferencesRepository.backgroundHealthCheckEnabled != enabled) {
            hostPreferencesRepository.backgroundHealthCheckEnabled = enabled
        }
        _uiState.update { current -> current.copy(backgroundHealthCheckEnabled = enabled) }
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
        shouldReloadTavernUi: Boolean = false,
        shouldForceFreshWebViewLoad: Boolean = false,
        browserDataClearMask: Int = 0
    ) {
        _uiState.update { current ->
            val normalizedBrowserDataClearMask = BrowserDataClearOptions.normalize(browserDataClearMask)
            current.copy(
                shouldStartBootstrap = current.shouldStartBootstrap || shouldStartBootstrap,
                shouldReloadTavernUi = current.shouldReloadTavernUi || shouldReloadTavernUi,
                shouldForceFreshWebViewLoad = current.shouldForceFreshWebViewLoad || shouldForceFreshWebViewLoad,
                browserDataClearMask = current.browserDataClearMask or normalizedBrowserDataClearMask
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
