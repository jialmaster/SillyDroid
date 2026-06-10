package com.jm.sillydroid.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.BrowserDataClearOptions
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.model.settings.NodeHeapLimitOptions
import com.jm.sillydroid.core.model.settings.NodeNewSpaceLimitOptions
import com.jm.sillydroid.domain.bootstrap.RuntimeMetadataRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.settings.model.SettingsTab
import com.jm.sillydroid.feature.settings.model.SettingsActivityUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsActivityViewModel(
    private val hostPreferencesRepository: HostPreferencesRepository,
    runtimeMetadataRepository: RuntimeMetadataRepository? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsActivityUiState(
            hostDisplayMode = hostPreferencesRepository.hostDisplayMode,
            browserEngine = hostPreferencesRepository.browserEngine,
            nodeMaxOldSpaceMb = hostPreferencesRepository.nodeMaxOldSpaceMb,
            nodeMaxSemiSpaceMb = hostPreferencesRepository.nodeMaxSemiSpaceMb,
            backgroundOnlyModeEnabled = !hostPreferencesRepository.launchWebViewOnReady,
            backgroundHealthCheckEnabled = hostPreferencesRepository.backgroundHealthCheckEnabled,
            tavernRuntimePatchEnabled = hostPreferencesRepository.tavernRuntimePatchEnabled,
            tavernRuntimePatchDisabledModuleIds = hostPreferencesRepository.tavernRuntimePatchDisabledModuleIds,
            tavernRuntimePatchSettingOverrides = hostPreferencesRepository.tavernRuntimePatchSettingOverrides,
            tavernRuntimePatchMetadata = runtimeMetadataRepository?.resolveRuntimePatchMetadataSnapshot(),
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

    fun setBrowserEngine(engine: BrowserEngine) {
        val changed = hostPreferencesRepository.browserEngine != engine
        if (changed) {
            hostPreferencesRepository.browserEngine = engine
        }
        _uiState.update { current ->
            current.copy(
                browserEngine = engine,
                // 浏览器引擎切换会替换整个主界面 browser host；设置页只返回重建信号，
                // 避免把内核切换伪装成普通 WebView reload 或清缓存。
                shouldRecreateMainActivity = current.shouldRecreateMainActivity || changed
            )
        }
    }

    // 返回值表示堆上限是否真的发生变化；调用方据此决定是否提示“重启服务后生效”。
    // Node 启动参数只在拉起服务时读取一次，这里不自动重启，保持最小侵入。
    fun setNodeMaxOldSpaceMb(valueMb: Int): Boolean {
        val sanitized = NodeHeapLimitOptions.sanitize(valueMb)
        val changed = hostPreferencesRepository.nodeMaxOldSpaceMb != sanitized
        if (changed) {
            hostPreferencesRepository.nodeMaxOldSpaceMb = sanitized
        }
        _uiState.update { current -> current.copy(nodeMaxOldSpaceMb = sanitized) }
        return changed
    }

    // 与老生代上限同口径：返回是否真的变化，供调用方决定是否提示重启生效。
    fun setNodeMaxSemiSpaceMb(valueMb: Int): Boolean {
        val sanitized = NodeNewSpaceLimitOptions.sanitize(valueMb)
        val changed = hostPreferencesRepository.nodeMaxSemiSpaceMb != sanitized
        if (changed) {
            hostPreferencesRepository.nodeMaxSemiSpaceMb = sanitized
        }
        _uiState.update { current -> current.copy(nodeMaxSemiSpaceMb = sanitized) }
        return changed
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

    // Runtime patch 只在 Node 服务启动前注入；设置页只保存开关并提示重启服务，避免热切换半生效。
    fun setTavernRuntimePatchEnabled(enabled: Boolean): Boolean {
        val changed = hostPreferencesRepository.tavernRuntimePatchEnabled != enabled
        if (changed) {
            hostPreferencesRepository.tavernRuntimePatchEnabled = enabled
        }
        _uiState.update { current -> current.copy(tavernRuntimePatchEnabled = enabled) }
        return changed
    }

    fun isRuntimePatchModuleEnabled(moduleId: String): Boolean {
        val normalizedId = moduleId.trim()
        return normalizedId.isNotBlank() && normalizedId !in hostPreferencesRepository.tavernRuntimePatchDisabledModuleIds
    }

    fun setRuntimePatchModuleEnabled(moduleId: String, enabled: Boolean): Boolean {
        val normalizedId = moduleId.trim()
        if (normalizedId.isBlank()) {
            return false
        }
        val currentDisabledIds = hostPreferencesRepository.tavernRuntimePatchDisabledModuleIds
        val nextDisabledIds = if (enabled) {
            currentDisabledIds - normalizedId
        } else {
            currentDisabledIds + normalizedId
        }
        val changed = nextDisabledIds != currentDisabledIds
        if (changed) {
            hostPreferencesRepository.tavernRuntimePatchDisabledModuleIds = nextDisabledIds
        }
        _uiState.update { current ->
            current.copy(tavernRuntimePatchDisabledModuleIds = nextDisabledIds)
        }
        return changed
    }

    fun resolveRuntimePatchSettingValue(moduleId: String, settingKey: String, defaultValue: String): String {
        val normalizedModuleId = moduleId.trim()
        val normalizedSettingKey = settingKey.trim()
        return hostPreferencesRepository.tavernRuntimePatchSettingOverrides[normalizedModuleId]
            ?.get(normalizedSettingKey)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: defaultValue.trim()
    }

    fun setRuntimePatchSettingOverride(moduleId: String, settingKey: String, value: String): Boolean {
        val normalizedModuleId = moduleId.trim()
        val normalizedSettingKey = settingKey.trim()
        val normalizedValue = value.trim()
        if (normalizedModuleId.isBlank() || normalizedSettingKey.isBlank()) {
            return false
        }

        val currentOverrides = hostPreferencesRepository.tavernRuntimePatchSettingOverrides
        val currentModuleOverrides = currentOverrides[normalizedModuleId].orEmpty()
        val nextModuleOverrides = if (normalizedValue.isBlank()) {
            currentModuleOverrides - normalizedSettingKey
        } else {
            currentModuleOverrides + (normalizedSettingKey to normalizedValue)
        }
        val nextOverrides = if (nextModuleOverrides.isEmpty()) {
            currentOverrides - normalizedModuleId
        } else {
            currentOverrides + (normalizedModuleId to nextModuleOverrides)
        }
        val changed = nextOverrides != currentOverrides
        if (changed) {
            hostPreferencesRepository.tavernRuntimePatchSettingOverrides = nextOverrides
        }
        _uiState.update { current ->
            current.copy(tavernRuntimePatchSettingOverrides = nextOverrides)
        }
        return changed
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
        shouldRecreateMainActivity: Boolean = false,
        browserDataClearMask: Int = 0
    ) {
        _uiState.update { current ->
            val normalizedBrowserDataClearMask = BrowserDataClearOptions.normalize(browserDataClearMask)
            current.copy(
                shouldStartBootstrap = current.shouldStartBootstrap || shouldStartBootstrap,
                shouldReloadTavernUi = current.shouldReloadTavernUi || shouldReloadTavernUi,
                shouldForceFreshWebViewLoad = current.shouldForceFreshWebViewLoad || shouldForceFreshWebViewLoad,
                shouldRecreateMainActivity = current.shouldRecreateMainActivity || shouldRecreateMainActivity,
                browserDataClearMask = current.browserDataClearMask or normalizedBrowserDataClearMask
            )
        }
    }
}

class SettingsActivityViewModelFactory(
    private val hostPreferencesRepository: HostPreferencesRepository,
    private val runtimeMetadataRepository: RuntimeMetadataRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsActivityViewModel::class.java)) {
            return SettingsActivityViewModel(
                hostPreferencesRepository = hostPreferencesRepository,
                runtimeMetadataRepository = runtimeMetadataRepository
            ) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
    }
}
