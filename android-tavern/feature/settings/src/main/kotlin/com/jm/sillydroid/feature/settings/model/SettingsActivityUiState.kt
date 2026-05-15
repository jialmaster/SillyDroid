package com.jm.sillydroid.feature.settings.model

data class SettingsActivityUiState(
    val selectedTabIndex: Int = 0,
    val floatingLogsEnabled: Boolean = false,
    val pullRefreshEnabled: Boolean = false,
    val shouldStartBootstrap: Boolean = false,
    val shouldReloadTavernUi: Boolean = false
)
