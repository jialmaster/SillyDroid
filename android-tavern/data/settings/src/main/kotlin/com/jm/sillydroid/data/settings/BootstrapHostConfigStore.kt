package com.jm.sillydroid.data.settings

import android.content.Context
import com.jm.sillydroid.core.model.bootstrap.defaultBootstrapServicePort
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import com.jm.sillydroid.core.model.settings.FloatingLogRefreshIntervals
import com.jm.sillydroid.core.model.settings.TerminalFontSizeOptions
import com.jm.sillydroid.domain.settings.HostPreferencesRepository

class BootstrapHostConfigStore(context: Context) : HostPreferencesRepository {
    companion object {
        internal const val preferencesName = "bootstrap-host-config"
        private const val servicePortKey = "service-port"
        private const val webViewPullRefreshEnabledKey = "webview-pull-refresh-enabled"
        private const val terminalFontSizePxKey = "terminal-font-size-px"
        private const val terminalCursorBlinkEnabledKey = "terminal-cursor-blink-enabled"
        private const val terminalExtraKeysEnabledKey = "terminal-extra-keys-enabled"
        private const val floatingLogBubbleEnabledKey = "floating-log-bubble-enabled"
        private const val floatingLogRefreshIntervalMillisKey = "floating-log-refresh-interval-millis"
        private const val floatingLogBubbleXKey = "floating-log-bubble-x"
        private const val floatingLogBubbleYKey = "floating-log-bubble-y"
        private const val defaultExtensionsPromptConsumedKey = "default-extensions-prompt-consumed"

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
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override var servicePort: Int
        get() = sanitizeServicePort(preferences.getInt(servicePortKey, defaultBootstrapServicePort))
        set(value) {
            preferences.edit()
                .putInt(servicePortKey, sanitizeServicePort(value))
                .apply()
        }

    override var webViewPullRefreshEnabled: Boolean
        get() = preferences.getBoolean(webViewPullRefreshEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(webViewPullRefreshEnabledKey, value)
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
