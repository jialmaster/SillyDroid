package com.jm.sillydroid

import android.content.Context

internal class BootstrapHostConfigStore(context: Context) {
    data class FloatingLogBubblePosition(
        val horizontalFraction: Float,
        val verticalFraction: Float
    )

    companion object {
        private const val preferencesName = "bootstrap-host-config"
        private const val servicePortKey = "service-port"
        private const val webViewPullRefreshEnabledKey = "webview-pull-refresh-enabled"
        private const val floatingLogBubbleEnabledKey = "floating-log-bubble-enabled"
        private const val floatingLogRefreshIntervalMillisKey = "floating-log-refresh-interval-millis"
        private const val floatingLogBubbleXKey = "floating-log-bubble-x"
        private const val floatingLogBubbleYKey = "floating-log-bubble-y"
        private const val defaultExtensionsPromptConsumedKey = "default-extensions-prompt-consumed"

        const val floatingLogRefreshIntervalRealtimeMillis = 250
        const val floatingLogRefreshIntervalOneSecondMillis = 1_000
        const val floatingLogRefreshIntervalThreeSecondsMillis = 3_000
        const val floatingLogRefreshIntervalFiveSecondsMillis = 5_000
        val floatingLogRefreshIntervalOptions = intArrayOf(
            floatingLogRefreshIntervalRealtimeMillis,
            floatingLogRefreshIntervalOneSecondMillis,
            floatingLogRefreshIntervalThreeSecondsMillis,
            floatingLogRefreshIntervalFiveSecondsMillis
        )
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    var servicePort: Int
        get() = sanitizeServicePort(preferences.getInt(servicePortKey, BootConfig.defaultServicePort))
        set(value) {
            preferences.edit()
                .putInt(servicePortKey, sanitizeServicePort(value))
                .apply()
        }

    var webViewPullRefreshEnabled: Boolean
        get() = preferences.getBoolean(webViewPullRefreshEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(webViewPullRefreshEnabledKey, value)
                .apply()
        }

    var floatingLogBubbleEnabled: Boolean
        get() = preferences.getBoolean(floatingLogBubbleEnabledKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(floatingLogBubbleEnabledKey, value)
                .apply()
        }

    var floatingLogRefreshIntervalMillis: Int
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

    var floatingLogBubblePosition: FloatingLogBubblePosition?
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

    var defaultExtensionsPromptConsumed: Boolean
        get() = preferences.getBoolean(defaultExtensionsPromptConsumedKey, false)
        set(value) {
            preferences.edit()
                .putBoolean(defaultExtensionsPromptConsumedKey, value)
                .apply()
        }

    private fun sanitizeServicePort(value: Int): Int {
        return value.takeIf { it in 1..65535 } ?: BootConfig.defaultServicePort
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
