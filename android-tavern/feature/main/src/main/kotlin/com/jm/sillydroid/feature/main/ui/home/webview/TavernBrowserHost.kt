package com.jm.sillydroid.feature.main.ui.home.webview

import android.view.View
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.BrowserEngine

/**
 * 主界面内嵌浏览器的最小宿主契约。
 *
 * 系统 WebView 和 GeckoView 分别实现自己的 bridge/download/file/diagnostic 通道，
 * MainActivity/Bootstrap overlay 只依赖这一层宿主契约，避免继续硬编码 WebView 类型。
 */
interface TavernBrowserHost {
    val browserEngine: BrowserEngine
    val browserContainer: View
    val browserSurface: View

    fun currentBrowserRuntimeInfo(): BrowserRuntimeInfo
    fun currentBrowserZoomPercent(): Int
    fun currentBrowserPageZoomPercent(): Int
    fun configure()
    fun setBrowserZoomPercent(percent: Int): Boolean
    fun setBrowserPageZoomPercent(percent: Int): Boolean
    fun showBrowser(baseUrl: String)
    fun hideForBootstrapRestart()
    fun reloadTavernUiIfPossible(snapshot: BootstrapSessionSnapshot)
    fun reloadTavernWebView(source: String): Boolean
    fun updateRefreshLayoutEnabled()
    fun resetRefreshOnBootstrapEvent()
    fun onImeVisibilityChanged(visible: Boolean)
    fun onTrimMemory(level: Int)
    fun onLowMemory()
    fun onDestroy()
    fun openUrlInExternalBrowser(url: String): Boolean
    fun openCurrentPageInExternalBrowser(): Boolean
}
