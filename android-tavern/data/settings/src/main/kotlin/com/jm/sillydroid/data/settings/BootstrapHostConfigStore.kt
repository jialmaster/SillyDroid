package com.jm.sillydroid.data.settings

import android.content.Context
import com.jm.sillydroid.core.model.bootstrap.defaultBootstrapServicePort
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.BrowserZoomOptions
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import com.jm.sillydroid.core.model.settings.FloatingLogRefreshIntervals
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.model.settings.NodeHeapLimitOptions
import com.jm.sillydroid.core.model.settings.NodeNewSpaceLimitOptions
import com.jm.sillydroid.core.model.settings.TerminalFontSizeOptions
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverrides
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverridesCodec
import com.jm.sillydroid.domain.settings.HostPreferencesRepository

class BootstrapHostConfigStore(context: Context) : HostPreferencesRepository {
    companion object {
        internal const val preferencesName = "bootstrap-host-config"
        private const val servicePortKey = "service-port"
        private const val nodeMaxOldSpaceMbKey = "node-max-old-space-mb"
        private const val nodeMaxSemiSpaceMbKey = "node-max-semi-space-mb"
        private const val hostDisplayModeKey = "host-display-mode"
        private const val browserEngineKey = "browser-engine"
        private const val browserZoomPercentKey = "browser-zoom-percent"
        private const val launchWebViewOnReadyKey = "launch-webview-on-ready"
        private const val backgroundHealthCheckEnabledKey = "background-health-check-enabled"
        private const val tavernRuntimePatchEnabledKey = "tavern-runtime-patch-enabled"
        private const val tavernRuntimePatchDisabledModuleIdsKey = "tavern-runtime-patch-disabled-module-ids"
        private const val tavernRuntimePatchSettingOverridesKey = "tavern-runtime-patch-setting-overrides"
        private const val webViewPullRefreshEnabledKey = "webview-pull-refresh-enabled"
        private const val debugDiagnosticsEnabledKey = "debug-diagnostics-enabled"
        private const val unrestrictedFileImportSelectionEnabledKey = "unrestricted-file-import-selection-enabled"
        private const val terminalFontSizePxKey = "terminal-font-size-px"
        private const val terminalCursorBlinkEnabledKey = "terminal-cursor-blink-enabled"
        private const val terminalExtraKeysEnabledKey = "terminal-extra-keys-enabled"
        private const val floatingLogBubbleEnabledKey = "floating-log-bubble-enabled"
        private const val floatingLogRefreshIntervalMillisKey = "floating-log-refresh-interval-millis"
        private const val floatingLogBubbleXKey = "floating-log-bubble-x"
        private const val floatingLogBubbleYKey = "floating-log-bubble-y"
        private const val defaultExtensionsPromptConsumedKey = "default-extensions-prompt-consumed"
        private const val crashLogUploadEnabledKey = "crash-log-upload-enabled"
        private const val crashLogUploadPromptConsumedKey = "crash-log-upload-prompt-consumed"
        private const val legacyCrashLogUploadPromptVersionCodeKey = "crash-log-upload-prompt-version-code"
        private const val lastCrashLogAutoUploadKeyKey = "last-crash-log-auto-upload-key"
        private const val pendingRendererGoneAutoUploadKeyKey = "pending-renderer-gone-auto-upload-key"
        private const val pendingRendererGoneAutoUploadCrashTypeKey = "pending-renderer-gone-auto-upload-crash-type"
        private const val pendingRendererGoneAutoUploadNotesKey = "pending-renderer-gone-auto-upload-notes"

        const val floatingLogRefreshIntervalRealtimeMillis = FloatingLogRefreshIntervals.REALTIME_MILLIS
        const val floatingLogRefreshIntervalOneSecondMillis = FloatingLogRefreshIntervals.ONE_SECOND_MILLIS
        const val floatingLogRefreshIntervalThreeSecondsMillis = FloatingLogRefreshIntervals.THREE_SECONDS_MILLIS
        const val floatingLogRefreshIntervalFiveSecondsMillis = FloatingLogRefreshIntervals.FIVE_SECONDS_MILLIS
        val floatingLogRefreshIntervalOptions = intArrayOf(
            floatingLogRefreshIntervalRealtimeMillis,
            floatingLogRefreshIntervalOneSecondMillis,
            floatingLogRefreshIntervalThreeSecondsMillis,
            floatingLogRefreshIntervalFiveSecondsMillis
        )

        // servicePort 在启动热路径上被反复读取，而 getter 直连 SharedPreferences 会触发同步 disk IO。
        // 本 App 未声明 android:process 为单进程，但 servicePort 会被多个 store 实例写入，
        // 所以缓存放在 companion 级别保证进程内跨实例一致，避免实例级缓存互相失真。
        @Volatile
        private var cachedServicePort: Int? = null
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override var servicePort: Int
        get() {
            cachedServicePort?.let { return it }
            val resolved = sanitizeServicePort(preferences.getInt(servicePortKey, defaultBootstrapServicePort))
            cachedServicePort = resolved
            return resolved
        }
        set(value) {
            val sanitized = sanitizeServicePort(value)
            cachedServicePort = sanitized
            preferences.edit()
                .putInt(servicePortKey, sanitized)
                .apply()
        }

    override var nodeMaxOldSpaceMb: Int
        get() = NodeHeapLimitOptions.sanitize(
            preferences.getInt(nodeMaxOldSpaceMbKey, NodeHeapLimitOptions.DEFAULT_MB)
        )
        set(value) {
            preferences.edit()
                .putInt(nodeMaxOldSpaceMbKey, NodeHeapLimitOptions.sanitize(value))
                .apply()
        }

    override var nodeMaxSemiSpaceMb: Int
        get() = NodeNewSpaceLimitOptions.sanitize(
            preferences.getInt(nodeMaxSemiSpaceMbKey, NodeNewSpaceLimitOptions.DEFAULT_MB)
        )
        set(value) {
            preferences.edit()
                .putInt(nodeMaxSemiSpaceMbKey, NodeNewSpaceLimitOptions.sanitize(value))
                .apply()
        }

    override var hostDisplayMode: HostDisplayMode
        get() = HostDisplayMode.fromStorageValue(preferences.getString(hostDisplayModeKey, HostDisplayMode.NORMAL.name))
        set(value) {
            preferences.edit()
                .putString(hostDisplayModeKey, value.name)
                .apply()
        }

    override var browserEngine: BrowserEngine
        get() = BrowserEngine.fromStorageValue(preferences.getString(browserEngineKey, BrowserEngine.SYSTEM_WEBVIEW.name))
        set(value) {
            preferences.edit()
                .putString(browserEngineKey, value.name)
                .apply()
        }

    override var browserZoomPercent: Int
        get() = BrowserZoomOptions.sanitize(
            preferences.getInt(browserZoomPercentKey, BrowserZoomOptions.DEFAULT_PERCENT)
        )
        set(value) {
            preferences.edit()
                .putInt(browserZoomPercentKey, BrowserZoomOptions.sanitize(value))
                .apply()
        }

    override var launchWebViewOnReady: Boolean
        get() = preferences.getBoolean(launchWebViewOnReadyKey, true)
        set(value) {
            preferences.edit()
                .putBoolean(launchWebViewOnReadyKey, value)
                .apply()
        }

    override var backgroundHealthCheckEnabled: Boolean
        get() = preferences.getBoolean(backgroundHealthCheckEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(backgroundHealthCheckEnabledKey, value)
                .apply()
        }

    override var tavernRuntimePatchEnabled: Boolean
        get() = preferences.getBoolean(tavernRuntimePatchEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(tavernRuntimePatchEnabledKey, value)
                .apply()
        }

    override var tavernRuntimePatchDisabledModuleIds: Set<String>
        get() = preferences.getStringSet(tavernRuntimePatchDisabledModuleIdsKey, emptySet())
            .orEmpty()
            .map { id -> id.trim() }
            .filter { id -> id.isNotBlank() }
            .toSet()
        set(value) {
            preferences.edit()
                .putStringSet(
                    tavernRuntimePatchDisabledModuleIdsKey,
                    value.map { id -> id.trim() }
                        .filter { id -> id.isNotBlank() }
                        .toSet()
                )
                .apply()
        }

    override var tavernRuntimePatchSettingOverrides: RuntimePatchSettingOverrides
        get() = RuntimePatchSettingOverridesCodec.decode(
            preferences.getString(tavernRuntimePatchSettingOverridesKey, null)
        )
        set(value) {
            preferences.edit().apply {
                val encoded = RuntimePatchSettingOverridesCodec.encode(value)
                if (encoded.isBlank()) {
                    remove(tavernRuntimePatchSettingOverridesKey)
                } else {
                    putString(tavernRuntimePatchSettingOverridesKey, encoded)
                }
            }.apply()
        }

    override var webViewPullRefreshEnabled: Boolean
        get() = preferences.getBoolean(webViewPullRefreshEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(webViewPullRefreshEnabledKey, value)
                .apply()
        }

    override var debugDiagnosticsEnabled: Boolean
        get() = preferences.getBoolean(debugDiagnosticsEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(debugDiagnosticsEnabledKey, value)
                .apply()
        }

    override var unrestrictedFileImportSelectionEnabled: Boolean
        get() = preferences.getBoolean(unrestrictedFileImportSelectionEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(unrestrictedFileImportSelectionEnabledKey, value)
                .apply()
        }

    override var terminalFontSizePx: Int
        get() = TerminalFontSizeOptions.sanitize(
            preferences.getInt(terminalFontSizePxKey, TerminalFontSizeOptions.DEFAULT_PX)
        )
        set(value) {
            preferences.edit()
                .putInt(terminalFontSizePxKey, TerminalFontSizeOptions.sanitize(value))
                .apply()
        }

    override var terminalCursorBlinkEnabled: Boolean
        get() = preferences.getBoolean(terminalCursorBlinkEnabledKey, true)
        set(value) {
            preferences.edit()
                .putBoolean(terminalCursorBlinkEnabledKey, value)
                .apply()
        }

    override var terminalExtraKeysEnabled: Boolean
        get() = preferences.getBoolean(terminalExtraKeysEnabledKey, true)
        set(value) {
            preferences.edit()
                .putBoolean(terminalExtraKeysEnabledKey, value)
                .apply()
        }

    override var floatingLogBubbleEnabled: Boolean
        get() = preferences.getBoolean(floatingLogBubbleEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(floatingLogBubbleEnabledKey, value)
                .apply()
        }

    override var floatingLogRefreshIntervalMillis: Int
        get() = sanitizeFloatingLogRefreshIntervalMillis(
            preferences.getInt(
                floatingLogRefreshIntervalMillisKey,
                floatingLogRefreshIntervalOneSecondMillis
            )
        )
        set(value) {
            preferences.edit()
                .putInt(
                    floatingLogRefreshIntervalMillisKey,
                    sanitizeFloatingLogRefreshIntervalMillis(value)
                )
                .apply()
        }

    override var floatingLogBubblePosition: FloatingLogBubblePosition?
        get() {
            if (!preferences.contains(floatingLogBubbleXKey) || !preferences.contains(floatingLogBubbleYKey)) {
                return null
            }

            return FloatingLogBubblePosition(
                horizontalFraction = sanitizeFraction(preferences.getFloat(floatingLogBubbleXKey, 1f)),
                verticalFraction = sanitizeFraction(preferences.getFloat(floatingLogBubbleYKey, 1f))
            )
        }
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    remove(floatingLogBubbleXKey)
                    remove(floatingLogBubbleYKey)
                } else {
                    putFloat(floatingLogBubbleXKey, sanitizeFraction(value.horizontalFraction))
                    putFloat(floatingLogBubbleYKey, sanitizeFraction(value.verticalFraction))
                }
            }.apply()
        }

    override var defaultExtensionsPromptConsumed: Boolean
        get() = preferences.getBoolean(defaultExtensionsPromptConsumedKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(defaultExtensionsPromptConsumedKey, value)
                .apply()
        }

    override var crashLogUploadEnabled: Boolean
        get() = preferences.getBoolean(crashLogUploadEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(crashLogUploadEnabledKey, value)
                .apply()
        }

    override var crashLogUploadPromptConsumed: Boolean
        get() = preferences.getBoolean(crashLogUploadPromptConsumedKey, false) ||
            preferences.getLong(legacyCrashLogUploadPromptVersionCodeKey, 0L) > 0L
        set(value) {
            // 旧开发包曾按 versionCode 记录授权提示；这里迁移为“看过一次即永久不再提示”。
            preferences.edit()
                .putBoolean(crashLogUploadPromptConsumedKey, value)
                .remove(legacyCrashLogUploadPromptVersionCodeKey)
                .apply()
        }

    override var lastCrashLogAutoUploadKey: String?
        get() = preferences.getString(lastCrashLogAutoUploadKeyKey, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        set(value) {
            preferences.edit().apply {
                val normalized = value.orEmpty().trim()
                if (normalized.isBlank()) {
                    remove(lastCrashLogAutoUploadKeyKey)
                } else {
                    putString(lastCrashLogAutoUploadKeyKey, normalized)
                }
            }.apply()
        }

    override var pendingRendererGoneAutoUploadKey: String?
        get() = preferences.getString(pendingRendererGoneAutoUploadKeyKey, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        set(value) {
            preferences.edit().apply {
                val normalized = value.orEmpty().trim()
                if (normalized.isBlank()) {
                    remove(pendingRendererGoneAutoUploadKeyKey)
                    remove(pendingRendererGoneAutoUploadCrashTypeKey)
                    remove(pendingRendererGoneAutoUploadNotesKey)
                } else {
                    putString(pendingRendererGoneAutoUploadKeyKey, normalized)
                }
            }.apply()
        }

    override var pendingRendererGoneAutoUploadCrashType: String?
        get() = preferences.getString(pendingRendererGoneAutoUploadCrashTypeKey, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        set(value) {
            preferences.edit().apply {
                val normalized = value.orEmpty().trim()
                if (normalized.isBlank()) {
                    remove(pendingRendererGoneAutoUploadCrashTypeKey)
                } else {
                    putString(pendingRendererGoneAutoUploadCrashTypeKey, normalized)
                }
            }.apply()
        }

    override var pendingRendererGoneAutoUploadNotes: String?
        get() = preferences.getString(pendingRendererGoneAutoUploadNotesKey, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        set(value) {
            preferences.edit().apply {
                val normalized = value.orEmpty().trim()
                if (normalized.isBlank()) {
                    remove(pendingRendererGoneAutoUploadNotesKey)
                } else {
                    putString(pendingRendererGoneAutoUploadNotesKey, normalized)
                }
            }.apply()
        }

    private fun sanitizeServicePort(value: Int): Int {
        return value.takeIf { it in 1..65535 } ?: defaultBootstrapServicePort
    }

    private fun sanitizeFraction(value: Float): Float {
        return value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    }

    private fun sanitizeFloatingLogRefreshIntervalMillis(value: Int): Int {
        return value.takeIf { candidate ->
            floatingLogRefreshIntervalOptions.contains(candidate)
        } ?: floatingLogRefreshIntervalOneSecondMillis
    }
}
