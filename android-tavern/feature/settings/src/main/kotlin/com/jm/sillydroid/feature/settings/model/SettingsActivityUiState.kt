package com.jm.sillydroid.feature.settings.model

import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.domain.bootstrap.RuntimePatchMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverrides

data class SettingsActivityUiState(
    val selectedTab: SettingsTab = SettingsTab.DATA,
    val hostDisplayMode: HostDisplayMode = HostDisplayMode.NORMAL,
    val browserEngine: BrowserEngine = BrowserEngine.SYSTEM_WEBVIEW,
    val nodeMaxOldSpaceMb: Int = 0,
    val nodeMaxSemiSpaceMb: Int = 0,
    val backgroundOnlyModeEnabled: Boolean = false,
    val backgroundHealthCheckEnabled: Boolean = false,
    val tavernRuntimePatchEnabled: Boolean = false,
    val tavernRuntimePatchDisabledModuleIds: Set<String> = emptySet(),
    val tavernRuntimePatchSettingOverrides: RuntimePatchSettingOverrides = emptyMap(),
    val tavernRuntimePatchMetadata: RuntimePatchMetadataSnapshot? = null,
    val floatingLogsEnabled: Boolean = false,
    val pullRefreshEnabled: Boolean = false,
    val debugDiagnosticsEnabled: Boolean = false,
    val unrestrictedFileImportSelectionEnabled: Boolean = false,
    val shouldStartBootstrap: Boolean = false,
    val shouldReloadTavernUi: Boolean = false,
    val shouldForceFreshWebViewLoad: Boolean = false,
    val shouldRecreateMainActivity: Boolean = false,
    val browserDataClearMask: Int = 0
)
