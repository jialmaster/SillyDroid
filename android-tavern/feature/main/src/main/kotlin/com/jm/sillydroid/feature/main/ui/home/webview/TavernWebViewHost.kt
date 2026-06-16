package com.jm.sillydroid.feature.main.ui.home.webview

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebStorage
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewCompat
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.BrowserDataClearOptions
import com.jm.sillydroid.core.model.settings.BrowserDataClearTarget
import com.jm.sillydroid.core.model.settings.BrowserZoomOptions
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.diagnostics.formatTrimMemoryLevel
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeInstaller
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeTarget
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel

class TavernWebViewHost(
    private val activity: AppCompatActivity,
    private val homeViewModel: HomeViewModel,
    private val hostConfigStore: HostPreferencesRepository,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val processManager: BootstrapController,
    private val bridgeInstaller: BrowserHostBridgeInstaller,
    private val restoreHostSystemBarAppearance: () -> Unit = {},
    private val onDownloadRequested: (BrowserDownloadRequest) -> Unit,
    private val onShowFileChooser: (android.webkit.WebChromeClient.FileChooserParams, android.webkit.ValueCallback<Array<Uri>>) -> Unit,
    private val jsErrorSink: WebViewJsErrorSink = WebViewJsErrorSink { /* no-op */ },
    private val hostDiagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> },
    private val criticalHostDiagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> },
    private val refreshApplicationExitInfo: () -> Unit = {},
    private val uploadRendererGoneLogBundle: (WebViewRendererGoneInfo) -> Unit = {},
    private val onWebViewRendererCrash: () -> Unit = {},
    private val onDocumentStartScriptUnsupported: () -> Unit = {},
) : TavernBrowserHost {
    companion object {
        private const val LOG_TAG = "SillyDroidMain"
        // 仅 debug 包响应；用来手动踏 renderer-gone 路径。
        // adb shell am broadcast -a com.jm.sillydroid.debug.CRASH_RENDERER -p com.jm.sillydroid
        // adb shell am broadcast -a com.jm.sillydroid.debug.KILL_RENDERER  -p com.jm.sillydroid
        private const val DEBUG_ACTION_CRASH_RENDERER = "com.jm.sillydroid.debug.CRASH_RENDERER"
        private const val DEBUG_ACTION_KILL_RENDERER = "com.jm.sillydroid.debug.KILL_RENDERER"
        // ApplicationExitInfo 写入系统历史退出列表存在机型级延迟；renderer gone 后补几次刷新，
        // 提高下一次启动自动上传或用户手动导出时命中“刚刚那次 WebView renderer 退出”的概率。
        private val RENDERER_EXIT_INFO_REFRESH_DELAYS_MS = longArrayOf(1_500L, 5_000L)
        // trimMemory 可能在压力期间密集触发；内存资源缓存释放需要节流，避免持续清理反而拖慢前台页面。
        private const val VOLATILE_WEBVIEW_CACHE_CLEAR_MIN_INTERVAL_MS = 30_000L
    }

    override val browserEngine: BrowserEngine = BrowserEngine.SYSTEM_WEBVIEW
    val webViewRefreshLayout: ViewGroup = activity.findViewById(R.id.webViewRefreshLayout)
    val webView: WebView = WebView(activity).also { createdWebView ->
        // 浏览器 surface 由具体 host 动态创建，避免 GeckoView 模式也提前 inflate 系统 WebView provider。
        createdWebView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webViewRefreshLayout.addView(createdWebView)
    }
    override val browserContainer: View
        get() = webViewRefreshLayout
    override val browserSurface: View
        get() = webView
    private val webViewPullRefreshHint: LinearLayout = activity.findViewById(R.id.webViewPullRefreshHint)
    private val webViewPullRefreshHintArc: View = activity.findViewById(R.id.webViewPullRefreshHintArc)
    private val webViewPullRefreshHintIcon: ImageView = activity.findViewById(R.id.webViewPullRefreshHintIcon)
    private val webViewPullRefreshHintText: TextView = activity.findViewById(R.id.webViewPullRefreshHintText)

    // overlay view 同时被 BootstrapOverlayHost 持有；这里只读它的 isVisible 来配合下拉刷新逻辑，
    // 以及在 showWebView / hideForBootstrapRestart 时一并切换可见性，保持一对一互斥。
    private val bootstrapOverlay: android.view.View = activity.findViewById(R.id.bootstrapOverlay)

    private var webDocumentStartScriptController: WebDocumentStartScriptController? = null

    private val activityManager by lazy {
        activity.getSystemService(ActivityManager::class.java)
    }

    private val mainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    private var rendererRecoveryActivityRecreateScheduled = false
    private var lastVolatileWebViewCacheClearElapsedMs = -1L
    private val webReloadTracer by lazy { WebReloadTracer(LOG_TAG) }
    private val httpAuthPromptController by lazy {
        HttpAuthPromptController(
            activity = activity,
            diagnosticSink = HostDiagnosticSink { category, body ->
                recordHostDiagnostic(category = category, body = body)
            }
        )
    }

    private val homeWebViewController by lazy {
        HomeWebViewController(
            context = activity,
            webViewProvider = { webView },
            installDocumentStartScripts = ::installWebDocumentStartScriptController,
            installJavascriptInterfaces = ::installBrowserHostBridge,
            shouldOpenExternally = ::shouldOpenExternally,
            openExternalBrowser = ::openExternalBrowser,
            onPageStarted = ::handleWebViewPageStarted,
            onPageCommitVisible = ::handleWebViewPageCommitVisible,
            onPageFinished = ::handleWebViewPageFinished,
            isLocalTavernUrl = ::isLocalTavernUrl,
            onHttpAuthRequest = httpAuthPromptController::show,
            onMainFrameLocalLoadError = ::scheduleLocalWebViewRetry,
            onRendererGone = ::handleWebViewRendererGone,
            onDownloadRequested = onDownloadRequested,
            onShowFileChooser = { filePathCallback, fileChooserParams ->
                onShowFileChooser(fileChooserParams, filePathCallback)
            },
            downloadDiagnosticSink = { body ->
                recordHostDiagnostic(category = "download", body = body)
            },
            jsErrorSink = jsErrorSink
        )
    }

    private val homeWebViewRefreshController by lazy {
        HomeWebViewRefreshController(
            refreshContainer = webViewRefreshLayout,
            webViewProvider = { webView },
            pullRefreshHintViews = PullRefreshHintViews(
                container = webViewPullRefreshHint,
                arc = webViewPullRefreshHintArc,
                icon = webViewPullRefreshHintIcon,
                text = webViewPullRefreshHintText
            ),
            bootstrapOverlay = bootstrapOverlay,
            pullRefreshEnabled = { hostConfigStore.webViewPullRefreshEnabled },
            pullGestureRefreshing = { homeViewModel.isPullGestureRefreshing },
            setPullGestureRefreshing = { refreshing -> homeViewModel.isPullGestureRefreshing = refreshing },
            imeVisible = { homeViewModel.isImeVisible },
            reloadTracer = webReloadTracer,
            diagnosticSink = { body ->
                recordHostDiagnostic(category = "pull_refresh", body = body)
            }
        )
    }

    private var debugRendererCrashReceiver: BroadcastReceiver? = null

    fun currentRuntimeCompatibility(): WebViewRuntimeCompatibility {
        return WebViewRuntimeCompatibility.from(
            webView = webView,
            providerPackageInfo = currentWebViewPackageInfo()
        )
    }

    override fun currentBrowserRuntimeInfo(): BrowserRuntimeInfo {
        val compatibility = currentRuntimeCompatibility()
        return BrowserRuntimeInfo(
            engine = browserEngine,
            runtimeName = "Android WebView",
            packageName = compatibility.providerPackageName,
            versionName = compatibility.providerVersionName,
            versionCode = compatibility.providerVersionCode,
            coreName = "Chromium",
            coreVersion = compatibility.chromiumVersion,
            coreMajorVersion = compatibility.chromiumMajorVersion,
            recommendedCoreMajorVersion = WebViewRuntimeCompatibility.recommendedChromiumMajorVersion(),
            outdated = compatibility.isOutdated,
            userAgent = compatibility.userAgent
        )
    }

    override fun currentBrowserZoomPercent(): Int {
        return BrowserZoomOptions.sanitize(hostConfigStore.browserZoomPercent)
    }

    override fun currentBrowserPageZoomPercent(): Int {
        return BrowserZoomOptions.sanitizeViewportDensity(hostConfigStore.browserPageZoomPercent)
    }

    override fun configure() {
        val webViewBackgroundColor = ContextCompat.getColor(activity, R.color.tavern_webview_background)
        homeWebViewRefreshController.configure(webViewBackgroundColor)
        homeWebViewController.configure()
        setBrowserZoomPercent(hostConfigStore.browserZoomPercent)
        setBrowserPageZoomPercent(hostConfigStore.browserPageZoomPercent)
        restoreHostSystemBarAppearance()
        installDebugRendererCrashReceiverIfDebuggable()
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=configured ")
                append(resolveWebViewProviderSummary())
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
    }

    override fun showBrowser(baseUrl: String) {
        showWebView(baseUrl)
    }

    fun showWebView(baseUrl: String) {
        bootstrapOverlay.isVisible = false
        webViewRefreshLayout.isVisible = true
        webView.isVisible = true
        updateRefreshLayoutEnabled()
        if (homeViewModel.shouldForceFreshWebViewLoad) {
            forceFreshWebViewLoad(baseUrl)
            return
        }

        if (isCurrentWebViewPageFor(baseUrl)) {
            return
        }

        val targetUrl = buildInitialTavernUrl(baseUrl)
        homeViewModel.loadedUrl = targetUrl
        recordCriticalHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=show_webview_load_url")
                append(" targetUrl=${normalizeDiagnosticValue(targetUrl)}")
                append(" baseUrl=${normalizeDiagnosticValue(buildInitialTavernUrl(baseUrl))}")
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
        webView.loadUrl(targetUrl)
    }

    override fun hideForBootstrapRestart() {
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
        webViewRefreshLayout.isVisible = false
        webView.isVisible = false
        // 返回 bootstrap overlay 时，把系统栏背景也切回宿主自己的遮罩色，避免残留上一页的 WebView 主题色。
        restoreHostSystemBarAppearance()
    }

    private fun forceFreshWebViewLoad(baseUrl: String) {
        val targetUrl = buildInitialTavernUrl(baseUrl)
        val requestedClearMask = homeViewModel.browserDataClearMask
        val clearMask = if (requestedClearMask == 0) {
            BrowserDataClearOptions.fullMask
        } else {
            BrowserDataClearOptions.normalizeOrDefault(requestedClearMask)
        }
        // 清空宿主数据并重解压后，WebView 不能继续复用旧内存页面 / history / cache / sessionStorage；
        // 否则 showWebView 会因为还是同一 local URL 而跳过 loadUrl，用户仍看到旧前端信息。
        homeViewModel.shouldForceFreshWebViewLoad = false
        homeViewModel.browserDataClearMask = 0
        homeViewModel.pendingLocalRetryAttempts = 0
        homeViewModel.loadedUrl = targetUrl
        clearCurrentPageSessionState(clearMask)
        clearPersistedWebSiteState(clearMask)
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=force_fresh_webview_load")
                append(" targetUrl=${normalizeDiagnosticValue(targetUrl)}")
                append(" action=clear_selected_site_state_and_load")
                append(" clearMask=$clearMask")
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
        webView.loadUrl("about:blank")
        webView.loadUrl(targetUrl)
    }

    override fun reloadTavernUiIfPossible(snapshot: BootstrapSessionSnapshot) {
        if (!snapshot.isReady || !webView.isVisible) {
            return
        }
        reloadTavernWebView(source = "host_state_ready")
    }

    override fun reloadTavernWebView(source: String): Boolean {
        return homeWebViewRefreshController.reload(source)
    }

    override fun updateRefreshLayoutEnabled() {
        homeWebViewRefreshController.updateEnabled()
    }

    override fun resetRefreshOnBootstrapEvent() {
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
    }

    override fun onImeVisibilityChanged(visible: Boolean) {
        if (visible) {
            homeWebViewRefreshController.reset()
        }
        updateRefreshLayoutEnabled()
    }

    override fun onTrimMemory(level: Int) {
        val volatileCacheCleared = clearVolatileWebViewMemoryCache(
            reason = "trim_memory",
            trimLevel = level
        )
        recordCriticalHostDiagnostic(
            category = "memory",
            body = "scope=main_activity_webview event=on_trim_memory level=${formatTrimMemoryLevel(level)} rawLevel=$level volatileCacheCleared=$volatileCacheCleared ${currentWebViewDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
    }

    override fun onLowMemory() {
        val volatileCacheCleared = clearVolatileWebViewMemoryCache(
            reason = "low_memory",
            trimLevel = null
        )
        recordCriticalHostDiagnostic(
            category = "memory",
            body = "scope=main_activity_webview event=on_low_memory volatileCacheCleared=$volatileCacheCleared ${currentWebViewDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
    }

    override fun onDestroy() {
        // renderer gone 后安排的 exit-info 延迟刷新（postDelayed 1.5s/5s）必须随 Activity 销毁一并取消；
        // 否则回调会落到已销毁的宿主上下文，造成无意义的后台 exit-info 刷新与潜在泄漏。
        mainHandler.removeCallbacksAndMessages(null)
        uninstallDebugRendererCrashReceiver()
        webDocumentStartScriptController?.close()
        webDocumentStartScriptController = null
        httpAuthPromptController.dismissActivePrompt(reason = "webview_destroy")
        bridgeInstaller.close()
        destroyWebViewForActivityTeardown()
    }

    private fun destroyWebViewForActivityTeardown() {
        // Activity 重建或 renderer gone 恢复时会创建新的 WebView；旧实例必须主动拆桥、停载并销毁，
        // 否则大型 Tavern 前端残留的 Chromium 资源会拖高后续沙箱崩溃和内存压力概率。
        recordCriticalHostDiagnostic(
            category = "webview",
            body = "event=destroy_webview_started ${currentWebViewDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.webChromeClient = null }
        runCatching { webView.webViewClient = android.webkit.WebViewClient() }
        runCatching { webView.removeJavascriptInterface(bridgeInstaller.systemNotificationBridgeName) }
        runCatching { webView.removeJavascriptInterface(bridgeInstaller.androidHostBridgeName) }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
            .onFailure { error ->
                recordCriticalHostDiagnostic(
                    category = "webview",
                    body = "event=destroy_webview_failed error=${normalizeDiagnosticValue(error.message ?: error.javaClass.simpleName)} ${currentHostMemoryDiagnosticState()}"
                )
                return
            }
        recordCriticalHostDiagnostic(
            category = "webview",
            body = "event=destroy_webview_finished ${currentHostMemoryDiagnosticState()}"
        )
    }

    private fun clearVolatileWebViewMemoryCache(reason: String, trimLevel: Int?): Boolean {
        if (trimLevel != null && !shouldClearVolatileWebViewCacheForTrimMemory(trimLevel)) {
            return false
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (
            shouldThrottleVolatileWebViewCacheClear(
                nowElapsedMs = nowElapsedMs,
                lastClearElapsedMs = lastVolatileWebViewCacheClearElapsedMs,
                minIntervalMs = VOLATILE_WEBVIEW_CACHE_CLEAR_MIN_INTERVAL_MS
            )
        ) {
            recordCriticalHostDiagnostic(
                category = "memory",
                body = buildString {
                    append("scope=main_activity_webview event=clear_volatile_webview_cache_skipped")
                    append(" reason=$reason skip=throttled")
                    trimLevel?.let { level ->
                        append(" level=${formatTrimMemoryLevel(level)} rawLevel=$level")
                    }
                    append(" intervalMs=$VOLATILE_WEBVIEW_CACHE_CLEAR_MIN_INTERVAL_MS")
                    append(" elapsedSinceLastMs=${nowElapsedMs - lastVolatileWebViewCacheClearElapsedMs}")
                    append(' ')
                    append(currentHostMemoryDiagnosticState())
                }
            )
            return false
        }
        // includeDiskFiles=false 只释放 WebView 内存资源缓存；不能在内存压力回调里清磁盘缓存、
        // Cookie、IndexedDB、LocalStorage 或 Service Worker 数据，否则会破坏酒馆会话状态。
        return runCatching {
            webView.clearCache(false)
        }.fold(
            onSuccess = {
                lastVolatileWebViewCacheClearElapsedMs = nowElapsedMs
                recordCriticalHostDiagnostic(
                    category = "memory",
                    body = buildString {
                        append("scope=main_activity_webview event=clear_volatile_webview_cache")
                        append(" reason=$reason")
                        trimLevel?.let { level ->
                            append(" level=${formatTrimMemoryLevel(level)} rawLevel=$level")
                        }
                        append(" includeDiskFiles=false")
                        append(' ')
                        append(currentHostMemoryDiagnosticState())
                    }
                )
                true
            },
            onFailure = { error ->
                recordCriticalHostDiagnostic(
                    category = "memory",
                    body = "scope=main_activity_webview event=clear_volatile_webview_cache_failed reason=$reason error=${normalizeDiagnosticValue(error.message ?: error.javaClass.simpleName)} ${currentHostMemoryDiagnosticState()}"
                )
                false
            }
        )
    }

    private fun installDebugRendererCrashReceiverIfDebuggable() {
        val isDebuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return
        if (debugRendererCrashReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    DEBUG_ACTION_CRASH_RENDERER -> {
                        Log.w(LOG_TAG, "[debug] broadcast received: crashing renderer via chrome://crash")
                        webView.loadUrl("chrome://crash")
                    }
                    DEBUG_ACTION_KILL_RENDERER -> {
                        Log.w(LOG_TAG, "[debug] broadcast received: killing renderer via chrome://kill")
                        webView.loadUrl("chrome://kill")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(DEBUG_ACTION_CRASH_RENDERER)
            addAction(DEBUG_ACTION_KILL_RENDERER)
        }
        // Android 13+ (TIRAMISU) 要求明确指定 receiver exported 标志。
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        debugRendererCrashReceiver = receiver
        Log.i(LOG_TAG, "[debug] renderer crash broadcast receiver installed")
    }

    private fun uninstallDebugRendererCrashReceiver() {
        val receiver = debugRendererCrashReceiver ?: return
        debugRendererCrashReceiver = null
        runCatching { activity.unregisterReceiver(receiver) }
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

    private fun isCurrentWebViewInstance(sourceWebView: WebView): Boolean {
        return sourceWebView === webView
    }

    fun openExternalBrowser(targetUri: Uri): Boolean {
        return launchExternalBrowser(targetUri)
    }

    override fun openUrlInExternalBrowser(url: String): Boolean {
        val targetUrl = url.trim()
        if (targetUrl.isBlank()) {
            showOpenExternalBrowserFailure()
            return false
        }
        return launchExternalBrowser(Uri.parse(targetUrl))
    }

    override fun openCurrentPageInExternalBrowser(): Boolean {
        // 宿主设置里的“在浏览器中打开”必须带出当前酒馆页，避免外部浏览器只回首页后丢掉当前路由上下文。
        val targetUrl = currentKnownWebViewUrl().trim()
        if (targetUrl.isBlank()) {
            showOpenExternalBrowserFailure()
            return false
        }
        return launchExternalBrowser(Uri.parse(targetUrl))
    }

    private fun launchExternalBrowser(targetUri: Uri): Boolean {
        return try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, targetUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
            true
        } catch (_: ActivityNotFoundException) {
            showOpenExternalBrowserFailure()
            false
        }
    }

    private fun showOpenExternalBrowserFailure() {
        Toast.makeText(activity, R.string.browser_open_external_failed, Toast.LENGTH_SHORT).show()
    }

    private fun handleWebViewPageStarted(sourceWebView: WebView, url: String?) {
        if (!isCurrentWebViewInstance(sourceWebView)) {
            return
        }
        logActiveWebReloadTrace(phase = "page_started", url = url)
    }

    private fun handleWebViewPageCommitVisible(sourceWebView: WebView, url: String?) {
        if (!isCurrentWebViewInstance(sourceWebView)) {
            return
        }
        logActiveWebReloadTrace(phase = "page_commit_visible", url = url)
    }

    private fun handleWebViewPageFinished(sourceWebView: WebView, url: String?) {
        if (!isCurrentWebViewInstance(sourceWebView)) {
            Log.w(
                LOG_TAG,
                "Ignore onPageFinished from stale WebView instance. url=$url"
            )
            return
        }
        logActiveWebReloadTrace(phase = "page_finished", url = url)
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
        updateRefreshLayoutEnabled()
        CookieManager.getInstance().flush()
        setBrowserZoomPercent(hostConfigStore.browserZoomPercent)
        setBrowserPageZoomPercent(hostConfigStore.browserPageZoomPercent)
        bridgeInstaller.installAfterPageFinished(buildBridgeTarget(sourceWebView))
        installSystemBarThemeSyncScript(sourceWebView)
        installWebPerformanceDiagnosticScript(sourceWebView)
        if (!url.isNullOrBlank()) {
            homeViewModel.loadedUrl = url
            homeViewModel.pendingLocalRetryAttempts = 0
        }
        clearActiveWebReloadTrace()
    }

    private fun installSystemBarThemeSyncScript(sourceWebView: WebView) {
        // 酒馆内部主题切换多半是前端改 html/body 的 class/style，不一定整页 reload；
        // 这里给当前文档挂一个 observer，首次进入和后续主题切换都把根背景色同步给 Android 系统栏。
        val androidHostBridgeNameJson = org.json.JSONObject.quote(bridgeInstaller.androidHostBridgeName)
        sourceWebView.evaluateJavascript(
            """
                (function() {
                    const bridge = window[$androidHostBridgeNameJson];
                    if (!bridge || typeof bridge.setSystemBarsBackgroundColor !== 'function') {
                        return 'bridge_missing';
                    }

                    function normalizeHexColor(input) {
                        const value = String(input || '').trim().toLowerCase();
                        if (!value || value === 'transparent') {
                            return '';
                        }

                        const hexMatch = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i);
                        if (hexMatch) {
                            const hex = hexMatch[1];
                            if (hex.length === 3) {
                                return '#' + hex.split('').map(function(char) { return char + char; }).join('');
                            }
                            if (hex.length === 8) {
                                const alpha = parseInt(hex.slice(6, 8), 16);
                                if (alpha <= 3) {
                                    return '';
                                }
                                return '#' + hex.slice(0, 6);
                            }
                            return '#' + hex.slice(0, 6);
                        }

                        const rgbaMatch = value.match(/^rgba?\(\s*(\d{1,3})(?:\s*,\s*|\s+)(\d{1,3})(?:\s*,\s*|\s+)(\d{1,3})(?:\s*(?:\/|,)\s*([0-9.]+%?))?\s*\)$/);
                        if (rgbaMatch) {
                            const alphaText = rgbaMatch[4];
                            const alpha = alphaText && alphaText.endsWith('%') ? Number(alphaText.slice(0, -1)) / 100 : Number(alphaText == null ? 1 : alphaText);
                            if (!Number.isFinite(alpha) || alpha <= 0.01) {
                                return '';
                            }
                            const rgb = rgbaMatch.slice(1, 4).map(function(channel) {
                                const clamped = Math.max(0, Math.min(255, Number(channel)));
                                return clamped.toString(16).padStart(2, '0');
                            });
                            return '#' + rgb.join('');
                        }

                        return '';
                    }

                    function firstSolidBackgroundHex() {
                        const themeMeta = document.querySelector('meta[name="theme-color"]');
                        const metaColor = normalizeHexColor(themeMeta && themeMeta.content);
                        if (metaColor) {
                            return metaColor;
                        }

                        const candidates = [document.getElementById('bg1'), document.body, document.documentElement];
                        for (const node of candidates) {
                            if (!node) {
                                continue;
                            }
                            const color = normalizeHexColor(window.getComputedStyle(node).backgroundColor);
                            if (color) {
                                return color;
                            }
                        }
                        return '';
                    }

                    function notifyBridge() {
                        if (document.documentElement && document.documentElement.dataset.sillydroidTheme === 'glass') {
                            return;
                        }

                        const nextColor = firstSolidBackgroundHex();
                        if (!nextColor || nextColor === window.__sillyDroidLastSystemBarColor) {
                            return;
                        }
                        window.__sillyDroidLastSystemBarColor = nextColor;
                        bridge.setSystemBarsBackgroundColor(nextColor);
                    }

                    if (window.__sillyDroidSystemBarThemeSyncInstalled) {
                        notifyBridge();
                        return 'already_installed';
                    }

                    let frameScheduled = false;
                    function scheduleNotify() {
                        if (frameScheduled) {
                            return;
                        }
                        frameScheduled = true;
                        window.requestAnimationFrame(function() {
                            frameScheduled = false;
                            notifyBridge();
                        });
                    }

                    const observer = new MutationObserver(scheduleNotify);
                    if (document.documentElement) {
                        observer.observe(document.documentElement, {
                            attributes: true,
                            childList: true,
                            subtree: true,
                            attributeFilter: ['class', 'style', 'data-theme', 'theme', 'content']
                        });
                    }

                    window.addEventListener('load', scheduleNotify);
                    window.addEventListener('hashchange', scheduleNotify);
                    window.addEventListener('popstate', scheduleNotify);
                    document.addEventListener('readystatechange', scheduleNotify);

                    window.__sillyDroidSystemBarThemeSyncInstalled = true;
                    scheduleNotify();
                    return 'installed';
                })();
            """.trimIndent(),
            null
        )
    }

    private fun installWebPerformanceDiagnosticScript(sourceWebView: WebView) {
        // 只在页面 load 后上报一次聚合性能摘要，用来同机对比 Chrome 与 App WebView 的真实瓶颈。
        val androidHostBridgeNameJson = org.json.JSONObject.quote(bridgeInstaller.androidHostBridgeName)
        sourceWebView.evaluateJavascript(
            """
                (function() {
                    if (window.__sillyDroidWebPerformanceDiagnosticInstalled) {
                        return 'already_installed';
                    }
                    window.__sillyDroidWebPerformanceDiagnosticInstalled = true;

                    const bridge = window[$androidHostBridgeNameJson];
                    if (!bridge || typeof bridge.recordWebPerformanceDiagnostic !== 'function') {
                        return 'bridge_missing';
                    }

                    const longTasks = [];
                    let observer = null;
                    if ('PerformanceObserver' in window) {
                        try {
                            observer = new PerformanceObserver(function(list) {
                                for (const entry of list.getEntries()) {
                                    longTasks.push(Math.round(entry.duration || 0));
                                }
                            });
                            observer.observe({ type: 'longtask', buffered: true });
                        } catch (error) {
                            observer = null;
                        }
                    }

                    function round(value) {
                        return Number.isFinite(value) ? Math.max(0, Math.round(value)) : 0;
                    }

                    function hostKind(urlText) {
                        try {
                            const url = new URL(urlText, location.href);
                            if (url.hostname === '127.0.0.1' || url.hostname === 'localhost' || url.hostname === '::1') {
                                return 'local';
                            }
                            return url.protocol === 'data:' || url.protocol === 'blob:' ? url.protocol.slice(0, -1) : 'remote';
                        } catch (error) {
                            return 'unknown';
                        }
                    }

                    function isIndexedDbAvailable() {
                        try {
                            return typeof indexedDB !== 'undefined' && !!indexedDB;
                        } catch (error) {
                            return false;
                        }
                    }

                    function isStorageAvailable(name) {
                        try {
                            const storage = window[name];
                            if (!storage) {
                                return false;
                            }
                            const key = '__sillydroid_storage_probe__';
                            storage.setItem(key, '1');
                            storage.removeItem(key);
                            return true;
                        } catch (error) {
                            return false;
                        }
                    }

                    function isCacheStorageAvailable() {
                        try {
                            return typeof caches !== 'undefined' && !!caches;
                        } catch (error) {
                            return false;
                        }
                    }

                    function isWebGlAvailable() {
                        try {
                            const canvas = document.createElement('canvas');
                            return !!(canvas.getContext('webgl2') || canvas.getContext('webgl') || canvas.getContext('experimental-webgl'));
                        } catch (error) {
                            return false;
                        }
                    }

                    function webGlContextInfo() {
                        const emptyInfo = {
                            available: false,
                            contextName: '',
                            version: '',
                            shadingLanguageVersion: '',
                            vendor: '',
                            renderer: '',
                            unmaskedVendor: '',
                            unmaskedRenderer: ''
                        };
                        try {
                            const canvas = document.createElement('canvas');
                            const contextNames = ['webgl2', 'webgl', 'experimental-webgl'];
                            let gl = null;
                            let contextName = '';
                            for (let index = 0; index < contextNames.length; index += 1) {
                                contextName = contextNames[index];
                                gl = canvas.getContext(contextName);
                                if (gl) {
                                    break;
                                }
                            }
                            if (!gl) {
                                return emptyInfo;
                            }
                            const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
                            return {
                                available: true,
                                contextName,
                                version: compactText(gl.getParameter(gl.VERSION), 120),
                                shadingLanguageVersion: compactText(gl.getParameter(gl.SHADING_LANGUAGE_VERSION), 120),
                                vendor: compactText(gl.getParameter(gl.VENDOR), 120),
                                renderer: compactText(gl.getParameter(gl.RENDERER), 160),
                                unmaskedVendor: compactText(debugInfo ? gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL) : '', 120),
                                unmaskedRenderer: compactText(debugInfo ? gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) : '', 180)
                            };
                        } catch (error) {
                            return emptyInfo;
                        }
                    }

                    function isWebGpuAvailable() {
                        try {
                            return typeof navigator !== 'undefined' && !!navigator.gpu;
                        } catch (error) {
                            return false;
                        }
                    }

                    function isWorkerAvailable() {
                        try {
                            return typeof Worker !== 'undefined';
                        } catch (error) {
                            return false;
                        }
                    }

                    function isSharedWorkerAvailable() {
                        try {
                            return typeof SharedWorker !== 'undefined';
                        } catch (error) {
                            return false;
                        }
                    }

                    function isServiceWorkerAvailable() {
                        try {
                            return typeof navigator !== 'undefined' && 'serviceWorker' in navigator;
                        } catch (error) {
                            return false;
                        }
                    }

                    function isWasmAvailable() {
                        try {
                            return typeof WebAssembly !== 'undefined' && typeof WebAssembly.instantiate === 'function';
                        } catch (error) {
                            return false;
                        }
                    }

                    function isOffscreenCanvasAvailable() {
                        try {
                            return typeof OffscreenCanvas !== 'undefined';
                        } catch (error) {
                            return false;
                        }
                    }

                    function compactText(value, limit) {
                        const text = String(value || '').replace(/\s+/g, ' ').trim();
                        if (!text) {
                            return '';
                        }
                        return text.length <= limit ? text : text.slice(0, limit) + '...';
                    }

                    function viewportMetaContent() {
                        const viewportMeta = document.querySelector('meta[name="viewport"]');
                        return viewportMeta ? viewportMeta.getAttribute('content') || '' : '';
                    }

                    function computedFontSizePx(node) {
                        try {
                            const fontSize = node ? window.getComputedStyle(node).fontSize : '';
                            return round(Number.parseFloat(fontSize) || 0);
                        } catch (error) {
                            return 0;
                        }
                    }

                    function computedCssZoom(node) {
                        try {
                            const zoom = node ? window.getComputedStyle(node).zoom : '';
                            return Number.parseFloat(zoom) || 0;
                        } catch (error) {
                            return 0;
                        }
                    }

                    function computedLineHeightPx(node) {
                        try {
                            const style = node ? window.getComputedStyle(node) : null;
                            if (!style) {
                                return 0;
                            }
                            const lineHeight = Number.parseFloat(style.lineHeight || '');
                            if (Number.isFinite(lineHeight)) {
                                return round(lineHeight);
                            }
                            return computedFontSizePx(node);
                        } catch (error) {
                            return 0;
                        }
                    }

                    function computedTextSizeAdjust(node) {
                        try {
                            const style = node ? window.getComputedStyle(node) : null;
                            if (!style) {
                                return '';
                            }
                            return compactText(
                                style.getPropertyValue('-webkit-text-size-adjust') ||
                                    style.getPropertyValue('text-size-adjust') ||
                                    style.webkitTextSizeAdjust ||
                                    '',
                                40
                            );
                        } catch (error) {
                            return '';
                        }
                    }

                    function computedFontFamily(node) {
                        try {
                            return compactText(node ? window.getComputedStyle(node).fontFamily : '', 80);
                        } catch (error) {
                            return '';
                        }
                    }

                    function elementMetric(selector) {
                        try {
                            const node = document.querySelector(selector);
                            if (!node) {
                                return null;
                            }
                            const rect = node.getBoundingClientRect();
                            const style = window.getComputedStyle(node);
                            return {
                                width: round(rect.width),
                                height: round(rect.height),
                                top: round(rect.top),
                                bottom: round(rect.bottom),
                                display: compactText(style.display, 24),
                                fontSizePx: round(Number.parseFloat(style.fontSize || '') || 0),
                                lineHeightPx: round(Number.parseFloat(style.lineHeight || '') || 0)
                            };
                        } catch (error) {
                            return null;
                        }
                    }

                    function mediaMatches(query) {
                        try {
                            return typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
                        } catch (error) {
                            return false;
                        }
                    }

                    function filePickerCapabilitySummary() {
                        return {
                            showOpenFilePickerAvailable: typeof window.showOpenFilePicker === 'function',
                            showSaveFilePickerAvailable: typeof window.showSaveFilePicker === 'function',
                            showDirectoryPickerAvailable: typeof window.showDirectoryPicker === 'function'
                        };
                    }

                    function clipboardCapabilitySummary() {
                        const clipboard = typeof navigator !== 'undefined' ? navigator.clipboard : null;
                        return {
                            clipboardApiAvailable: !!clipboard,
                            clipboardReadAvailable: !!clipboard && typeof clipboard.read === 'function',
                            clipboardReadTextAvailable: !!clipboard && typeof clipboard.readText === 'function',
                            clipboardWriteAvailable: !!clipboard && typeof clipboard.write === 'function',
                            clipboardWriteTextAvailable: !!clipboard && typeof clipboard.writeText === 'function'
                        };
                    }

                    function inputCapabilitySummary() {
                        return {
                            pointerEventAvailable: typeof PointerEvent !== 'undefined',
                            touchEventAvailable: typeof TouchEvent !== 'undefined',
                            maxTouchPoints: typeof navigator !== 'undefined' ? navigator.maxTouchPoints || 0 : 0
                        };
                    }

                    function compactCapabilityValue(value) {
                        if (value == null) {
                            return '-';
                        }
                        if (typeof value === 'string') {
                            return value.replace(/\s+/g, '_').slice(0, 80) || '-';
                        }
                        if (typeof value === 'object') {
                            return JSON.stringify(value).replace(/\s+/g, '_').slice(0, 120);
                        }
                        return String(value);
                    }

                    function buildCompactCapabilitySummary(summary) {
                        const topBar = summary.layoutMetrics && summary.layoutMetrics.topBar || {};
                        const sendForm = summary.layoutMetrics && summary.layoutMetrics.sendForm || {};
                        // 短摘要保留缩放、GPU、存储和输入能力关键字段，避免完整 JSON 被日志长度裁剪后丢失定位点。
                        return [
                            'event=page_capability_summary',
                            'browserEngine=SYSTEM_WEBVIEW',
                            'viewportInnerWidth=' + compactCapabilityValue(summary.viewportInnerWidth),
                            'visualViewportWidth=' + compactCapabilityValue(summary.visualViewportWidth),
                            'visualViewportScale=' + compactCapabilityValue(summary.visualViewportScale),
                            'devicePixelRatio=' + compactCapabilityValue(summary.devicePixelRatio),
                            'screenWidth=' + compactCapabilityValue(summary.screenWidth),
                            'rootFontSizePx=' + compactCapabilityValue(summary.rootFontSizePx),
                            'bodyFontSizePx=' + compactCapabilityValue(summary.bodyFontSizePx),
                            'rootLineHeightPx=' + compactCapabilityValue(summary.rootLineHeightPx),
                            'rootCssZoom=' + compactCapabilityValue(summary.rootCssZoom),
                            'bodyCssZoom=' + compactCapabilityValue(summary.bodyCssZoom),
                            'rootTextSizeAdjust=' + compactCapabilityValue(summary.rootTextSizeAdjust),
                            'viewportMeta=' + compactCapabilityValue(summary.viewportMetaContent),
                            'mediaWidthMax360=' + compactCapabilityValue(summary.mediaWidthMax360),
                            'mediaWidthMin390=' + compactCapabilityValue(summary.mediaWidthMin390),
                            'mediaWidthMin600=' + compactCapabilityValue(summary.mediaWidthMin600),
                            'topBarHeight=' + compactCapabilityValue(topBar.height),
                            'sendFormHeight=' + compactCapabilityValue(sendForm.height),
                            'indexedDb=' + compactCapabilityValue(summary.indexedDbAvailable),
                            'localStorage=' + compactCapabilityValue(summary.localStorageAvailable),
                            'cacheStorage=' + compactCapabilityValue(summary.cacheStorageAvailable),
                            'webGl=' + compactCapabilityValue(summary.webGlAvailable),
                            'webGlContext=' + compactCapabilityValue(summary.webGlContextName),
                            'webGlRenderer=' + compactCapabilityValue(summary.webGlUnmaskedRenderer || summary.webGlRenderer),
                            'webGpu=' + compactCapabilityValue(summary.webGpuAvailable),
                            'worker=' + compactCapabilityValue(summary.webWorkerAvailable),
                            'wasm=' + compactCapabilityValue(summary.wasmAvailable),
                            'offscreenCanvas=' + compactCapabilityValue(summary.offscreenCanvasAvailable),
                            'secureContext=' + compactCapabilityValue(summary.secureContext),
                            'clipboardWriteText=' + compactCapabilityValue(summary.clipboardCapabilities && summary.clipboardCapabilities.clipboardWriteTextAvailable),
                            'showOpenFilePicker=' + compactCapabilityValue(summary.filePickerCapabilities && summary.filePickerCapabilities.showOpenFilePickerAvailable),
                            'pointerEvent=' + compactCapabilityValue(summary.inputCapabilities && summary.inputCapabilities.pointerEventAvailable),
                            'maxTouchPoints=' + compactCapabilityValue(summary.inputCapabilities && summary.inputCapabilities.maxTouchPoints)
                        ].join(' ');
                    }

                    function buildSummary() {
                        const nav = performance.getEntriesByType('navigation')[0];
                        const resources = performance.getEntriesByType('resource');
                        const webGlInfo = webGlContextInfo();
                        const filePickerCapabilities = filePickerCapabilitySummary();
                        const clipboardCapabilities = clipboardCapabilitySummary();
                        const inputCapabilities = inputCapabilitySummary();
                        const slowResources = resources
                            .filter(function(entry) { return (entry.duration || 0) >= 250; })
                            .sort(function(left, right) { return (right.duration || 0) - (left.duration || 0); })
                            .slice(0, 5)
                            .map(function(entry) {
                                return {
                                    kind: entry.initiatorType || 'unknown',
                                    host: hostKind(entry.name),
                                    durationMs: round(entry.duration),
                                    transferSize: round(entry.transferSize || 0),
                                    encodedBodySize: round(entry.encodedBodySize || 0)
                                };
                            });

                        const sortedLongTasks = longTasks.slice().sort(function(left, right) { return right - left; });
                        return {
                            event: 'page_load_summary',
                            browserEngine: 'SYSTEM_WEBVIEW',
                            hrefHost: hostKind(location.href),
                            navType: nav ? nav.type : 'unknown',
                            domContentLoadedMs: nav ? round(nav.domContentLoadedEventEnd - nav.startTime) : 0,
                            loadEventMs: nav ? round(nav.loadEventEnd - nav.startTime) : 0,
                            responseEndMs: nav ? round(nav.responseEnd - nav.startTime) : 0,
                            transferSize: nav ? round(nav.transferSize || 0) : 0,
                            encodedBodySize: nav ? round(nav.encodedBodySize || 0) : 0,
                            resourceCount: resources.length,
                            slowResourceCount: resources.filter(function(entry) { return (entry.duration || 0) >= 250; }).length,
                            slowResources: slowResources,
                            longTaskCount: longTasks.length,
                            maxLongTaskMs: sortedLongTasks[0] || 0,
                            topLongTasksMs: sortedLongTasks.slice(0, 5),
                            viewportInnerWidth: round(window.innerWidth || 0),
                            viewportInnerHeight: round(window.innerHeight || 0),
                            documentClientWidth: round(document.documentElement ? document.documentElement.clientWidth : 0),
                            documentClientHeight: round(document.documentElement ? document.documentElement.clientHeight : 0),
                            visualViewportWidth: round(window.visualViewport ? window.visualViewport.width : 0),
                            visualViewportHeight: round(window.visualViewport ? window.visualViewport.height : 0),
                            visualViewportScale: window.visualViewport && Number.isFinite(window.visualViewport.scale) ? window.visualViewport.scale : 0,
                            devicePixelRatio: window.devicePixelRatio || 0,
                            outerWidth: round(window.outerWidth || 0),
                            outerHeight: round(window.outerHeight || 0),
                            screenWidth: round(window.screen ? window.screen.width : 0),
                            screenHeight: round(window.screen ? window.screen.height : 0),
                            screenAvailWidth: round(window.screen ? window.screen.availWidth : 0),
                            screenAvailHeight: round(window.screen ? window.screen.availHeight : 0),
                            userAgentFamily: navigator.userAgent && navigator.userAgent.includes('Firefox') ? 'firefox' : 'other',
                            userAgent: compactText(navigator.userAgent, 180),
                            viewportMetaContent: compactText(viewportMetaContent(), 180),
                            rootFontSizePx: computedFontSizePx(document.documentElement),
                            bodyFontSizePx: computedFontSizePx(document.body),
                            rootLineHeightPx: computedLineHeightPx(document.documentElement),
                            bodyLineHeightPx: computedLineHeightPx(document.body),
                            rootTextSizeAdjust: computedTextSizeAdjust(document.documentElement),
                            bodyTextSizeAdjust: computedTextSizeAdjust(document.body),
                            rootFontFamily: computedFontFamily(document.documentElement),
                            bodyFontFamily: computedFontFamily(document.body),
                            rootCssZoom: computedCssZoom(document.documentElement),
                            bodyCssZoom: computedCssZoom(document.body),
                            mediaWidthMax360: mediaMatches('(max-width: 360px)'),
                            mediaWidthMin390: mediaMatches('(min-width: 390px)'),
                            mediaWidthMin600: mediaMatches('(min-width: 600px)'),
                            mediaPointerCoarse: mediaMatches('(pointer: coarse)'),
                            mediaPointerFine: mediaMatches('(pointer: fine)'),
                            mediaHoverNone: mediaMatches('(hover: none)'),
                            mediaHoverHover: mediaMatches('(hover: hover)'),
                            mediaAnyPointerFine: mediaMatches('(any-pointer: fine)'),
                            layoutMetrics: {
                                topBar: elementMetric('#top-bar'),
                                chat: elementMetric('#chat'),
                                sendForm: elementMetric('#send_form'),
                                leftNav: elementMetric('#left-nav-panel'),
                                rightNav: elementMetric('#right-nav-panel')
                            },
                            hardwareConcurrency: navigator.hardwareConcurrency || 0,
                            deviceMemoryGb: navigator.deviceMemory || 0,
                            indexedDbAvailable: isIndexedDbAvailable(),
                            localStorageAvailable: isStorageAvailable('localStorage'),
                            sessionStorageAvailable: isStorageAvailable('sessionStorage'),
                            cacheStorageAvailable: isCacheStorageAvailable(),
                            webGlAvailable: webGlInfo.available || isWebGlAvailable(),
                            webGlContextName: webGlInfo.contextName,
                            webGlVersion: webGlInfo.version,
                            webGlShadingLanguageVersion: webGlInfo.shadingLanguageVersion,
                            webGlVendor: webGlInfo.vendor,
                            webGlRenderer: webGlInfo.renderer,
                            webGlUnmaskedVendor: webGlInfo.unmaskedVendor,
                            webGlUnmaskedRenderer: webGlInfo.unmaskedRenderer,
                            webGpuAvailable: isWebGpuAvailable(),
                            webWorkerAvailable: isWorkerAvailable(),
                            sharedWorkerAvailable: isSharedWorkerAvailable(),
                            serviceWorkerAvailable: isServiceWorkerAvailable(),
                            wasmAvailable: isWasmAvailable(),
                            offscreenCanvasAvailable: isOffscreenCanvasAvailable(),
                            secureContext: window.isSecureContext === true,
                            filePickerCapabilities,
                            clipboardCapabilities,
                            inputCapabilities
                        };
                    }

                    function sendSummary() {
                        if (window.__sillyDroidWebPerformanceDiagnosticSent) {
                            return;
                        }
                        window.__sillyDroidWebPerformanceDiagnosticSent = true;
                        try {
                            if (observer) {
                                observer.disconnect();
                            }
                            const summary = buildSummary();
                            bridge.recordWebPerformanceDiagnostic(buildCompactCapabilitySummary(summary));
                            bridge.recordWebPerformanceDiagnostic(JSON.stringify(summary));
                        } catch (error) {
                            bridge.recordWebPerformanceDiagnostic('event=page_load_summary_failed reason=' + String(error && error.message || error));
                        }
                    }

                    if (document.readyState === 'complete') {
                        setTimeout(sendSummary, 800);
                    } else {
                        window.addEventListener('load', function() {
                            setTimeout(sendSummary, 800);
                        }, { once: true });
                    }

                    return 'installed';
                })();
            """.trimIndent(),
            null
        )
    }

    private fun handleWebViewRendererGone(info: WebViewRendererGoneInfo) {
        val didCrash = info.didCrash
        if (didCrash) {
            // 只有真实 renderer crash 才提示用户切换浏览器引擎；系统回收/用户清理类退出不打扰。
            onWebViewRendererCrash()
        }
        val recoveryUrl = currentKnownWebViewUrl()
        val rendererGoneDetail = info.toDiagnosticText()
        val rendererFailureSnapshot = currentRendererFailureDiagnosticState(
            didCrash = didCrash,
            rendererGoneDetail = rendererGoneDetail
        )
        enableDebugDiagnosticsAfterRendererGone(
            didCrash = didCrash,
            recoveryUrl = recoveryUrl,
            rendererGoneDetail = rendererGoneDetail
        )
        uploadRendererGoneLogBundle(info)
        scheduleRendererExitInfoRefresh()
        if (recoveryUrl.isNotBlank()) {
            homeViewModel.loadedUrl = recoveryUrl
        }
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
        updateRefreshLayoutEnabled()
        clearActiveWebReloadTrace()
        if (rendererRecoveryActivityRecreateScheduled) {
            Log.w(
                LOG_TAG,
                "WebView renderer gone (didCrash=$didCrash) while Activity recreation is already scheduled."
            )
            recordCriticalHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=renderer_gone_duplicate")
                    append(" didCrash=$didCrash")
                    append(" exitKind=${if (didCrash) "crash" else "non_crash_exit"}")
                    append(" $rendererGoneDetail")
                    append(" action=skip_duplicate_recreate")
                    append(" recoveryUrl=${normalizeDiagnosticValue(recoveryUrl)}")
                    append(' ')
                    append(currentWebViewDiagnosticState())
                    append(' ')
                    append(rendererFailureSnapshot)
                }
            )
            return
        }
        rendererRecoveryActivityRecreateScheduled = true
        Log.e(
            LOG_TAG,
            "WebView renderer gone (didCrash=$didCrash). Recreating Activity so Android rebuilds " +
                "the window and WebView surface instead of only swapping the WebView object."
        )
        recordCriticalHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=renderer_gone")
                append(" didCrash=$didCrash")
                append(" exitKind=${if (didCrash) "crash" else "non_crash_exit"}")
                append(" $rendererGoneDetail")
                append(" action=schedule_activity_recreate")
                append(" recoveryUrl=${normalizeDiagnosticValue(recoveryUrl)}")
                append(' ')
                append(currentWebViewDiagnosticState())
                append(' ')
                append(rendererFailureSnapshot)
            }
        )
        if (!activity.isFinishing && !activity.isDestroyed) {
            webViewRefreshLayout.post {
                if (activity.isFinishing || activity.isDestroyed) {
                    recordCriticalHostDiagnostic(
                        category = "webview",
                        body = "event=renderer_gone_recreate_aborted reason=activity_not_alive ${currentWebViewDiagnosticState()} ${rendererFailureSnapshot}"
                    )
                    return@post
                }
                activity.recreate()
            }
        } else {
            recordCriticalHostDiagnostic(
                category = "webview",
                body = "event=renderer_gone_recreate_skipped reason=activity_not_alive ${currentWebViewDiagnosticState()} ${rendererFailureSnapshot}"
            )
        }
    }

    private fun enableDebugDiagnosticsAfterRendererGone(
        didCrash: Boolean,
        recoveryUrl: String,
        rendererGoneDetail: String
    ) {
        if (!hostConfigStore.debugDiagnosticsEnabled) {
            // renderer gone 往往难以在开发机复现；首次命中后自动开启详细诊断，
            // 让用户下一次导出的日志带上 page/bridge/恢复链路细节，而不依赖手动进设置。
            hostConfigStore.debugDiagnosticsEnabled = true
        }
        recordCriticalHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=debug_diagnostics_auto_enabled")
                append(" trigger=renderer_gone")
                append(" didCrash=$didCrash")
                append(" $rendererGoneDetail")
                append(" recoveryUrl=${normalizeDiagnosticValue(recoveryUrl)}")
                append(" enabled=${hostConfigStore.debugDiagnosticsEnabled}")
            }
        )
    }

    override fun setBrowserZoomPercent(percent: Int): Boolean {
        val sanitizedPercent = BrowserZoomOptions.sanitize(percent)
        if (webView.settings.textZoom != sanitizedPercent) {
            webView.settings.textZoom = sanitizedPercent
        }
        recordCriticalHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=browser_text_zoom_applied")
                append(" engine=${browserEngine.name}")
                append(" percent=$sanitizedPercent")
                append(" webViewTextZoom=${webView.settings.textZoom}")
            }
        )
        return webView.settings.textZoom == sanitizedPercent
    }

    override fun setBrowserPageZoomPercent(percent: Int): Boolean {
        val sanitizedPercent = BrowserZoomOptions.sanitizeViewportDensity(percent)
        applyWebViewViewportDensityPercent(sanitizedPercent, reason = "set_browser_viewport_density")
        recordCriticalHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=browser_viewport_density_applied")
                append(" engine=${browserEngine.name}")
                append(" percent=$sanitizedPercent")
                append(" mode=html_viewport")
            }
        )
        return true
    }

    private fun applyWebViewViewportDensityPercent(percent: Int, reason: String) {
        webView.evaluateJavascript(
            buildApplyWebViewViewportDensityScript(
                percent = BrowserZoomOptions.sanitizeViewportDensity(percent),
                baseViewportWidthCssPx = resolveWebViewBaseViewportWidthCssPx(),
                reason = reason
            ),
            null
        )
    }

    private fun resolveWebViewBaseViewportWidthCssPx(): Int {
        val density = activity.resources.displayMetrics.density.takeIf { value ->
            value.isFinite() && value > 0f
        } ?: 1f
        val widthPx = webView.width.takeIf { value -> value > 0 } ?: activity.resources.displayMetrics.widthPixels
        return (widthPx / density).toInt().coerceAtLeast(240)
    }

    private fun installWebDocumentStartScriptController() {
        webDocumentStartScriptController?.close()
        webDocumentStartScriptController = WebDocumentStartScriptController(
            webView = webView,
            systemNotificationBridgeName = bridgeInstaller.systemNotificationBridgeName,
            androidHostBridgeName = bridgeInstaller.androidHostBridgeName,
            allowedOrigin = { runtimeConfigRepository.localServiceUrl() }
        ).also { controller ->
            val documentStartInstalled = controller.install()
            if (!documentStartInstalled) {
                // 部分华为 WebView 不支持 DOCUMENT_START_SCRIPT；启动样式/通知 shim 属于增强能力，不能因此阻断主界面启动。
                recordHostDiagnostic(
                    category = "webview",
                    body = "event=document_start_script_skipped reason=unsupported_feature ${currentWebViewDiagnosticState()}"
                )
                // 静默降级用户无感知；这里请求一次性提示，告知增强能力不可用并给出可操作路径。
                onDocumentStartScriptUnsupported()
            }
        }
    }

    private fun clearPersistedWebSiteState(clearMask: Int) {
        val normalizedClearMask = BrowserDataClearOptions.normalizeOrDefault(clearMask)
        webDocumentStartScriptController?.refreshScript()
        if (BrowserDataClearOptions.contains(normalizedClearMask, BrowserDataClearTarget.COOKIES)) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        if (BrowserDataClearOptions.contains(normalizedClearMask, BrowserDataClearTarget.SITE_STORAGE)) {
            WebStorage.getInstance().deleteAllData()
        }
        if (BrowserDataClearOptions.contains(normalizedClearMask, BrowserDataClearTarget.HISTORY_AND_FORM_DATA)) {
            webView.clearHistory()
            webView.clearFormData()
            webView.clearSslPreferences()
            webView.clearMatches()
        }
        if (BrowserDataClearOptions.contains(normalizedClearMask, BrowserDataClearTarget.RESOURCE_CACHE)) {
            webView.clearCache(true)
        }
    }

    private fun clearCurrentPageSessionState(clearMask: Int) {
        if (!BrowserDataClearOptions.contains(clearMask, BrowserDataClearTarget.SITE_STORAGE)) {
            return
        }

        // 只有用户选择网页本地存储时才清当前文档存储；默认 JS/CSS 缓存清理不能误删酒馆会话状态。
        webView.evaluateJavascript(
            """
                (function() {
                    try { sessionStorage.clear(); } catch (_) {}
                    try { localStorage.clear(); } catch (_) {}
                    return 'cleared';
                })();
            """.trimIndent(),
            null
        )
    }

    private fun scheduleLocalWebViewRetry(errorInfo: WebViewLocalLoadErrorInfo) {
        recordMainFrameLocalLoadError(errorInfo)
        val failingUrl = errorInfo.failingUrl
        if (homeViewModel.pendingLocalRetryAttempts >= 5) {
            // 估计是服务侧長期起不来；交给 startup overlay 接手，不再闪烁。
            recordHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=main_frame_local_load_error")
                    append(" action=retry_skipped")
                    append(" reason=attempt_limit")
                    append(" retryAttempt=${homeViewModel.pendingLocalRetryAttempts}")
                    append(" failingUrl=${normalizeDiagnosticValue(failingUrl)}")
                    append(' ')
                    append(currentWebViewDiagnosticState())
                }
            )
            return
        }
        if (!processManager.currentSnapshot().isReady) {
            recordHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=main_frame_local_load_error")
                    append(" action=retry_skipped")
                    append(" reason=server_not_ready")
                    append(" failingUrl=${normalizeDiagnosticValue(failingUrl)}")
                    append(' ')
                    append(currentWebViewDiagnosticState())
                }
            )
            return
        }
        homeViewModel.pendingLocalRetryAttempts += 1
        val retryAttempt = homeViewModel.pendingLocalRetryAttempts
        val delayMillis = (500L * retryAttempt).coerceAtMost(3_000L)
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=main_frame_local_load_error")
                append(" action=schedule_retry")
                append(" retryAttempt=$retryAttempt")
                append(" delayMs=$delayMillis")
                append(" failingUrl=${normalizeDiagnosticValue(failingUrl)}")
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
        webView.postDelayed(
            {
                if (activity.isFinishing || activity.isDestroyed) {
                    recordHostDiagnostic(
                        category = "webview",
                        body = "event=main_frame_local_load_error action=retry_aborted reason=activity_not_alive retryAttempt=$retryAttempt failingUrl=${normalizeDiagnosticValue(failingUrl)} ${currentWebViewDiagnosticState()}"
                    )
                    return@postDelayed
                }
                if (!processManager.currentSnapshot().isReady) {
                    recordHostDiagnostic(
                        category = "webview",
                        body = "event=main_frame_local_load_error action=retry_aborted reason=server_not_ready retryAttempt=$retryAttempt failingUrl=${normalizeDiagnosticValue(failingUrl)} ${currentWebViewDiagnosticState()}"
                    )
                    return@postDelayed
                }
                if (failingUrl == homeViewModel.loadedUrl || failingUrl.startsWith(homeViewModel.loadedUrl.trimEnd('/'))) {
                    recordHostDiagnostic(
                        category = "webview",
                        body = "event=main_frame_local_load_error action=retry_execute retryMode=load_url retryAttempt=$retryAttempt failingUrl=${normalizeDiagnosticValue(failingUrl)} ${currentWebViewDiagnosticState()}"
                    )
                    webView.loadUrl(failingUrl)
                } else {
                    recordHostDiagnostic(
                        category = "webview",
                        body = "event=main_frame_local_load_error action=retry_execute retryMode=reload retryAttempt=$retryAttempt failingUrl=${normalizeDiagnosticValue(failingUrl)} ${currentWebViewDiagnosticState()}"
                    )
                    webView.reload()
                }
            },
            delayMillis
        )
    }

    private fun recordMainFrameLocalLoadError(errorInfo: WebViewLocalLoadErrorInfo) {
        val snapshot = processManager.currentSnapshot()
        // 这条日志专门定位“后台 ready 但 WebView 打不开 127.0.0.1”的现场；
        // 只在 WebViewClient.onReceivedError 事件触发时记录，不做 URL 轮询或自动改写地址。
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=main_frame_local_url_unreachable")
                append(" method=${errorInfo.method}")
                append(" errorCode=${errorInfo.errorCode ?: "-"}")
                append(" description=${normalizeDiagnosticValue(errorInfo.description)}")
                append(" failingUrl=${normalizeDiagnosticValue(errorInfo.failingUrl)}")
                append(" serverReady=${snapshot.isReady}")
                append(" serverLifecycle=${snapshot.lifecycle}")
                append(" snapshotLocalUrl=${normalizeDiagnosticValue(snapshot.localUrl)}")
                append(' ')
                append(currentNetworkDiagnosticState())
                append(' ')
                append(currentWebViewDiagnosticState())
                append(' ')
                append(currentHostMemoryDiagnosticState())
            }
        )
    }

    private fun currentNetworkDiagnosticState(): String {
        val connectivityManager = activity.getSystemService(ConnectivityManager::class.java)
            ?: return "vpnActive=unknown networkTransports=unknown networkValidated=unknown"
        val activeNetwork = connectivityManager.activeNetwork
            ?: return "vpnActive=false networkTransports=none networkValidated=false"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return "vpnActive=unknown networkTransports=unknown networkValidated=unknown"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }.ifEmpty { listOf("other") }
        return buildString {
            append("vpnActive=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
            append(" networkTransports=${transports.joinToString(separator = ",")}")
            append(" networkValidated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
        }
    }

    private fun installBrowserHostBridge(targetWebView: WebView) {
        bridgeInstaller.install(buildBridgeTarget(targetWebView))
    }

    private fun buildBridgeTarget(targetWebView: WebView): BrowserHostBridgeTarget {
        return BrowserHostBridgeTarget(
            browserEngine = browserEngine,
            surface = targetWebView,
            allowedOrigin = runtimeConfigRepository.localServiceUrl()
        )
    }

    private fun isCurrentWebViewPageFor(baseUrl: String): Boolean {
        // 这里必须只看“当前这一个真实 WebView 实例”已经加载出的 URL。
        // 如果新建出来的 WebView 还停在 about:blank，不能把任何宿主记录冒充成当前已渲染页面，
        // 否则会误判成“页面已在当前站点”并跳过 loadUrl，最终把整页永久留在空白文档。
        return hasLoadedCurrentWebViewPageForBaseUrl(
            currentWebViewUrl = webView.url.orEmpty(),
            baseUrl = baseUrl
        )
    }

    private fun currentKnownWebViewUrl(): String {
        return webView.url.orEmpty()
            .ifBlank { homeViewModel.loadedUrl }
            .ifBlank { buildInitialTavernUrl(runtimeConfigRepository.localServiceUrl()) }
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

    // HistoricalProcessExitReasons 在部分机型上不会和 renderer gone 回调严格同步；
    // 这里先立即刷新，再延迟补刷两次，尽量把“刚刚那次 renderer 退出”打进导出的 exit-info 日志。
    private fun scheduleRendererExitInfoRefresh() {
        refreshApplicationExitInfo()
        RENDERER_EXIT_INFO_REFRESH_DELAYS_MS.forEach { delayMillis ->
            mainHandler.postDelayed(
                { refreshApplicationExitInfo() },
                delayMillis
            )
        }
    }

    // 诊断写盘不能反向影响宿主主流程；即使磁盘写失败，这里也只吞掉异常保留主功能。
    private fun recordHostDiagnostic(category: String, body: String) {
        runCatching { hostDiagnosticSink.record(category, body) }
    }

    private fun recordCriticalHostDiagnostic(category: String, body: String) {
        runCatching { criticalHostDiagnosticSink.record(category, body) }
    }

    // 统一收口当前 WebView/UI 关键状态，避免各事件各自拼字段导致 release 现场难以横向对比。
    private fun currentWebViewDiagnosticState(): String {
        return buildString {
            append("currentUrl=${normalizeDiagnosticValue(webView.url)}")
            append(" rememberedUrl=${normalizeDiagnosticValue(homeViewModel.loadedUrl)}")
            append(" localBaseUrl=${normalizeDiagnosticValue(buildInitialTavernUrl(runtimeConfigRepository.localServiceUrl()))}")
            append(" retryAttempts=${homeViewModel.pendingLocalRetryAttempts}")
            append(" webViewVisible=${webView.isVisible}")
            append(" refreshVisible=${webViewRefreshLayout.isVisible}")
            append(" refreshEnabled=${webViewRefreshLayout.isEnabled}")
            append(" pullGestureRefreshing=${homeViewModel.isPullGestureRefreshing}")
            append(" overlayVisible=${bootstrapOverlay.isVisible}")
            append(" activityFinishing=${activity.isFinishing}")
            append(" activityDestroyed=${activity.isDestroyed}")
            append(" webViewHardwareAccelerated=${webView.isHardwareAccelerated}")
            append(" webViewLayerType=${resolveViewLayerTypeName(webView.layerType)}")
            append(" webViewCacheMode=${resolveWebSettingsCacheModeName(webView.settings.cacheMode)}")
            append(" webViewTextZoom=${webView.settings.textZoom}")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                append(" rendererPriority=${resolveWebViewRendererPriorityName(webView.rendererRequestedPriority)}")
                append(" rendererPriorityWaivedWhenNotVisible=${webView.rendererPriorityWaivedWhenNotVisible}")
            }
        }
    }

    // renderer gone 是否由内存压力触发，单靠 didCrash 不够判断；
    // 这里把系统/进程/WebView 三层快照一次性打出来，便于后续按机型和 provider 归因。
    private fun currentRendererFailureDiagnosticState(
        didCrash: Boolean,
        rendererGoneDetail: String
    ): String {
        return buildString {
            append(resolveWebViewProviderSummary())
            append(" appExitInfoRefreshPlanMs=0")
            RENDERER_EXIT_INFO_REFRESH_DELAYS_MS.forEach { delayMillis ->
                append(",")
                append(delayMillis)
            }
            append(" rendererDidCrash=$didCrash")
            append(" $rendererGoneDetail")
            append(' ')
            append(currentHostMemoryDiagnosticState())
        }
    }

    private fun currentHostMemoryDiagnosticState(): String {
        val systemMemory = activityManager?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }
        val runningProcessInfo = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val processMemory = runCatching {
            activityManager?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
        }.getOrNull()
        val runtime = Runtime.getRuntime()
        val javaHeapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        val javaHeapMaxKb = runtime.maxMemory() / 1024L
        val javaHeapFreeKb = runtime.freeMemory() / 1024L
        val nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L
        val nativeHeapSizeKb = Debug.getNativeHeapSize() / 1024L
        val nativeHeapFreeKb = Debug.getNativeHeapFreeSize() / 1024L

        return buildString {
            append("systemLowMemory=${systemMemory?.lowMemory ?: "-"}")
            append(" systemAvailMemKb=${systemMemory?.availMem?.div(1024L) ?: "-"}")
            append(" systemTotalMemKb=${systemMemory?.totalMem?.div(1024L) ?: "-"}")
            append(" systemThresholdKb=${systemMemory?.threshold?.div(1024L) ?: "-"}")
            append(" appMemoryClassMb=${activityManager?.memoryClass ?: "-"}")
            append(" appLargeMemoryClassMb=${activityManager?.largeMemoryClass ?: "-"}")
            append(" isLowRamDevice=${activityManager?.isLowRamDevice ?: "-"}")
            append(" appImportance=${runningProcessInfo.importance}")
            append(" appImportanceReasonCode=${runningProcessInfo.importanceReasonCode}")
            append(" appLru=${runningProcessInfo.lru}")
            append(" processPssKb=${processMemory?.totalPss ?: "-"}")
            append(" processPrivateDirtyKb=${processMemory?.totalPrivateDirty ?: "-"}")
            append(" processSharedDirtyKb=${processMemory?.totalSharedDirty ?: "-"}")
            append(" javaHeapUsedKb=$javaHeapUsedKb")
            append(" javaHeapMaxKb=$javaHeapMaxKb")
            append(" javaHeapFreeKb=$javaHeapFreeKb")
            append(" nativeHeapAllocatedKb=$nativeHeapAllocatedKb")
            append(" nativeHeapSizeKb=$nativeHeapSizeKb")
            append(" nativeHeapFreeKb=$nativeHeapFreeKb")
            append(" webViewWidth=${webView.width}")
            append(" webViewHeight=${webView.height}")
            append(" webViewContentHeight=${webView.contentHeight}")
            append(" webViewProgress=${webView.progress}")
            append(" webViewScale=${resolveWebViewScale()}")
            append(" webViewLayerType=${resolveViewLayerTypeName(webView.layerType)}")
        }
    }

    private fun resolveWebViewProviderSummary(): String {
        return runCatching { currentWebViewPackageInfo() }
            .map { packageInfo ->
                if (packageInfo == null) {
                    currentRuntimeCompatibility().toDiagnosticText()
                } else {
                    WebViewRuntimeCompatibility.from(
                        webView = webView,
                        providerPackageInfo = packageInfo
                    ).toDiagnosticText()
                }
            }
            .getOrElse { error ->
                "providerPackage=- providerVersionName=- providerVersionCode=- providerError=${normalizeDiagnosticValue(error.message ?: error.javaClass.simpleName)}"
            }
    }

    private fun currentWebViewPackageInfo() = runCatching {
        WebViewCompat.getCurrentWebViewPackage(activity)
    }.getOrNull()

    private fun resolveViewLayerTypeName(layerType: Int): String {
        return when (layerType) {
            View.LAYER_TYPE_NONE -> "LAYER_TYPE_NONE"
            View.LAYER_TYPE_SOFTWARE -> "LAYER_TYPE_SOFTWARE"
            View.LAYER_TYPE_HARDWARE -> "LAYER_TYPE_HARDWARE"
            else -> "UNKNOWN"
        }
    }

    private fun resolveWebViewScale(): String {
        @Suppress("DEPRECATION")
        return webView.scale.toString()
    }
}

internal fun hasLoadedCurrentWebViewPageForBaseUrl(currentWebViewUrl: String, baseUrl: String): Boolean {
    val trimmedCurrentWebViewUrl = currentWebViewUrl.trim()
    if (trimmedCurrentWebViewUrl.isBlank()) {
        return false
    }

    // “当前页已加载”判断只允许基于真实 WebView URL 成立。
    return isTavernUrlForBaseUrl(trimmedCurrentWebViewUrl, baseUrl)
}

internal fun isTavernUrlForBaseUrl(url: String, baseUrl: String): Boolean {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedCurrentUrl = url.trim()
    return normalizedCurrentUrl == normalizedBaseUrl ||
        normalizedCurrentUrl == "$normalizedBaseUrl/" ||
        normalizedCurrentUrl.startsWith("$normalizedBaseUrl/#") ||
        normalizedCurrentUrl.startsWith("$normalizedBaseUrl/?") ||
        normalizedCurrentUrl.startsWith("$normalizedBaseUrl/")
}

internal fun buildInitialTavernUrl(baseUrl: String): String {
    return "${baseUrl.trim().trimEnd('/')}/"
}

internal fun buildApplyWebViewViewportDensityScript(
    percent: Int,
    baseViewportWidthCssPx: Int,
    reason: String
): String {
    val sanitizedPercent = BrowserZoomOptions.sanitizeViewportDensity(percent)
    val viewportWidth = resolveViewportDensityWidthCssPx(
        percent = sanitizedPercent,
        baseViewportWidthCssPx = baseViewportWidthCssPx
    )
    val initialScale = resolveViewportDensityInitialScale(sanitizedPercent)
    val reasonJson = quoteJavascriptString(reason)
    return """
        (function() {
            const head = document.head || document.getElementsByTagName('head')[0];
            if (!head) {
                return 'missing_head';
            }
            const percent = $sanitizedPercent;
            const viewportWidth = $viewportWidth;
            const initialScale = $initialScale;
            const root = document.documentElement;
            const body = document.body;
            if (body) {
                // 清理旧版错误实现留下的视觉缩放样式；界面密度只通过 layout viewport 生效。
                body.style.transform = '';
                body.style.transformOrigin = '';
                body.style.width = '';
                body.style.minWidth = '';
                body.style.minHeight = '';
                body.style.overflowX = '';
                body.style.overflowY = '';
            }
            let viewport = document.querySelector('meta[name="viewport"]');
            if (!viewport) {
                viewport = document.createElement('meta');
                viewport.setAttribute('name', 'viewport');
                head.appendChild(viewport);
            }
            if (viewport.dataset.sillydroidOriginalViewport === undefined) {
                viewport.dataset.sillydroidOriginalViewport = viewport.getAttribute('content') || '';
            }
            const originalViewport = viewport.dataset.sillydroidOriginalViewport || '';
            if (percent === 100) {
                if (originalViewport) {
                    viewport.setAttribute('content', originalViewport);
                } else {
                    viewport.setAttribute('content', 'width=device-width, initial-scale=1, viewport-fit=cover');
                }
                delete viewport.dataset.sillydroidViewportDensityPercent;
                delete viewport.dataset.sillydroidViewportDensityReason;
                delete viewport.dataset.sillydroidViewportDensityWidth;
                delete viewport.dataset.sillydroidHtmlPageZoomPercent;
                delete viewport.dataset.sillydroidHtmlPageZoomReason;
                delete viewport.dataset.sillydroidHtmlPageZoomWidth;
                if (root) {
                    delete root.dataset.sillydroidViewportDensityPercent;
                    delete root.dataset.sillydroidViewportDensityReason;
                    delete root.dataset.sillydroidHtmlPageZoomPercent;
                    delete root.dataset.sillydroidHtmlPageZoomReason;
                }
                return 'viewport_density_reset';
            }
            // 界面密度语义：降低有效密度时加宽 layout viewport，并用同等 initial-scale
            // 把更宽的 CSS 布局压回屏幕内，因此同屏内容更多；这不是 WebView page zoom。
            viewport.setAttribute(
                'content',
                'width=' + viewportWidth +
                    ', initial-scale=' + initialScale +
                    ', maximum-scale=1' +
                    ', viewport-fit=cover'
            );
            viewport.dataset.sillydroidViewportDensityPercent = String(percent);
            viewport.dataset.sillydroidViewportDensityReason = $reasonJson;
            viewport.dataset.sillydroidViewportDensityWidth = String(viewportWidth);
            if (root) {
                root.dataset.sillydroidViewportDensityPercent = String(percent);
                root.dataset.sillydroidViewportDensityReason = $reasonJson;
            }
            return 'viewport_density_applied:' + percent + ':' + viewportWidth;
        })();
    """.trimIndent()
}

internal fun resolveViewportDensityWidthCssPx(percent: Int, baseViewportWidthCssPx: Int): Int {
    val sanitizedPercent = BrowserZoomOptions.sanitizeViewportDensity(percent)
    val baseWidth = baseViewportWidthCssPx.coerceAtLeast(240)
    if (sanitizedPercent == BrowserZoomOptions.DEFAULT_PERCENT) {
        return baseWidth
    }
    return (baseWidth * BrowserZoomOptions.DEFAULT_PERCENT.toFloat() / sanitizedPercent).toInt()
        .coerceAtLeast(240)
}

internal fun resolveViewportDensityInitialScale(percent: Int): String {
    val factor = BrowserZoomOptions.toViewportDensityFactor(percent)
    return if (factor == 1f) {
        "1"
    } else {
        "%.4f".format(java.util.Locale.US, factor).trimEnd('0').trimEnd('.')
    }
}

private fun quoteJavascriptString(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
