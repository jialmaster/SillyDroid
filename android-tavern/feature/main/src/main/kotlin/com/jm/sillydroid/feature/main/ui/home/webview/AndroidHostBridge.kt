package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.JavascriptInterface

class AndroidHostBridge(
    private val isHostActive: () -> Boolean,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val openSettings: () -> Unit,
    private val showFloatingLogsBubble: () -> Unit,
    private val requestOpenCurrentPageInBrowser: () -> Unit,
    // 这里必须和 @JavascriptInterface 方法名区分开，避免 Kotlin 在 lambda 里解析成当前桥接方法并递归调用。
    private val applyFloatingLogsBubbleEnabled: (Boolean) -> Unit,
    private val applyWebViewPullRefreshEnabled: (Boolean) -> Unit,
    private val applySystemBarsBackgroundColor: (String) -> Unit,
    private val applySystemBarsBackgroundColors: (String, String) -> Unit,
    private val reloadTavern: () -> Unit,
    private val hostVersionInfoJson: () -> String,
    private val recordWebPerformanceDiagnosticPayload: (String) -> Unit = {}
) {
    private companion object {
        private const val WEB_PERFORMANCE_DIAGNOSTIC_PAYLOAD_LIMIT = 4_096
    }

    @JavascriptInterface
    fun openSettings(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(openSettings)
        return true
    }

    @JavascriptInterface
    fun showFloatingLogsBubble(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(showFloatingLogsBubble)
        return true
    }

    @JavascriptInterface
    fun openCurrentPageInBrowser(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(requestOpenCurrentPageInBrowser)
        return true
    }

    @JavascriptInterface
    fun setFloatingLogsBubbleEnabled(enabled: Boolean): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread {
            applyFloatingLogsBubbleEnabled(enabled)
        }
        return true
    }

    @JavascriptInterface
    fun setWebViewPullRefreshEnabled(enabled: Boolean): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread {
            applyWebViewPullRefreshEnabled(enabled)
        }
        return true
    }

    @JavascriptInterface
    fun setSystemBarsBackgroundColor(hexColor: String): Boolean {
        if (!isHostActive() || hexColor.isBlank()) {
            return false
        }

        runOnUiThread {
            applySystemBarsBackgroundColor(hexColor)
        }
        return true
    }

    @JavascriptInterface
    fun setSystemBarsBackgroundColors(statusBarHexColor: String, navigationBarHexColor: String): Boolean {
        if (!isHostActive() || statusBarHexColor.isBlank() || navigationBarHexColor.isBlank()) {
            return false
        }

        runOnUiThread {
            applySystemBarsBackgroundColors(statusBarHexColor, navigationBarHexColor)
        }
        return true
    }

    @JavascriptInterface
    fun reloadTavern(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(reloadTavern)
        return true
    }

    @JavascriptInterface
    fun getHostVersionInfo(): String {
        return hostVersionInfoJson()
    }

    @JavascriptInterface
    fun recordWebPerformanceDiagnostic(payload: String): Boolean {
        if (!isHostActive() || payload.isBlank()) {
            return false
        }

        // 性能探针只需要一行聚合摘要；这里裁剪长度并移除换行，避免页面侧误传超长内容污染宿主诊断日志。
        val compactPayload = payload
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .take(WEB_PERFORMANCE_DIAGNOSTIC_PAYLOAD_LIMIT)
        if (compactPayload.isBlank()) {
            return false
        }
        recordWebPerformanceDiagnosticPayload(compactPayload)
        return true
    }
}
