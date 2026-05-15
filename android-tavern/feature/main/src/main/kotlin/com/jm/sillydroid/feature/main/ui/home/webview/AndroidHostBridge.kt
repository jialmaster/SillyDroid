package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.JavascriptInterface

class AndroidHostBridge(
    private val isHostActive: () -> Boolean,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val openSettings: () -> Unit,
    private val showFloatingLogsBubble: () -> Unit,
    private val setFloatingLogsBubbleEnabled: (Boolean) -> Unit,
    private val setWebViewPullRefreshEnabled: (Boolean) -> Unit,
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
            setFloatingLogsBubbleEnabled(enabled)
        }
        return true
    }

    @JavascriptInterface
    fun setWebViewPullRefreshEnabled(enabled: Boolean): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread {
            setWebViewPullRefreshEnabled(enabled)
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
