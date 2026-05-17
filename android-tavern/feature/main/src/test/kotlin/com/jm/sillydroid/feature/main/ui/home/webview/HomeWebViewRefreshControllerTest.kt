package com.jm.sillydroid.feature.main.ui.home.webview

import android.view.View
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 覆盖 renderer 重建后控制器是否会切换到新 WebView 实例。
 * 通过 mockito-inline mock 掉 final 类（WebView/SwipeRefreshLayout/View/WebReloadTracer），
 * 不依赖真实 Android runtime。
 */
class HomeWebViewRefreshControllerTest {

    private fun newController(
        refreshLayout: SwipeRefreshLayout,
        overlay: View,
        webViewRef: () -> WebView,
        tracer: WebReloadTracer = mock(),
        pullRefreshEnabled: Boolean = true,
        pullGestureRefreshing: Boolean = false,
        imeVisible: Boolean = false
    ): HomeWebViewRefreshController {
        return HomeWebViewRefreshController(
            refreshLayout = refreshLayout,
            webViewProvider = webViewRef,
            bootstrapOverlay = overlay,
            pullRefreshEnabled = { pullRefreshEnabled },
            pullGestureRefreshing = { pullGestureRefreshing },
            setPullGestureRefreshing = { /* no-op */ },
            imeVisible = { imeVisible },
            reloadTracer = tracer
        )
    }

    private fun visibleMock(): View = mock<View>().also {
        whenever(it.visibility).thenReturn(View.VISIBLE)
    }

    private fun visibleWebView(): WebView = mock<WebView>().also {
        whenever(it.visibility).thenReturn(View.VISIBLE)
    }

    private fun visibleRefreshLayout(): SwipeRefreshLayout = mock<SwipeRefreshLayout>().also {
        whenever(it.visibility).thenReturn(View.VISIBLE)
    }

    @Test
    fun `reload uses latest webView from provider after renderer recreate`() {
        val refreshLayout = visibleRefreshLayout()
        val overlay = mock<View>().also { whenever(it.visibility).thenReturn(View.GONE) }
        val oldWebView = visibleWebView()
        val newWebView = visibleWebView()
        var current: WebView = oldWebView

        val controller = newController(
            refreshLayout = refreshLayout,
            overlay = overlay,
            webViewRef = { current }
        )

        // 模拟 renderer crash 后 host 把 webView 替换为新实例。
        current = newWebView

        val accepted = controller.reload(source = "test")

        assert(accepted) { "reload should be accepted when overlay hidden and webView visible" }
        verify(newWebView).reload()
        verify(oldWebView, never()).reload()
    }

    @Test
    fun `reload is rejected when overlay visible`() {
        val refreshLayout = visibleRefreshLayout()
        val overlay = mock<View>().also { whenever(it.visibility).thenReturn(View.VISIBLE) }
        val webView = visibleWebView()

        val controller = newController(
            refreshLayout = refreshLayout,
            overlay = overlay,
            webViewRef = { webView }
        )

        val accepted = controller.reload(source = "blocked_by_overlay")
        assert(!accepted)
        verify(webView, never()).reload()
    }

    @Test
    fun `updateEnabled reflects latest webView visibility`() {
        val refreshLayout = visibleRefreshLayout()
        val overlay = mock<View>().also { whenever(it.visibility).thenReturn(View.GONE) }
        val oldWebView = mock<WebView>().also { whenever(it.visibility).thenReturn(View.GONE) }
        val newWebView = visibleWebView()
        var current: WebView = oldWebView

        val controller = newController(
            refreshLayout = refreshLayout,
            overlay = overlay,
            webViewRef = { current }
        )

        controller.updateEnabled()
        verify(refreshLayout).isEnabled = false

        current = newWebView
        controller.updateEnabled()
        verify(refreshLayout, atLeastOnce()).isEnabled = true
    }

    @Test
    fun `canStartSwipeRefresh respects fresh webView visibility`() {
        val refreshLayout = visibleRefreshLayout()
        val overlay = mock<View>().also { whenever(it.visibility).thenReturn(View.GONE) }
        var current: WebView = mock<WebView>().also { whenever(it.visibility).thenReturn(View.GONE) }

        val controller = newController(
            refreshLayout = refreshLayout,
            overlay = overlay,
            webViewRef = { current }
        )

        assert(!controller.canStartSwipeRefresh())
        current = visibleWebView()
        assert(controller.canStartSwipeRefresh())
    }
}
