package com.jm.sillydroid.feature.settings.model

import com.jm.sillydroid.core.model.settings.HostDisplayMode

data class SettingsActivityUiState(
    val selectedTab: SettingsTab = SettingsTab.DATA,
    val hostDisplayMode: HostDisplayMode = HostDisplayMode.NORMAL,
    val floatingLogsEnabled: Boolean = false,
    val pullRefreshEnabled: Boolean = false,
    val debugDiagnosticsEnabled: Boolean = false,
    val unrestrictedFileImportSelectionEnabled: Boolean = false,
    val shouldStartBootstrap: Boolean = false,
    val shouldReloadTavernUi: Boolean = false,
    val shouldForceFreshWebViewLoad: Boolean = false
)
