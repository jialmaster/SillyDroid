package com.jm.sillydroid.feature.main.ui.home.webview

import android.view.View
import android.view.ViewGroup
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

    /** 当前内核进程内会话标识，仅用于确认迁移前后仍是同一实例，不得持久化为用户标识。 */
    val browserSessionIdentity: String
        get() = "${browserEngine.name}:${System.identityHashCode(browserSurface)}"

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
    /** Activity 销毁时只释放 UI delegate 和回调；实现不得销毁进程级浏览器会话。 */
    fun releaseActivityBindings() = onDestroy()
    /** 当前 host 是否已经加载真实浏览器页面并允许迁移到系统 overlay。 */
    fun canAttachToFloatingBrowser(): Boolean = false
    /** 把现有浏览器表面迁移到 overlay 容器；实现不得调用任何导航或刷新 API。 */
    fun attachToFloatingBrowser(container: ViewGroup): Boolean = false
    /** overlay Window 已挂载并可绘制后触发内核专属页面可见性诊断；不得导航或刷新页面。 */
    fun onFloatingBrowserWindowVisible() = Unit
    /** 把同一浏览器表面挂回 Activity 原容器；实现不得重建浏览器会话。 */
    fun attachToActivityBrowser(): Boolean = false
    fun openUrlInExternalBrowser(url: String): Boolean
    fun openCurrentPageInExternalBrowser(): Boolean
}
