package com.jm.sillydroid.feature.main.ui.home.webview

import android.view.View
import android.webkit.WebView
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class HomeWebViewRefreshController(
    private val refreshLayout: SwipeRefreshLayout,
    private val webView: WebView,
    private val bootstrapOverlay: View,
    private val pullRefreshEnabled: () -> Boolean,
    private val pullGestureRefreshing: () -> Boolean,
    private val setPullGestureRefreshing: (Boolean) -> Unit,
    private val imeVisible: () -> Boolean,
    private val reloadTracer: WebReloadTracer
) {
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
        return refreshLayout.isVisible &&
            webView.isVisible &&
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
        if (!webView.isVisible || bootstrapOverlay.isVisible) {
            reloadTracer.log(
                phase = "reload_blocked",
                extra = "webViewVisible=${webView.isVisible},overlayVisible=${bootstrapOverlay.isVisible}"
            )
            return false
        }

        reloadTracer.beginIfSourceChanged(source)
        reloadTracer.log(phase = "reload_requested", url = webView.url)
        webView.reload()
        reloadTracer.log(phase = "reload_dispatched", url = webView.url)
        return true
    }
}
