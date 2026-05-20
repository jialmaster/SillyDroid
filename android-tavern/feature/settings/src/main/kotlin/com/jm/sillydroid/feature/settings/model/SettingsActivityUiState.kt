package com.jm.sillydroid.feature.settings.model

data class SettingsActivityUiState(
    val selectedTab: SettingsTab = SettingsTab.DATA,
    val floatingLogsEnabled: Boolean = false,
    val pullRefreshEnabled: Boolean = false,
    val debugDiagnosticsEnabled: Boolean = false,
    val shouldStartBootstrap: Boolean = false,
    val shouldReloadTavernUi: Boolean = false
)
