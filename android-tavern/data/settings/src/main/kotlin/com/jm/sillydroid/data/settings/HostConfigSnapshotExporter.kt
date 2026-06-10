package com.jm.sillydroid.data.settings

import android.content.Context
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverrides
import com.jm.sillydroid.domain.settings.HostPreferencesRepository

data class HostConfigSnapshot(
    val storageBackend: String,
    val storageName: String,
    val snapshotPolicy: String,
    val servicePort: Int,
    val nodeMaxOldSpaceMb: Int,
    val nodeMaxSemiSpaceMb: Int,
    val hostDisplayMode: HostDisplayMode,
    val browserEngine: BrowserEngine,
    val browserZoomPercent: Int,
    val launchWebViewOnReady: Boolean,
    val backgroundHealthCheckEnabled: Boolean,
    val tavernRuntimePatchEnabled: Boolean,
    val tavernRuntimePatchDisabledModuleIds: Set<String>,
    val tavernRuntimePatchSettingOverrides: RuntimePatchSettingOverrides,
    val webViewPullRefreshEnabled: Boolean,
    val debugDiagnosticsEnabled: Boolean,
    val unrestrictedFileImportSelectionEnabled: Boolean,
    val terminalFontSizePx: Int,
    val terminalCursorBlinkEnabled: Boolean,
    val terminalExtraKeysEnabled: Boolean,
    val floatingLogBubbleEnabled: Boolean,
    val floatingLogRefreshIntervalMillis: Int,
    val floatingLogBubblePosition: FloatingLogBubblePosition?,
    val defaultExtensionsPromptConsumed: Boolean,
    val crashLogUploadEnabled: Boolean,
    val crashLogUploadPromptConsumed: Boolean
)

object HostConfigSnapshotExporter {
    private const val sharedPreferencesBackend = "SharedPreferences"
    private const val explicitSnapshotPolicy = "explicit-host-preferences-only"

    fun build(context: Context): HostConfigSnapshot {
        return build(BootstrapHostConfigStore(context.applicationContext))
    }

    // 宿主配置快照只允许导出 HostPreferencesRepository 已显式公开的字段，
    // 避免未来 SharedPreferences 新增未审字段后，被日志打包链路意外原样带出。
    fun build(hostPreferences: HostPreferencesRepository): HostConfigSnapshot {
        return HostConfigSnapshot(
            storageBackend = sharedPreferencesBackend,
            storageName = BootstrapHostConfigStore.preferencesName,
            snapshotPolicy = explicitSnapshotPolicy,
            servicePort = hostPreferences.servicePort,
            nodeMaxOldSpaceMb = hostPreferences.nodeMaxOldSpaceMb,
            nodeMaxSemiSpaceMb = hostPreferences.nodeMaxSemiSpaceMb,
            hostDisplayMode = hostPreferences.hostDisplayMode,
            browserEngine = hostPreferences.browserEngine,
            browserZoomPercent = hostPreferences.browserZoomPercent,
            launchWebViewOnReady = hostPreferences.launchWebViewOnReady,
            backgroundHealthCheckEnabled = hostPreferences.backgroundHealthCheckEnabled,
            tavernRuntimePatchEnabled = hostPreferences.tavernRuntimePatchEnabled,
            tavernRuntimePatchDisabledModuleIds = hostPreferences.tavernRuntimePatchDisabledModuleIds,
            tavernRuntimePatchSettingOverrides = hostPreferences.tavernRuntimePatchSettingOverrides,
            webViewPullRefreshEnabled = hostPreferences.webViewPullRefreshEnabled,
            debugDiagnosticsEnabled = hostPreferences.debugDiagnosticsEnabled,
            unrestrictedFileImportSelectionEnabled = hostPreferences.unrestrictedFileImportSelectionEnabled,
            terminalFontSizePx = hostPreferences.terminalFontSizePx,
            terminalCursorBlinkEnabled = hostPreferences.terminalCursorBlinkEnabled,
            terminalExtraKeysEnabled = hostPreferences.terminalExtraKeysEnabled,
            floatingLogBubbleEnabled = hostPreferences.floatingLogBubbleEnabled,
            floatingLogRefreshIntervalMillis = hostPreferences.floatingLogRefreshIntervalMillis,
            floatingLogBubblePosition = hostPreferences.floatingLogBubblePosition,
            defaultExtensionsPromptConsumed = hostPreferences.defaultExtensionsPromptConsumed,
            crashLogUploadEnabled = hostPreferences.crashLogUploadEnabled,
            crashLogUploadPromptConsumed = hostPreferences.crashLogUploadPromptConsumed
        )
    }
}
