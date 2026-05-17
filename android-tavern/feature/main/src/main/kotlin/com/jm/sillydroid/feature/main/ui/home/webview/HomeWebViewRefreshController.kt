package com.jm.sillydroid.feature.main.ui.home.webview

import android.view.View
import android.webkit.WebView
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 刷新逻辑通过 [webViewProvider] 按需取当前 WebView 引用，
 * 避免把一次性的 View 实例写死在回调里；这样无论后续是 Activity 重建还是局部 View 重建，
 * 这里拿到的都是当前仍然有效的 WebView。
 * `refreshLayout` 与 `bootstrapOverlay` 在布局里是固定容器，不会被替换，可以直接持引用。
 */
class HomeWebViewRefreshController(
    private val refreshLayout: SwipeRefreshLayout,
    private val webViewProvider: () -> WebView,
    private val bootstrapOverlay: View,
    private val pullRefreshEnabled: () -> Boolean,
    private val pullGestureRefreshing: () -> Boolean,
    private val setPullGestureRefreshing: (Boolean) -> Unit,
    private val imeVisible: () -> Boolean,
    private val reloadTracer: WebReloadTracer
) {
    private val webView: WebView
        get() = webViewProvider()

    fun configure(backgroundColor: Int) {
        refreshLayout.isEnabled = false
        refreshLayout.setBackgroundColor(backgroundColor)
        webView.setBackgroundColor(backgroundColor)
        refreshLayout.setOnChildScrollUpCallback { _, _ ->
            !canStartSwipeRefresh() || webView.canScrollVertically(-1)
        }
        refreshLayout.setOnRefreshListener {
            if (!canStartSwipeRefresh()) {
                refreshLayout.isRefreshing = false
                return@setOnRefreshListener
            }
            setPullGestureRefreshing(true)
            reloadTracer.begin(source = "swipe_refresh")
            reloadTracer.log(phase = "on_refresh")
            if (!reload(source = "swipe_refresh")) {
                reloadTracer.log(phase = "reload_rejected")
                reloadTracer.clear()
            }
        }
    }

    fun canStartSwipeRefresh(): Boolean {
        val currentWebView = webView
        return refreshLayout.isVisible &&
            currentWebView.isVisible &&
            pullRefreshEnabled() &&
            !bootstrapOverlay.isVisible &&
            !pullGestureRefreshing() &&
            !imeVisible()
    }

    fun updateEnabled() {
        refreshLayout.isEnabled = refreshLayout.isVisible &&
            webView.isVisible &&
            pullRefreshEnabled() &&
            !bootstrapOverlay.isVisible &&
            !imeVisible()
    }

    fun reload(source: String): Boolean {
        val currentWebView = webView
        if (!currentWebView.isVisible || bootstrapOverlay.isVisible) {
            reloadTracer.log(
                phase = "reload_blocked",
                extra = "webViewVisible=${currentWebView.isVisible},overlayVisible=${bootstrapOverlay.isVisible}"
            )
            return false
        }

        reloadTracer.beginIfSourceChanged(source)
        reloadTracer.log(phase = "reload_requested", url = currentWebView.url)
        currentWebView.reload()
        reloadTracer.log(phase = "reload_dispatched", url = currentWebView.url)
        return true
    }
}
