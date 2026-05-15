package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel

/**
 * 把 WebView 与下拉刷新栈、Web 会话持久化、page lifecycle、本地重试、renderer crash 重建、
 * URL 工具函数（local 判断 / 外开浏览器）等收拢到一个 host。
 *
 * MainActivity 持有一个实例，并通过构造参数注入需要的跨 host 回调（JS 桥安装、blob 下载桥脚本、
 * 文件选择器、下载行为、外部回到 ready 时的 prompt 等）。
 */
class TavernWebViewHost(
    private val activity: AppCompatActivity,
    private val homeViewModel: HomeViewModel,
    private val hostConfigStore: HostPreferencesRepository,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val processManager: BootstrapController,
    private val installJavascriptInterfaces: (WebView) -> Unit,
    private val installBlobBridgeScriptOnPageFinished: (WebView) -> Unit,
    private val onDownloadRequested: (BrowserDownloadRequest) -> Unit,
    private val onShowFileChooser: (Intent, android.webkit.ValueCallback<Array<Uri>>) -> Unit,
) {
    companion object {
        private const val LOG_TAG = "SillyDroidMain"
        private const val WEB_VIEW_STATE_KEY = "tavern.webview.state"
        private const val LOADED_URL_STATE_KEY = "tavern.webview.loadedUrl"
        private const val WEB_SESSION_BRIDGE_NAME = "StaiWebSessionBridge"
        private const val SYSTEM_NOTIFICATION_BRIDGE_NAME = "AndroidSystemNotificationBridge"
        private const val WEB_SESSION_STORAGE_PREFS_NAME = "sillydroid-webview-session"
        private const val WEB_SESSION_STORAGE_SNAPSHOT_KEY = "session-storage"
    }

    val webViewRefreshLayout: SwipeRefreshLayout = activity.findViewById(R.id.webViewRefreshLayout)
    var webView: WebView = activity.findViewById(R.id.webView)
        private set

    // overlay view 同时被 BootstrapOverlayHost 持有；这里只读它的 isVisible 来配合下拉刷新逻辑，
    // 以及在 showWebView / hideForBootstrapRestart 时一并切换可见性，保持一对一互斥。
    private val bootstrapOverlay: android.view.View = activity.findViewById(R.id.bootstrapOverlay)

    private var webSessionPersistenceController: WebSessionPersistenceController? = null

    private val webSessionStoragePreferences by lazy {
        activity.getSharedPreferences(WEB_SESSION_STORAGE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val webReloadTracer by lazy { WebReloadTracer(LOG_TAG) }

    private val homeWebViewController by lazy {
        HomeWebViewController(
            context = activity,
            webViewProvider = { webView },
            installSessionPersistence = ::installWebSessionPersistenceController,
            installJavascriptInterfaces = installJavascriptInterfaces,
            shouldOpenExternally = ::shouldOpenExternally,
            openExternalBrowser = ::openExternalBrowser,
            onPageStarted = { url -> logActiveWebReloadTrace(phase = "page_started", url = url) },
            onPageCommitVisible = { url -> logActiveWebReloadTrace(phase = "page_commit_visible", url = url) },
            onPageFinished = ::handleWebViewPageFinished,
            isLocalTavernUrl = ::isLocalTavernUrl,
            onMainFrameLocalLoadError = ::scheduleLocalWebViewRetry,
            onRendererGone = ::handleWebViewRendererGone,
            onDownloadRequested = onDownloadRequested,
            onShowFileChooser = { filePathCallback, fileChooserParams ->
                onShowFileChooser(fileChooserParams.createIntent(), filePathCallback)
            }
        )
    }

    private val homeWebViewRefreshController by lazy {
        HomeWebViewRefreshController(
            refreshLayout = webViewRefreshLayout,
            webView = webView,
            bootstrapOverlay = bootstrapOverlay,
            pullRefreshEnabled = { hostConfigStore.webViewPullRefreshEnabled },
            pullGestureRefreshing = { homeViewModel.isPullGestureRefreshing },
            setPullGestureRefreshing = { refreshing -> homeViewModel.isPullGestureRefreshing = refreshing },
            imeVisible = { homeViewModel.isImeVisible },
            reloadTracer = webReloadTracer
        )
    }

    fun configure() {
        val webViewBackgroundColor = ContextCompat.getColor(activity, R.color.tavern_webview_background)
        homeWebViewRefreshController.configure(webViewBackgroundColor)
        homeWebViewController.configure()
    }

    fun saveState(outState: Bundle) {
        // Activity 被系统回收后，优先恢复 WebView 现有会话，避免重新 load baseUrl 把页面打回首页。
        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(WEB_VIEW_STATE_KEY, webViewState)
        outState.putString(LOADED_URL_STATE_KEY, homeViewModel.loadedUrl)
    }

    fun restoreState(savedInstanceState: Bundle?) {
        val webViewState = savedInstanceState?.getBundle(WEB_VIEW_STATE_KEY) ?: return
        val restoredState = webView.restoreState(webViewState)
        val restoredUrl = restoredState?.currentItem?.url.orEmpty()
            .ifBlank { savedInstanceState.getString(LOADED_URL_STATE_KEY).orEmpty() }

        if (restoredUrl.isBlank()) {
            return
        }

        // 恢复出来的 URL 可能在上一轮会话中使用了不同的服务端口。
        // 若与当前 localUrl 不匹配，则不能复用，避免 WebView 以旧端口发起请求
        // 造成永久 ERR_CONNECTION_REFUSED 白屏。
        if (!isLocalTavernUrl(restoredUrl)) {
            return
        }

        homeViewModel.loadedUrl = restoredUrl
        homeViewModel.hasRestoredWebViewState = true
    }

    fun showWebView(baseUrl: String) {
        bootstrapOverlay.isVisible = false
        webViewRefreshLayout.isVisible = true
        webView.isVisible = true
        updateRefreshLayoutEnabled()
        if (homeViewModel.hasRestoredWebViewState) {
            // 已恢复出原来的 WebView 会话时，不再重新 load baseUrl，避免把前端状态重置到首页。
            homeViewModel.hasRestoredWebViewState = false
            return
        }

        if (isCurrentWebViewPageFor(baseUrl)) {
            return
        }

        val targetUrl = buildInitialWebViewUrl(baseUrl)
        homeViewModel.loadedUrl = targetUrl
        webView.loadUrl(targetUrl)
    }

    fun hideForBootstrapRestart() {
        webViewRefreshLayout.isVisible = false
        webView.isVisible = false
    }

    fun reloadTavernUiIfPossible(snapshot: BootstrapSessionSnapshot) {
        if (!snapshot.isReady || !webView.isVisible) {
            return
        }
        reloadTavernWebView(source = "host_state_ready")
    }

    fun reloadTavernWebView(source: String): Boolean {
        return homeWebViewRefreshController.reload(source)
    }

    fun updateRefreshLayoutEnabled() {
        homeWebViewRefreshController.updateEnabled()
    }

    fun resetRefreshOnBootstrapEvent() {
        webViewRefreshLayout.isRefreshing = false
        homeViewModel.isPullGestureRefreshing = false
    }

    fun onImeVisibilityChanged(visible: Boolean) {
        if (visible) {
            webViewRefreshLayout.isRefreshing = false
        }
        updateRefreshLayoutEnabled()
    }

    fun onDestroy() {
        webSessionPersistenceController?.close()
        webSessionPersistenceController = null
    }

    fun shouldOpenExternally(targetUri: Uri): Boolean {
        return !isLocalTavernUri(targetUri)
    }

    fun isLocalTavernUri(targetUri: Uri): Boolean {
        val localUri = Uri.parse(runtimeConfigRepository.localServiceUrl())
        val targetScheme = targetUri.scheme.orEmpty()
        if (!targetScheme.equals(localUri.scheme.orEmpty(), ignoreCase = true)) {
            return false
        }

        val targetHost = targetUri.host.orEmpty()
        val localHost = localUri.host.orEmpty()
        if (!targetHost.equals(localHost, ignoreCase = true)) {
            return false
        }

        return normalizedPort(targetUri) == normalizedPort(localUri)
    }

    fun isLocalTavernUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return isLocalTavernUri(parsed)
    }

    fun openExternalBrowser(targetUri: Uri): Boolean {
        return try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, targetUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.browser_open_external_failed, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun handleWebViewPageFinished(url: String?) {
        logActiveWebReloadTrace(phase = "page_finished", url = url)
        homeViewModel.isPullGestureRefreshing = false
        webViewRefreshLayout.isRefreshing = false
        updateRefreshLayoutEnabled()
        CookieManager.getInstance().flush()
        installBlobBridgeScriptOnPageFinished(webView)
        if (!url.isNullOrBlank()) {
            homeViewModel.loadedUrl = url
            homeViewModel.pendingLocalRetryAttempts = 0
        }
        clearActiveWebReloadTrace()
    }

    private fun handleWebViewRendererGone(didCrash: Boolean) {
        Log.e(
            LOG_TAG,
            "WebView renderer gone (didCrash=$didCrash). Recreating WebView to keep host process alive."
        )
        if (!activity.isFinishing && !activity.isDestroyed) {
            recreateWebViewAfterRendererGone()
        }
    }

    private fun installWebSessionPersistenceController() {
        webSessionPersistenceController?.close()
        webSessionPersistenceController = WebSessionPersistenceController(
            webView = webView,
            preferences = webSessionStoragePreferences,
            storageKey = WEB_SESSION_STORAGE_SNAPSHOT_KEY,
            bridgeName = WEB_SESSION_BRIDGE_NAME,
            systemNotificationBridgeName = SYSTEM_NOTIFICATION_BRIDGE_NAME,
            allowedOrigin = { runtimeConfigRepository.localServiceUrl() }
        ).also { controller ->
            controller.install()
        }
    }

    private fun scheduleLocalWebViewRetry(failingUrl: String) {
        if (homeViewModel.pendingLocalRetryAttempts >= 5) {
            // 估计是服务侧長期起不来；交给 startup overlay 接手，不再闪烁。
            return
        }
        if (!processManager.currentSnapshot().isReady) {
            return
        }
        homeViewModel.pendingLocalRetryAttempts += 1
        val delayMillis = (500L * homeViewModel.pendingLocalRetryAttempts).coerceAtMost(3_000L)
        webView.postDelayed(
            {
                if (activity.isFinishing || activity.isDestroyed) {
                    return@postDelayed
                }
                if (!processManager.currentSnapshot().isReady) {
                    return@postDelayed
                }
                if (failingUrl == homeViewModel.loadedUrl || failingUrl.startsWith(homeViewModel.loadedUrl.trimEnd('/'))) {
                    webView.loadUrl(failingUrl)
                } else {
                    webView.reload()
                }
            },
            delayMillis
        )
    }

    private fun recreateWebViewAfterRendererGone() {
        val crashedWebView = webView
        val parent = crashedWebView.parent as? ViewGroup
        val indexInParent = parent?.indexOfChild(crashedWebView) ?: -1
        val layoutParams = crashedWebView.layoutParams
        val targetUrl = processManager.currentSnapshot().localUrl
            .ifBlank { runtimeConfigRepository.localServiceUrl() }

        // 旧 WebView 必须先从视图树移除再 destroy，避免 native 资源泄漏。
        webSessionPersistenceController?.close()
        webSessionPersistenceController = null
        parent?.removeView(crashedWebView)
        runCatching { crashedWebView.destroy() }

        val newWebView = WebView(activity).apply {
            id = R.id.webView
        }
        if (parent != null) {
            if (indexInParent >= 0 && layoutParams != null) {
                parent.addView(newWebView, indexInParent, layoutParams)
            } else if (layoutParams != null) {
                parent.addView(newWebView, layoutParams)
            } else {
                parent.addView(newWebView)
            }
        }
        webView = newWebView
        // 重新走一遍与初始化一致的配置。
        configure()
        homeViewModel.resetAfterRendererRecreated()
        if (processManager.currentSnapshot().isReady) {
            showWebView(targetUrl)
        }
    }

    private fun isCurrentWebViewPageFor(baseUrl: String): Boolean {
        val currentUrl = webView.url.orEmpty().ifBlank { homeViewModel.loadedUrl }
        if (currentUrl.isBlank()) {
            return false
        }

        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedCurrentUrl = currentUrl.trim()

        // 回到前台时只要 WebView 还停留在同一个本地 Tavern 站点，就复用现有页面，避免再次 loadUrl 触发前端初始化。
        return normalizedCurrentUrl == normalizedBaseUrl ||
            normalizedCurrentUrl == "$normalizedBaseUrl/" ||
            normalizedCurrentUrl.startsWith("$normalizedBaseUrl/#") ||
            normalizedCurrentUrl.startsWith("$normalizedBaseUrl/?") ||
            normalizedCurrentUrl.startsWith("$normalizedBaseUrl/")
    }

    private fun buildInitialWebViewUrl(baseUrl: String): String {
        return "${baseUrl.trim().trimEnd('/')}/"
    }

    private fun normalizedPort(uri: Uri): Int {
        if (uri.port != -1) {
            return uri.port
        }
        return when (uri.scheme?.lowercase()) {
            "https" -> 443
            else -> 80
        }
    }

    private fun logActiveWebReloadTrace(phase: String, url: String? = null, extra: String? = null) {
        webReloadTracer.log(phase = phase, url = url, extra = extra)
    }

    private fun clearActiveWebReloadTrace() {
        webReloadTracer.clear()
    }
}
