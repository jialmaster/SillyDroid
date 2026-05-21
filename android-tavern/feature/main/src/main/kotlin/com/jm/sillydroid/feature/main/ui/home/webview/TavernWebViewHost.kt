package com.jm.sillydroid.feature.main.ui.home.webview

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewCompat
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.diagnostics.formatTrimMemoryLevel
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel

/**
 * 把 WebView 与宿主侧下拉刷新、Web 会话持久化、page lifecycle、本地重试、renderer crash 恢复、
 * URL 工具函数（local 判断 / 外开浏览器）等收拢到一个 host。
 *
 * MainActivity 持有一个实例，并通过构造参数注入需要的跨 host 回调（JS 桥安装、blob 下载桥脚本、
 * 文件选择器、下载行为、外部回到 ready 时的 prompt 等）。
 */
fun interface HostDiagnosticSink {
    fun record(category: String, body: String)
}

class TavernWebViewHost(
    private val activity: AppCompatActivity,
    private val homeViewModel: HomeViewModel,
    private val hostConfigStore: HostPreferencesRepository,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val processManager: BootstrapController,
    private val installJavascriptInterfaces: (WebView) -> Unit,
    private val installBlobBridgeScriptOnPageFinished: (WebView) -> Unit,
    private val restoreHostSystemBarAppearance: () -> Unit = {},
    private val onDownloadRequested: (BrowserDownloadRequest) -> Unit,
    private val onShowFileChooser: (android.webkit.WebChromeClient.FileChooserParams, android.webkit.ValueCallback<Array<Uri>>) -> Unit,
    private val jsErrorSink: WebViewJsErrorSink = WebViewJsErrorSink { /* no-op */ },
    private val hostDiagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> },
    private val refreshApplicationExitInfo: () -> Unit = {},
) {
    companion object {
        private const val LOG_TAG = "SillyDroidMain"
        private const val WEB_VIEW_STATE_KEY = "tavern.webview.state"
        private const val LOADED_URL_STATE_KEY = "tavern.webview.loadedUrl"
        private const val WEB_SESSION_BRIDGE_NAME = "StaiWebSessionBridge"
        private const val SYSTEM_NOTIFICATION_BRIDGE_NAME = "AndroidSystemNotificationBridge"
        private const val WEB_SESSION_STORAGE_PREFS_NAME = "sillydroid-webview-session"
        private const val WEB_SESSION_STORAGE_SNAPSHOT_KEY = "session-storage"
        // 恢复出来的 WebView 以 onPageCommitVisible 作为健康信号；在超时后仍未获得信号
        // 表示 surface 可能被系统回收成空白，退回 loadUrl 以重新拉起页面。
        private const val RESTORED_STATE_COMMIT_VISIBLE_TIMEOUT_MS = 6_000L

        // 仅 debug 包响应；用来手动踏 renderer-gone 路径。
        // adb shell am broadcast -a com.jm.sillydroid.debug.CRASH_RENDERER -p com.jm.sillydroid
        // adb shell am broadcast -a com.jm.sillydroid.debug.KILL_RENDERER  -p com.jm.sillydroid
        private const val DEBUG_ACTION_CRASH_RENDERER = "com.jm.sillydroid.debug.CRASH_RENDERER"
        private const val DEBUG_ACTION_KILL_RENDERER = "com.jm.sillydroid.debug.KILL_RENDERER"
        // ApplicationExitInfo 写入系统历史退出列表存在机型级延迟；renderer gone 后补几次刷新，
        // 提高导出日志命中“刚刚那次 WebView renderer 退出”的概率。
        private val RENDERER_EXIT_INFO_REFRESH_DELAYS_MS = longArrayOf(1_500L, 5_000L)
    }

    val webViewRefreshLayout: View = activity.findViewById(R.id.webViewRefreshLayout)
    val webView: WebView = activity.findViewById(R.id.webView)
    private val webViewPullRefreshHint: LinearLayout = activity.findViewById(R.id.webViewPullRefreshHint)
    private val webViewPullRefreshHintArc: View = activity.findViewById(R.id.webViewPullRefreshHintArc)
    private val webViewPullRefreshHintIcon: ImageView = activity.findViewById(R.id.webViewPullRefreshHintIcon)
    private val webViewPullRefreshHintText: TextView = activity.findViewById(R.id.webViewPullRefreshHintText)

    // overlay view 同时被 BootstrapOverlayHost 持有；这里只读它的 isVisible 来配合下拉刷新逻辑，
    // 以及在 showWebView / hideForBootstrapRestart 时一并切换可见性，保持一对一互斥。
    private val bootstrapOverlay: android.view.View = activity.findViewById(R.id.bootstrapOverlay)

    private var webSessionPersistenceController: WebSessionPersistenceController? = null

    private val webSessionStoragePreferences by lazy {
        activity.getSharedPreferences(WEB_SESSION_STORAGE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val activityManager by lazy {
        activity.getSystemService(ActivityManager::class.java)
    }

    private val mainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    private var rendererRecoveryActivityRecreateScheduled = false

    private val webReloadTracer by lazy { WebReloadTracer(LOG_TAG) }

    // 恢复 WebView session 后的 commit-visible 守望者；只在 hasRestoredWebViewState 路径上启用。
    private val restoredWebViewWatchdog = RestoredWebViewWatchdog(
        scheduler = RestoredWebViewWatchdog.Scheduler { delayMillis, task ->
            webView.postDelayed(task, delayMillis)
            RestoredWebViewWatchdog.Cancellable { webView.removeCallbacks(task) }
        },
        timeoutMillis = RESTORED_STATE_COMMIT_VISIBLE_TIMEOUT_MS
    )

    private val homeWebViewController by lazy {
        HomeWebViewController(
            context = activity,
            webViewProvider = { webView },
            installSessionPersistence = ::installWebSessionPersistenceController,
            installJavascriptInterfaces = installJavascriptInterfaces,
            shouldOpenExternally = ::shouldOpenExternally,
            openExternalBrowser = ::openExternalBrowser,
            onPageStarted = ::handleWebViewPageStarted,
            onPageCommitVisible = ::handleWebViewPageCommitVisible,
            onPageFinished = ::handleWebViewPageFinished,
            isLocalTavernUrl = ::isLocalTavernUrl,
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

    fun configure() {
        val webViewBackgroundColor = ContextCompat.getColor(activity, R.color.tavern_webview_background)
        homeWebViewRefreshController.configure(webViewBackgroundColor)
        homeWebViewController.configure()
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

    fun saveState(outState: Bundle) {
        // renderer crash 走 Activity.recreate() 时，旧 WebView 可能已经拿不到完整 back/forward stack。
        // 这里分成两层持久化：
        // 1) 能拿到完整 WebView state 就保存整包；
        // 2) 无论成功与否，都额外记住当前 URL，供下一次 onCreate 至少回到原页面而不是首页。
        val webViewState = Bundle()
        var saveStateError: Throwable? = null
        val savedStateList = runCatching { webView.saveState(webViewState) }
            .onFailure { error ->
                saveStateError = error
                Log.w(LOG_TAG, "Failed to save WebView state before Activity recreation.", error)
            }
            .getOrNull()
        val bundleSaved = savedStateList != null && !webViewState.isEmpty
        if (bundleSaved) {
            outState.putBundle(WEB_VIEW_STATE_KEY, webViewState)
        }
        val fallbackUrl = currentKnownWebViewUrl()
        outState.putString(LOADED_URL_STATE_KEY, fallbackUrl)
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=save_state")
                append(" bundleSaved=$bundleSaved")
                append(" fallbackUrl=${normalizeDiagnosticValue(fallbackUrl)}")
                if (saveStateError != null) {
                    append(" error=${normalizeDiagnosticValue(saveStateError?.message ?: saveStateError?.javaClass?.simpleName)}")
                }
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
    }

    fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            recordHostDiagnostic(
                category = "webview",
                body = "event=restore_state_skipped reason=no_saved_state ${currentWebViewDiagnosticState()}"
            )
            return
        }
        val persistedLoadedUrl = savedInstanceState.getString(LOADED_URL_STATE_KEY).orEmpty()
        val webViewState = savedInstanceState.getBundle(WEB_VIEW_STATE_KEY)
        var restoreStateError: Throwable? = null
        val restoredState = webViewState?.let { state ->
            runCatching { webView.restoreState(state) }
                .onFailure { error ->
                    restoreStateError = error
                    Log.w(LOG_TAG, "Failed to restore WebView state after Activity recreation.", error)
                }
                .getOrNull()
        }
        val restoredUrl = restoredState?.currentItem?.url.orEmpty()
            .ifBlank { persistedLoadedUrl }

        if (restoredUrl.isBlank()) {
            recordHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=restore_state_no_url")
                    append(" bundlePresent=${webViewState != null}")
                    append(" persistedUrl=${normalizeDiagnosticValue(persistedLoadedUrl)}")
                    if (restoreStateError != null) {
                        append(" error=${normalizeDiagnosticValue(restoreStateError?.message ?: restoreStateError?.javaClass?.simpleName)}")
                    }
                    append(' ')
                    append(currentWebViewDiagnosticState())
                }
            )
            return
        }

        // 恢复出来的 URL 可能在上一轮会话中使用了不同的服务端口。
        // 若与当前 localUrl 不匹配，则不能复用，避免 WebView 以旧端口发起请求
        // 造成永久 ERR_CONNECTION_REFUSED 白屏。
        if (!isLocalTavernUrl(restoredUrl)) {
            recordHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=restore_state_rejected_non_local")
                    append(" restoredUrl=${normalizeDiagnosticValue(restoredUrl)}")
                    append(" persistedUrl=${normalizeDiagnosticValue(persistedLoadedUrl)}")
                    append(" expectedBaseUrl=${normalizeDiagnosticValue(buildInitialTavernUrl(runtimeConfigRepository.localServiceUrl()))}")
                    append(' ')
                    append(currentWebViewDiagnosticState())
                }
            )
            return
        }

        homeViewModel.loadedUrl = restoredUrl
        // 只有真正 restore 进了 WebView back/forward stack，后续 showWebView 才能跳过 loadUrl。
        // 如果这里只剩 URL fallback，就让 showWebView 主动 load 这个 URL，把用户带回崩溃前页面。
        homeViewModel.hasRestoredWebViewState = restoredState != null
        if (restoredState == null) {
            Log.w(
                LOG_TAG,
                "Restored Activity with URL fallback only; WebView state bundle unavailable for url=$restoredUrl"
            )
            recordHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=restore_state_url_fallback")
                    append(" restoredUrl=${normalizeDiagnosticValue(restoredUrl)}")
                    append(" persistedUrl=${normalizeDiagnosticValue(persistedLoadedUrl)}")
                    append(" bundlePresent=${webViewState != null}")
                    if (restoreStateError != null) {
                        append(" error=${normalizeDiagnosticValue(restoreStateError?.message ?: restoreStateError?.javaClass?.simpleName)}")
                    }
                    append(' ')
                    append(currentWebViewDiagnosticState())
                }
            )
            return
        }
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=restore_state_success")
                append(" restoredUrl=${normalizeDiagnosticValue(restoredUrl)}")
                append(" historySize=${restoredState.size}")
                append(" persistedUrl=${normalizeDiagnosticValue(persistedLoadedUrl)}")
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
    }

    fun showWebView(baseUrl: String) {
        bootstrapOverlay.isVisible = false
        webViewRefreshLayout.isVisible = true
        webView.isVisible = true
        updateRefreshLayoutEnabled()
        if (homeViewModel.hasRestoredWebViewState) {
            // 已恢复出原来的 WebView 会话时，不再重新 load baseUrl，避免把前端状态重置到首页。
            homeViewModel.hasRestoredWebViewState = false
            recordHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=show_webview_restored_state")
                    append(" action=start_watchdog")
                    append(" baseUrl=${normalizeDiagnosticValue(buildInitialTavernUrl(baseUrl))}")
                    append(' ')
                    append(currentWebViewDiagnosticState())
                }
            )
            scheduleRestoredStateWatchdog(baseUrl)
            return
        }

        if (isCurrentWebViewPageFor(baseUrl)) {
            return
        }

        val targetUrl = resolveInitialTavernUrl(
            baseUrl = baseUrl,
            rememberedUrl = homeViewModel.loadedUrl
        )
        homeViewModel.loadedUrl = targetUrl
        recordHostDiagnostic(
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

    fun hideForBootstrapRestart() {
        // 仅隐藏不重建 WebView 时，恢复态 watchdog 里的 capturedWebView !== webView 门控不会生效；
        // 必须在这里主动取消，避免 6s 后被 loadUrl 打变 bootstrap 重启流程。
        cancelRestoredStateWatchdog()
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
        webViewRefreshLayout.isVisible = false
        webView.isVisible = false
        // 返回 bootstrap overlay 时，把系统栏背景也切回宿主自己的遮罩色，避免残留上一页的 WebView 主题色。
        restoreHostSystemBarAppearance()
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
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
    }

    fun onImeVisibilityChanged(visible: Boolean) {
        if (visible) {
            homeWebViewRefreshController.reset()
        }
        updateRefreshLayoutEnabled()
    }

    fun onResume() {
        // about:blank 白屏现场已经证明：不能只靠启动阶段判断，Activity 回前台时也要基于真实 WebView URL 复核一次。
        ensureVisibleWebViewUrlHealth(trigger = "activity_resume")
    }

    fun onTrimMemory(level: Int) {
        recordHostDiagnostic(
            category = "memory",
            body = "scope=main_activity_webview event=on_trim_memory level=${formatTrimMemoryLevel(level)} rawLevel=$level ${currentWebViewDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
    }

    fun onLowMemory() {
        recordHostDiagnostic(
            category = "memory",
            body = "scope=main_activity_webview event=on_low_memory ${currentWebViewDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
    }

    fun onDestroy() {
        cancelRestoredStateWatchdog()
        uninstallDebugRendererCrashReceiver()
        webSessionPersistenceController?.close()
        webSessionPersistenceController = null
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

    private fun handleWebViewPageStarted(sourceWebView: WebView, url: String?) {
        if (!isCurrentWebViewInstance(sourceWebView)) {
            return
        }
        logActiveWebReloadTrace(phase = "page_started", url = url)
        ensureVisibleWebViewUrlHealth(trigger = "page_started", observedUrl = url)
    }

    private fun handleWebViewPageCommitVisible(sourceWebView: WebView, url: String?) {
        if (!isCurrentWebViewInstance(sourceWebView)) {
            return
        }
        logActiveWebReloadTrace(phase = "page_commit_visible", url = url)
        cancelRestoredStateWatchdog()
        ensureVisibleWebViewUrlHealth(trigger = "page_commit_visible", observedUrl = url)
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
        // 恢复态 watchdog 的首选信号是 onPageCommitVisible；但某些 cache/restore 路径可能不触发。
        // page_finished 同样代表本次 navigation 跑完，需要兑底取消 watchdog，避免 6s 后多余的 reload。
        cancelRestoredStateWatchdog()
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
        updateRefreshLayoutEnabled()
        CookieManager.getInstance().flush()
        installBlobBridgeScriptOnPageFinished(sourceWebView)
        installSystemBarThemeSyncScript(sourceWebView)
        if (!url.isNullOrBlank()) {
            homeViewModel.loadedUrl = url
            homeViewModel.pendingLocalRetryAttempts = 0
        }
        ensureVisibleWebViewUrlHealth(trigger = "page_finished", observedUrl = url)
        clearActiveWebReloadTrace()
    }

    private fun installSystemBarThemeSyncScript(sourceWebView: WebView) {
        // 酒馆内部主题切换多半是前端改 html/body 的 class/style，不一定整页 reload；
        // 这里给当前文档挂一个 observer，首次进入和后续主题切换都把根背景色同步给 Android 系统栏。
        sourceWebView.evaluateJavascript(
            """
                (function() {
                    const bridge = window.SillyDroidAndroidHostBridge;
                    if (!bridge || typeof bridge.setSystemBarsBackgroundColor !== 'function') {
                        return 'bridge_missing';
                    }

                    function normalizeHexColor(input) {
                        const value = String(input || '').trim().toLowerCase();
                        if (!value || value === 'transparent') {
                            return '';
                        }

                        const rgbaMatch = value.match(/^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)$/);
                        if (rgbaMatch) {
                            const alpha = rgbaMatch[4] == null ? 1 : Number(rgbaMatch[4]);
                            if (!Number.isFinite(alpha) || alpha <= 0.01) {
                                return '';
                            }
                            const rgb = rgbaMatch.slice(1, 4).map(function(channel) {
                                const clamped = Math.max(0, Math.min(255, Number(channel)));
                                return clamped.toString(16).padStart(2, '0');
                            });
                            return '#' + rgb.join('');
                        }

                        const hexMatch = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i);
                        if (!hexMatch) {
                            return '';
                        }

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

                    function firstSolidBackgroundHex() {
                        const themeMeta = document.querySelector('meta[name="theme-color"]');
                        const metaColor = normalizeHexColor(themeMeta && themeMeta.content);
                        if (metaColor) {
                            return metaColor;
                        }

                        const candidates = [document.body, document.documentElement];
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

    private fun handleWebViewRendererGone(didCrash: Boolean) {
        val recoveryUrl = currentKnownWebViewUrl()
        val rendererFailureSnapshot = currentRendererFailureDiagnosticState(didCrash = didCrash)
        scheduleRendererExitInfoRefresh()
        if (recoveryUrl.isNotBlank()) {
            homeViewModel.loadedUrl = recoveryUrl
        }
        homeViewModel.hasRestoredWebViewState = false
        cancelRestoredStateWatchdog()
        homeViewModel.isPullGestureRefreshing = false
        homeWebViewRefreshController.reset()
        updateRefreshLayoutEnabled()
        clearActiveWebReloadTrace()
        if (rendererRecoveryActivityRecreateScheduled) {
            Log.w(
                LOG_TAG,
                "WebView renderer gone (didCrash=$didCrash) while Activity recreation is already scheduled."
            )
            recordHostDiagnostic(
                category = "webview",
                body = buildString {
                    append("event=renderer_gone_duplicate")
                    append(" didCrash=$didCrash")
                    append(" exitKind=${if (didCrash) "crash" else "non_crash_exit"}")
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
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=renderer_gone")
                append(" didCrash=$didCrash")
                append(" exitKind=${if (didCrash) "crash" else "non_crash_exit"}")
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
                    recordHostDiagnostic(
                        category = "webview",
                        body = "event=renderer_gone_recreate_aborted reason=activity_not_alive ${currentWebViewDiagnosticState()} ${rendererFailureSnapshot}"
                    )
                    return@post
                }
                activity.recreate()
            }
        } else {
            recordHostDiagnostic(
                category = "webview",
                body = "event=renderer_gone_recreate_skipped reason=activity_not_alive ${currentWebViewDiagnosticState()} ${rendererFailureSnapshot}"
            )
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

    private fun ensureVisibleWebViewUrlHealth(trigger: String, observedUrl: String? = null) {
        if (!processManager.currentSnapshot().isReady) {
            return
        }
        if (!webView.isVisible || !webViewRefreshLayout.isVisible || bootstrapOverlay.isVisible) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        // restoreState 刚接管的那几秒由恢复态 watchdog 负责判断 commit-visible，
        // 这里不抢跑，避免还原 back/forward stack 期间被新的 loadUrl 覆盖掉。
        if (restoredWebViewWatchdog.isScheduled) {
            return
        }

        val expectedBaseUrl = runtimeConfigRepository.localServiceUrl()
        val currentUrl = webView.url.orEmpty().trim()
        if (hasLoadedCurrentWebViewPageForBaseUrl(currentUrl, expectedBaseUrl)) {
            return
        }

        // 正常 local 页面刚开始 load 时，WebView 可能短暂还没把 url 暴露出来；
        // 这时先让当前 navigation 继续，避免回前台或 pageStarted 初期重复发 loadUrl。
        if (currentUrl.isBlank() && webView.progress in 1..99) {
            return
        }

        val recoveryUrl = resolveInitialTavernUrl(
            baseUrl = expectedBaseUrl,
            rememberedUrl = homeViewModel.loadedUrl
        )
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=unexpected_visible_webview_url")
                append(" trigger=$trigger")
                append(" action=load_url")
                append(" observedUrl=${normalizeDiagnosticValue(observedUrl)}")
                append(" targetUrl=${normalizeDiagnosticValue(recoveryUrl)}")
                append(" expectedBaseUrl=${normalizeDiagnosticValue(buildInitialTavernUrl(expectedBaseUrl))}")
                append(" currentScheme=${normalizeDiagnosticValue(Uri.parse(currentUrl.ifBlank { "about:blank" }).scheme)}")
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
        homeViewModel.loadedUrl = recoveryUrl
        webView.loadUrl(recoveryUrl)
    }

    private fun scheduleLocalWebViewRetry(failingUrl: String) {
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

    private fun isCurrentWebViewPageFor(baseUrl: String): Boolean {
        // 这里必须只看“当前这一个真实 WebView 实例”已经加载出的 URL。
        // 如果新建出来的 WebView 还停在 about:blank，却拿 rememberedUrl 当 currentUrl，
        // 会误判成“页面已在当前站点”并跳过 loadUrl，最终把整页永久留在空白文档。
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

    private fun scheduleRestoredStateWatchdog(baseUrl: String) {
        val targetUrl = homeViewModel.loadedUrl.ifBlank { buildInitialTavernUrl(baseUrl) }
        val capturedWebView = webView
        recordHostDiagnostic(
            category = "webview",
            body = buildString {
                append("event=restored_state_watchdog_started")
                append(" timeoutMs=$RESTORED_STATE_COMMIT_VISIBLE_TIMEOUT_MS")
                append(" targetUrl=${normalizeDiagnosticValue(targetUrl)}")
                append(' ')
                append(currentWebViewDiagnosticState())
            }
        )
        restoredWebViewWatchdog.start(targetUrl) { url ->
            // 任务真正运行时再做一次门控，避免在 destroy 或 webView 被替换后误触发。
            if (activity.isFinishing || activity.isDestroyed) {
                recordHostDiagnostic(
                    category = "webview",
                    body = "event=restored_state_watchdog_skipped reason=activity_not_alive targetUrl=${normalizeDiagnosticValue(url)} ${currentWebViewDiagnosticState()}"
                )
                return@start
            }
            if (capturedWebView !== webView) {
                recordHostDiagnostic(
                    category = "webview",
                    body = "event=restored_state_watchdog_skipped reason=webview_replaced targetUrl=${normalizeDiagnosticValue(url)} ${currentWebViewDiagnosticState()}"
                )
                return@start
            }
            Log.w(
                LOG_TAG,
                "Restored WebView state did not reach onPageCommitVisible within timeout; reloading url=$url"
            )
            recordHostDiagnostic(
                category = "webview",
                body = "event=restored_state_watchdog_timeout action=load_url targetUrl=${normalizeDiagnosticValue(url)} ${currentWebViewDiagnosticState()}"
            )
            homeViewModel.loadedUrl = url
            webView.loadUrl(url)
        }
    }

    private fun cancelRestoredStateWatchdog() {
        restoredWebViewWatchdog.cancel()
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

    // 统一收口当前 WebView/UI 关键状态，避免各事件各自拼字段导致 release 现场难以横向对比。
    private fun currentWebViewDiagnosticState(): String {
        return buildString {
            append("currentUrl=${normalizeDiagnosticValue(webView.url)}")
            append(" rememberedUrl=${normalizeDiagnosticValue(homeViewModel.loadedUrl)}")
            append(" localBaseUrl=${normalizeDiagnosticValue(buildInitialTavernUrl(runtimeConfigRepository.localServiceUrl()))}")
            append(" restoredStatePending=${homeViewModel.hasRestoredWebViewState}")
            append(" retryAttempts=${homeViewModel.pendingLocalRetryAttempts}")
            append(" webViewVisible=${webView.isVisible}")
            append(" refreshVisible=${webViewRefreshLayout.isVisible}")
            append(" refreshEnabled=${webViewRefreshLayout.isEnabled}")
            append(" pullGestureRefreshing=${homeViewModel.isPullGestureRefreshing}")
            append(" overlayVisible=${bootstrapOverlay.isVisible}")
            append(" activityFinishing=${activity.isFinishing}")
            append(" activityDestroyed=${activity.isDestroyed}")
            append(" watchdogScheduled=${restoredWebViewWatchdog.isScheduled}")
            append(" watchdogUrl=${normalizeDiagnosticValue(restoredWebViewWatchdog.pendingUrl)}")
        }
    }

    // renderer gone 是否由内存压力触发，单靠 didCrash 不够判断；
    // 这里把系统/进程/WebView 三层快照一次性打出来，便于后续按机型和 provider 归因。
    private fun currentRendererFailureDiagnosticState(didCrash: Boolean): String {
        return buildString {
            append(resolveWebViewProviderSummary())
            append(" appExitInfoRefreshPlanMs=0")
            RENDERER_EXIT_INFO_REFRESH_DELAYS_MS.forEach { delayMillis ->
                append(",")
                append(delayMillis)
            }
            append(" rendererDidCrash=$didCrash")
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
        return runCatching { WebViewCompat.getCurrentWebViewPackage(activity) }
            .map { packageInfo ->
                if (packageInfo == null) {
                    "providerPackage=- providerVersionName=- providerVersionCode=-"
                } else {
                    "providerPackage=${normalizeDiagnosticValue(packageInfo.packageName)} " +
                        "providerVersionName=${normalizeDiagnosticValue(packageInfo.versionName)} " +
                        "providerVersionCode=${PackageInfoCompat.getLongVersionCode(packageInfo)}"
                }
            }
            .getOrElse { error ->
                "providerPackage=- providerVersionName=- providerVersionCode=- providerError=${normalizeDiagnosticValue(error.message ?: error.javaClass.simpleName)}"
            }
    }

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

internal fun resolveInitialTavernUrl(baseUrl: String, rememberedUrl: String): String {
    val trimmedRememberedUrl = rememberedUrl.trim()
    return if (trimmedRememberedUrl.isNotBlank() && isTavernUrlForBaseUrl(trimmedRememberedUrl, baseUrl)) {
        trimmedRememberedUrl
    } else {
        buildInitialTavernUrl(baseUrl)
    }
}

internal fun hasLoadedCurrentWebViewPageForBaseUrl(currentWebViewUrl: String, baseUrl: String): Boolean {
    val trimmedCurrentWebViewUrl = currentWebViewUrl.trim()
    if (trimmedCurrentWebViewUrl.isBlank()) {
        return false
    }

    // “当前页已加载”判断只允许基于真实 WebView URL 成立；
    // rememberedUrl 仍然只用于“下一次该 load 什么 URL”，不能反过来冒充当前已渲染页面。
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
