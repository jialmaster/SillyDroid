package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.JavascriptInterface

class AndroidHostBridge(
    private val isHostActive: () -> Boolean,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val openSettings: () -> Unit,
    private val showFloatingLogsBubble: () -> Unit,
    // 这里必须和 @JavascriptInterface 方法名区分开，避免 Kotlin 在 lambda 里解析成当前桥接方法并递归调用。
    private val applyFloatingLogsBubbleEnabled: (Boolean) -> Unit,
    private val applyWebViewPullRefreshEnabled: (Boolean) -> Unit,
    private val reloadTavern: () -> Unit,
    private val hostVersionInfoJson: () -> String
) {
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
}
