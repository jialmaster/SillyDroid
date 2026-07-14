package com.jm.sillydroid.feature.main.ui.home.webview

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Debug
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.BrowserDataClearOptions
import com.jm.sillydroid.core.model.settings.BrowserDataClearTarget
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.BrowserZoomOptions
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.diagnostics.formatTrimMemoryLevel
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue
import com.jm.sillydroid.feature.main.floatingbrowser.FloatingBrowserSurfaceStore
import com.jm.sillydroid.feature.main.model.download.BrowserResponseDownloadRequest
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeInstaller
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeTarget
import com.jm.sillydroid.feature.main.ui.home.io.BrowserFileChooserSelectionPolicy
import com.jm.sillydroid.feature.main.ui.home.io.GeckoFilePromptUriMaterializer
import com.jm.sillydroid.feature.main.ui.home.io.HostIoController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BuildConfig as GeckoBuildConfig
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import kotlin.math.roundToInt

/**
 * GeckoView 浏览器宿主 adapter。
 *
 * GeckoView 没有 WebView 的 addJavascriptInterface / DownloadListener / WebChromeClient 等直接兼容面；
 * 本 host 负责 GeckoSession 生命周期、文件选择、普通下载、站点数据清理和渲染诊断，
 * 宿主插件桥、通知 shim、blob/data 导出由 [BrowserHostBridgeInstaller] 通过内置 WebExtension 接管。
 */
class TavernGeckoViewHost(
    private val activity: AppCompatActivity,
    private val homeViewModel: HomeViewModel,
    private val hostConfigStore: HostPreferencesRepository,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val processManager: BootstrapController,
    private val bridgeInstaller: BrowserHostBridgeInstaller,
    private val restoreHostSystemBarAppearance: () -> Unit = {},
    private val onDownloadRequested: (BrowserResponseDownloadRequest) -> Unit,
    private val onShowFileChooser: (HostIoController.BrowserFileChooserRequest, (Array<Uri>?) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val ioDispatcher: kotlin.coroutines.CoroutineContext,
    private val hostDiagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> },
    private val criticalHostDiagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> },
    private val filePromptUriMaterializer: GeckoFilePromptUriMaterializer = GeckoFilePromptUriMaterializer(activity),
    private val floatingBrowserSurfaceStore: FloatingBrowserSurfaceStore = FloatingBrowserSurfaceStore(),
) : TavernBrowserHost {
    companion object {
        private var sharedRuntime: GeckoRuntime? = null

        @Synchronized
        private fun runtime(activity: AppCompatActivity): GeckoRuntime {
            sharedRuntime?.let { return it }
            val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .consoleOutput(true)
                .debugLogging(debuggable)
                .remoteDebuggingEnabled(debuggable)
                .aboutConfigEnabled(debuggable)
                .webFontsEnabled(true)
                .webManifest(true)
                .extensionsWebAPIEnabled(true)
                .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                .automaticFontSizeAdjustment(false)
                .fontInflation(false)
                .fontSizeFactor(1.0f)
                .inputAutoZoomEnabled(false)
                .doubleTapZoomingEnabled(false)
                .forceUserScalableEnabled(false)
                .useMaxScreenDepth(true)
                .lowMemoryDetection(true)
                .build()
            return GeckoRuntime.create(activity.applicationContext, settings)
                .also { sharedRuntime = it }
        }
    }

    override val browserEngine: BrowserEngine = BrowserEngine.GECKOVIEW
    private val browserFrame: ViewGroup = activity.findViewById(R.id.webViewRefreshLayout)
    private val retainedGeckoSurface = floatingBrowserSurfaceStore.acquireGecko(activity, browserFrame)
    private val geckoView: GeckoView = retainedGeckoSurface.geckoView.also { retainedView ->
        // GeckoView 作为替代内核的关键目标是减少系统 WebView renderer gone，同时保持 GPU 合成路径常驻。
        retainedView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        retainedView.setBackgroundColor(ContextCompat.getColor(activity, R.color.tavern_webview_background))
    }
    private val bootstrapOverlay: View = activity.findViewById(R.id.bootstrapOverlay)
    private val activityManager by lazy {
        activity.getSystemService(ActivityManager::class.java)
    }
    private val session: GeckoSession = retainedGeckoSurface.session

    private var currentUrl: String = retainedGeckoSurface.currentUrl
    private var configured = false
    private var waitingForInitialPagePaint = false
    private val httpAuthPromptController by lazy {
        HttpAuthPromptController(
            activity = activity,
            diagnosticSink = HostDiagnosticSink { category, body ->
                recordHostDiagnostic(category = category, body = body)
            }
        )
    }

    override val browserContainer: View
        get() = browserFrame

    override val browserSurface: View
        get() = geckoView
    override val browserSessionIdentity: String
        get() = "${browserEngine.name}:${System.identityHashCode(session)}"
    private var browserSurfaceDestroyed = false

    override fun currentBrowserRuntimeInfo(): BrowserRuntimeInfo {
        val coreVersion = GeckoBuildConfig.GRE_MILESTONE.trim()
            .ifBlank { GeckoBuildConfig.MOZILLA_VERSION.trim() }
        return BrowserRuntimeInfo(
            engine = browserEngine,
            runtimeName = "GeckoView",
            packageName = GeckoBuildConfig.LIBRARY_PACKAGE_NAME,
            versionName = GeckoBuildConfig.MOZILLA_VERSION,
            versionCode = GeckoBuildConfig.MOZ_APP_BUILDID,
            coreName = "Gecko",
            coreVersion = coreVersion,
            coreMajorVersion = parseMajorVersion(coreVersion),
            recommendedCoreMajorVersion = 0,
            outdated = false,
            userAgent = GeckoBuildConfig.USER_AGENT_GECKOVIEW_MOBILE
        )
    }

    override fun currentBrowserZoomPercent(): Int {
        return BrowserZoomOptions.sanitize((runtime(activity).settings.fontSizeFactor * 100f).roundToInt())
    }

    override fun currentBrowserPageZoomPercent(): Int {
        return BrowserZoomOptions.sanitizeViewportDensity(hostConfigStore.browserPageZoomPercent)
    }

    override fun configure() {
        if (configured) {
            return
        }
        configured = true
        browserFrame.setBackgroundColor(ContextCompat.getColor(activity, R.color.tavern_webview_background))
        installDelegates()
        if (!session.isOpen) {
            session.open(runtime(activity))
        }
        if (geckoView.session !== session) {
            geckoView.setSession(session)
        }
        bridgeInstaller.install(buildBridgeTarget())
        filePromptUriMaterializer.cleanPreparedFiles()
        setBrowserZoomPercent(hostConfigStore.browserZoomPercent)
        setBrowserPageZoomPercent(hostConfigStore.browserPageZoomPercent)
        restoreHostSystemBarAppearance()
        recordHostDiagnostic(
            category = "geckoview",
            body = "event=configured ${currentGeckoDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
    }

    override fun setBrowserZoomPercent(percent: Int): Boolean {
        val sanitizedPercent = BrowserZoomOptions.sanitize(percent)
        val zoomFactor = BrowserZoomOptions.toZoomFactor(sanitizedPercent)
        runtime(activity).settings.setFontSizeFactor(zoomFactor)
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = buildString {
                append("event=browser_text_zoom_applied")
                append(" engine=${browserEngine.name}")
                append(" percent=$sanitizedPercent")
                append(" fontSizeFactor=${runtime(activity).settings.fontSizeFactor}")
            }
        )
        return true
    }

    override fun setBrowserPageZoomPercent(percent: Int): Boolean {
        val sanitizedPercent = BrowserZoomOptions.sanitizeViewportDensity(percent)
        val baseViewportWidthCssPx = resolveGeckoBaseViewportWidthCssPx()
        val requested = bridgeInstaller.requestViewportDensityPercent(
            percent = sanitizedPercent,
            baseViewportWidthCssPx = baseViewportWidthCssPx
        )
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = buildString {
                append("event=browser_viewport_density_requested")
                append(" engine=${browserEngine.name}")
                append(" percent=$sanitizedPercent")
                append(" baseViewportWidthCssPx=$baseViewportWidthCssPx")
                append(" requested=$requested")
            }
        )
        return requested
    }

    private fun resolveGeckoBaseViewportWidthCssPx(): Int {
        val density = activity.resources.displayMetrics.density.takeIf { value ->
            value.isFinite() && value > 0f
        } ?: 1f
        val widthPx = geckoView.width
            .takeIf { value -> value > 0 }
            ?: browserFrame.width.takeIf { value -> value > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        return (widthPx / density).toInt().coerceAtLeast(240)
    }

    override fun showBrowser(baseUrl: String) {
        browserFrame.isVisible = true
        geckoView.isVisible = true
        session.setActive(true)
        session.setFocused(true)
        homeViewModel.isPullGestureRefreshing = false
        if (homeViewModel.shouldForceFreshWebViewLoad) {
            showInitialPagePaintCover(reason = "force_fresh_load")
            forceFreshGeckoViewLoad(baseUrl)
            return
        }

        val targetUrl = buildInitialTavernUrl(baseUrl)
        if (isCurrentPageFor(baseUrl)) {
            if (waitingForInitialPagePaint) {
                return
            }
            bootstrapOverlay.isVisible = false
            updateRefreshLayoutEnabled()
            return
        }
        showInitialPagePaintCover(reason = "initial_load")
        updateCurrentUrl(targetUrl)
        homeViewModel.loadedUrl = targetUrl
        bridgeInstaller.install(buildBridgeTarget()) {
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=show_geckoview_load_uri targetUrl=${normalizeDiagnosticValue(targetUrl)} baseUrl=${normalizeDiagnosticValue(baseUrl)} ${currentGeckoDiagnosticState()}"
            )
            loadTavernUri(targetUrl)
        }
    }

    override fun hideForBootstrapRestart() {
        homeViewModel.isPullGestureRefreshing = false
        waitingForInitialPagePaint = false
        session.setActive(false)
        session.setFocused(false)
        browserFrame.isVisible = false
        geckoView.isVisible = false
        restoreHostSystemBarAppearance()
    }

    override fun reloadTavernUiIfPossible(snapshot: BootstrapSessionSnapshot) {
        if (!snapshot.isReady || !geckoView.isVisible) {
            return
        }
        reloadTavernWebView(source = "host_state_ready")
    }

    override fun reloadTavernWebView(source: String): Boolean {
        if (!geckoView.isVisible || bootstrapOverlay.isVisible) {
            recordHostDiagnostic(
                category = "geckoview",
                body = "event=reload_blocked source=$source geckoVisible=${geckoView.isVisible} overlayVisible=${bootstrapOverlay.isVisible}"
            )
            return false
        }
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = "event=reload_requested source=$source ${currentGeckoDiagnosticState()}"
        )
        session.reload()
        return true
    }

    override fun updateRefreshLayoutEnabled() {
        // GeckoView 不复用 WebView 的 DOM 顶部探针；下拉刷新先保持关闭，避免新增一套跨内核滚动探针干预页面。
        browserFrame.isEnabled = false
        homeViewModel.isPullGestureRefreshing = false
    }

    override fun resetRefreshOnBootstrapEvent() {
        homeViewModel.isPullGestureRefreshing = false
    }

    override fun onImeVisibilityChanged(visible: Boolean) {
        homeViewModel.isImeVisible = visible
        updateRefreshLayoutEnabled()
    }

    override fun onTrimMemory(level: Int) {
        recordCriticalHostDiagnostic(
            category = "memory",
            body = "scope=main_activity_geckoview event=on_trim_memory level=${formatTrimMemoryLevel(level)} rawLevel=$level ${currentGeckoDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
    }

    override fun onLowMemory() {
        recordCriticalHostDiagnostic(
            category = "memory",
            body = "scope=main_activity_geckoview event=on_low_memory ${currentGeckoDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
    }

    override fun onDestroy() {
        if (browserSurfaceDestroyed) {
            return
        }
        releaseGeckoActivityBindings(reason = "permanent_destroy", closeProcessBridge = true)
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = "event=destroy_geckoview_started ${currentGeckoDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
        floatingBrowserSurfaceStore.destroyGeckoIfSame(geckoView = geckoView, session = session)
        browserSurfaceDestroyed = true
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = "event=destroy_geckoview_finished ${currentHostMemoryDiagnosticState()}"
        )
    }

    /** Activity 销毁时解除窗口 delegate 和旧容器，但保留进程级 GeckoSession。 */
    override fun releaseActivityBindings() {
        if (!browserSurfaceDestroyed) {
            releaseGeckoActivityBindings(reason = "activity_destroy", closeProcessBridge = false)
        }
        floatingBrowserSurfaceStore.releaseActivityBinding(geckoView, browserFrame)
        if (!browserSurfaceDestroyed) {
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=activity_bindings_released session=$browserSessionIdentity ${currentGeckoDiagnosticState()}"
            )
        }
    }

    /** 释放 Activity delegate；普通销毁必须保留进程安全 native bridge，renderer 销毁才关闭。 */
    private fun releaseGeckoActivityBindings(reason: String, closeProcessBridge: Boolean) {
        httpAuthPromptController.dismissActivePrompt(reason = "geckoview_$reason")
        if (closeProcessBridge) {
            bridgeInstaller.close()
        }
        filePromptUriMaterializer.cleanPreparedFiles()
        // Session/页面继续由进程 store 持有；所有 Activity delegate 必须解除，等待下个 Activity 重新注册。
        session.navigationDelegate = null
        session.progressDelegate = null
        session.contentDelegate = null
        session.permissionDelegate = null
        session.promptDelegate = null
    }

    override fun openCurrentPageInExternalBrowser(): Boolean {
        val targetUrl = currentUrl.trim()
            .ifBlank { homeViewModel.loadedUrl }
            .ifBlank { buildInitialTavernUrl(runtimeConfigRepository.localServiceUrl()) }
        if (targetUrl.isBlank()) {
            showOpenExternalBrowserFailure()
            return false
        }
        return launchExternalBrowser(Uri.parse(targetUrl))
    }

    override fun openUrlInExternalBrowser(url: String): Boolean {
        val targetUrl = url.trim()
        if (targetUrl.isBlank()) {
            showOpenExternalBrowserFailure()
            return false
        }
        return launchExternalBrowser(Uri.parse(targetUrl))
    }

    private fun installDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val targetUri = runCatching { Uri.parse(request.uri.orEmpty()) }.getOrNull()
                    ?: return GeckoResult.allow()
                if (shouldOpenExternally(targetUri)) {
                    recordHostDiagnostic(
                        category = "geckoview",
                        body = "event=external_open source=on_load_request target=${request.target} uri=${normalizeDiagnosticValue(request.uri)}"
                    )
                    return if (launchExternalBrowser(targetUri)) {
                        GeckoResult.deny()
                    } else {
                        GeckoResult.allow()
                    }
                }
                return GeckoResult.allow()
            }

            override fun onSubframeLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? = GeckoResult.allow()

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val targetUri = runCatching { Uri.parse(uri) }.getOrNull()
                if (targetUri != null) {
                    recordHostDiagnostic(
                        category = "geckoview",
                        body = "event=external_open source=on_new_session uri=${normalizeDiagnosticValue(uri)}"
                    )
                    launchExternalBrowser(targetUri)
                }
                @Suppress("UNCHECKED_CAST")
                return GeckoResult.fromValue(null) as GeckoResult<GeckoSession>
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                recordLoadError(uri = uri, error = error)
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                floatingBrowserSurfaceStore.incrementPageLoadCount(geckoView)
                updateCurrentUrl(url)
                homeViewModel.loadedUrl = url
                recordHostDiagnostic(
                    category = "geckoview",
                    body = "event=page_start url=${normalizeDiagnosticValue(url)} ${currentGeckoDiagnosticState()}"
                )
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                homeViewModel.isPullGestureRefreshing = false
                if (success) {
                    homeViewModel.pendingLocalRetryAttempts = 0
                }
                hideInitialPagePaintCover(reason = "page_stop")
                bridgeInstaller.installAfterPageFinished(buildBridgeTarget())
                setBrowserZoomPercent(hostConfigStore.browserZoomPercent)
                setBrowserPageZoomPercent(hostConfigStore.browserPageZoomPercent)
                recordHostDiagnostic(
                    category = "geckoview",
                    body = "event=page_stop success=$success ${currentGeckoDiagnosticState()}"
                )
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                recordHostDiagnostic(
                    category = "geckoview",
                    body = "event=progress_changed progress=$progress ${currentGeckoDiagnosticState()}"
                )
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val url = response.uri.orEmpty()
                val contentType = response.header("content-type")
                val contentDisposition = response.header("content-disposition")
                recordCriticalHostDiagnostic(
                    category = "download",
                    body = "event=geckoview_external_response statusCode=${response.statusCode} uri=${normalizeDiagnosticValue(url)} mime=${normalizeDiagnosticValue(contentType)} contentDisposition=${normalizeDiagnosticValue(contentDisposition)} hasBody=${response.body != null}"
                )
                val body = response.body
                if (url.isBlank() || body == null) {
                    return
                }
                onDownloadRequested(
                    BrowserResponseDownloadRequest(
                        url = url,
                        contentDisposition = contentDisposition,
                        mimeType = contentType.substringBefore(';').trim(),
                        body = body
                    )
                )
            }

            override fun onCrash(session: GeckoSession) {
                recordGeckoRendererGone(didCrash = true, source = "content_delegate_crash")
            }

            override fun onKill(session: GeckoSession) {
                recordGeckoRendererGone(didCrash = false, source = "content_delegate_kill")
            }

            override fun onFirstComposite(session: GeckoSession) {
                recordHostDiagnostic(
                    category = "geckoview",
                    body = "event=first_composite ${currentGeckoDiagnosticState()}"
                )
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                hideInitialPagePaintCover(reason = "first_contentful_paint")
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=android_permission_request action=reject permissions=${permissions.orEmpty().joinToString(separator = ",")}"
                )
                callback.reject()
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                val localOrigin = isLocalTavernUrl(perm.uri) || isLocalTavernUrl(currentUrl)
                val allow = localOrigin && isAllowedLocalContentPermission(perm.permission)
                val decision = if (allow) {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                } else {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
                // GeckoView 不会像 WebView 一样默认接上所有 permission prompt；本地 Tavern 必需能力明确放行，
                // 外部来源和隐私敏感能力仍拒绝，避免实验内核变成全站无限授权。
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=content_permission_request permission=${resolveContentPermissionName(perm.permission)} action=${if (allow) "allow" else "deny"} uri=${normalizeDiagnosticValue(perm.uri)} localOrigin=$localOrigin privateMode=${perm.privateMode} value=${perm.value}"
                )
                return GeckoResult.fromValue(decision)
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=media_permission_request action=reject uri=${normalizeDiagnosticValue(uri)} videoCount=${video?.size ?: 0} audioCount=${audio?.size ?: 0}"
                )
                callback.reject()
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=choice_prompt_requested type=${prompt.type} title=${normalizeDiagnosticValue(prompt.title)} message=${normalizeDiagnosticValue(prompt.message)} choiceCount=${prompt.choices.size}"
                )
                return showChoicePrompt(prompt)
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=auth_prompt_requested title=${normalizeDiagnosticValue(prompt.title)} uri=${normalizeDiagnosticValue(prompt.authOptions.uri)}"
                )
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                httpAuthPromptController.show(
                    HttpAuthPromptRequest(
                        source = "geckoview",
                        host = prompt.authOptions.uri,
                        realm = prompt.title ?: prompt.message,
                        initialUsername = prompt.authOptions.username,
                        initialPassword = prompt.authOptions.password,
                        onConfirm = { credentials ->
                            result.complete(prompt.confirm(credentials.username, credentials.password))
                        },
                        onCancel = {
                            result.complete(prompt.dismiss())
                        }
                    )
                )
                return result
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=file_prompt_requested type=${prompt.type} capture=${prompt.capture} mimeTypes=${prompt.mimeTypes.orEmpty().joinToString(separator = ",")}"
                )
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                try {
                    onShowFileChooser(buildFileChooserRequest(prompt)) { selectedUris ->
                        scope.launch {
                            result.complete(completeFilePrompt(prompt = prompt, selectedUris = selectedUris))
                        }
                    }
                } catch (error: Exception) {
                    recordCriticalHostDiagnostic(
                        category = "geckoview",
                        body = "event=file_prompt_launch_failed error=${error.javaClass.simpleName} message=${normalizeDiagnosticValue(error.message)}"
                    )
                    result.complete(prompt.dismiss())
                }
                return result
            }
        }
    }

    private suspend fun completeFilePrompt(
        prompt: GeckoSession.PromptDelegate.FilePrompt,
        selectedUris: Array<Uri>?
    ): GeckoSession.PromptDelegate.PromptResponse {
        val acceptedUris = when {
            selectedUris == null -> null
            prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE -> selectedUris
            selectedUris.isNotEmpty() -> arrayOf(selectedUris.first())
            else -> emptyArray()
        }
        val preparedUris = runCatching {
            when {
                acceptedUris == null || acceptedUris.isEmpty() -> acceptedUris
                else -> withContext(ioDispatcher) {
                    filePromptUriMaterializer.materialize(acceptedUris).uris
                }
            }
        }.onSuccess { prepared ->
            if (acceptedUris != null && prepared != null) {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=file_prompt_uris_materialized selectedCount=${acceptedUris.size} preparedCount=${prepared.size}"
                )
            }
        }.getOrElse { error ->
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=file_prompt_uri_materialize_failed error=${error.javaClass.simpleName} message=${normalizeDiagnosticValue(error.message)} selectedCount=${acceptedUris?.size ?: 0}"
            )
            null
        }
        val response = runCatching {
            when {
                preparedUris == null -> prompt.dismiss()
                prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE -> {
                    if (preparedUris.isEmpty()) {
                        prompt.dismiss()
                    } else {
                        prompt.confirm(activity, preparedUris)
                    }
                }
                preparedUris.isNotEmpty() -> prompt.confirm(activity, preparedUris.first())
                else -> prompt.dismiss()
            }
        }.getOrElse { error ->
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=file_prompt_response_failed error=${error.javaClass.simpleName} message=${normalizeDiagnosticValue(error.message)} selectedCount=${preparedUris?.size ?: 0}"
            )
            prompt.dismiss()
        }
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = "event=file_prompt_completed selectedCount=${preparedUris?.size ?: 0}"
        )
        return response
    }

    private fun isAllowedLocalContentPermission(permission: Int): Boolean {
        return when (permission) {
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION,
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS,
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> true
            else -> false
        }
    }

    private fun showChoicePrompt(
        prompt: GeckoSession.PromptDelegate.ChoicePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        var completed = false
        fun complete(response: GeckoSession.PromptDelegate.PromptResponse) {
            if (completed) return
            completed = true
            result.complete(response)
        }

        val entries = buildChoicePromptEntries(prompt.choices)
        val selectableEntries = entries.filter { entry -> entry.selectable }
        if (selectableEntries.isEmpty()) {
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=choice_prompt_empty action=dismiss type=${prompt.type}"
            )
            complete(prompt.dismiss())
            return result
        }

        runCatching {
            activity.runOnUiThread {
                runCatching {
                    if (prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                        showMultipleChoicePrompt(prompt, selectableEntries, ::complete)
                    } else {
                        showSingleChoicePrompt(prompt, entries, ::complete)
                    }
                }.onFailure { error ->
                    recordCriticalHostDiagnostic(
                        category = "geckoview",
                        body = "event=choice_prompt_show_failed error=${error.javaClass.simpleName} message=${normalizeDiagnosticValue(error.message)} type=${prompt.type}"
                    )
                    complete(prompt.dismiss())
                }
            }
        }.onFailure { error ->
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=choice_prompt_post_failed error=${error.javaClass.simpleName} message=${normalizeDiagnosticValue(error.message)} type=${prompt.type}"
            )
            complete(prompt.dismiss())
        }
        return result
    }

    private fun showSingleChoicePrompt(
        prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        entries: List<GeckoChoicePromptEntry>,
        complete: (GeckoSession.PromptDelegate.PromptResponse) -> Unit
    ) {
        val checkedItem = entries.indexOfFirst { entry -> entry.selectable && entry.choice.selected }
        MaterialAlertDialogBuilder(activity)
            .setTitle(resolveChoicePromptTitle(prompt))
            .setSingleChoiceItems(
                GeckoChoicePromptAdapter(activity, entries),
                checkedItem
            ) { dialog, which ->
                val entry = entries.getOrNull(which) ?: return@setSingleChoiceItems
                if (!entry.selectable) {
                    return@setSingleChoiceItems
                }
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=choice_prompt_selected type=${prompt.type} index=$which label=${normalizeDiagnosticValue(entry.label)}"
                )
                complete(prompt.confirm(entry.choice))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=choice_prompt_cancelled type=${prompt.type}"
                )
                complete(prompt.dismiss())
            }
            .setOnCancelListener {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=choice_prompt_cancelled type=${prompt.type}"
                )
                complete(prompt.dismiss())
            }
            .show()
    }

    private fun showMultipleChoicePrompt(
        prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        selectableEntries: List<GeckoChoicePromptEntry>,
        complete: (GeckoSession.PromptDelegate.PromptResponse) -> Unit
    ) {
        val checkedItems = BooleanArray(selectableEntries.size) { index ->
            selectableEntries[index].choice.selected
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(resolveChoicePromptTitle(prompt))
            .setMultiChoiceItems(
                selectableEntries.map { entry -> entry.label }.toTypedArray(),
                checkedItems
            ) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedChoices = selectableEntries
                    .filterIndexed { index, _ -> checkedItems[index] }
                    .map { entry -> entry.choice }
                    .toTypedArray()
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=choice_prompt_selected type=${prompt.type} selectedCount=${selectedChoices.size}"
                )
                complete(prompt.confirm(selectedChoices))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=choice_prompt_cancelled type=${prompt.type}"
                )
                complete(prompt.dismiss())
            }
            .setOnCancelListener {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=choice_prompt_cancelled type=${prompt.type}"
                )
                complete(prompt.dismiss())
            }
            .show()
    }

    private fun resolveChoicePromptTitle(prompt: GeckoSession.PromptDelegate.ChoicePrompt): String {
        return prompt.title
            ?.trim()
            ?.takeIf { title -> title.isNotEmpty() }
            ?: prompt.message
                ?.trim()
                ?.takeIf { message -> message.isNotEmpty() }
            ?: activity.getString(R.string.geckoview_choice_prompt_title)
    }

    private fun buildChoicePromptEntries(
        choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
        parentLabel: String = "",
        parentDisabled: Boolean = false
    ): List<GeckoChoicePromptEntry> {
        val entries = mutableListOf<GeckoChoicePromptEntry>()
        choices.forEachIndexed { index, choice ->
            val label = choice.label
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: choice.id
                    ?.trim()
                    ?.takeIf { value -> value.isNotEmpty() }
                ?: "#${index + 1}"
            val groupLabel = listOf(parentLabel, label)
                .filter { value -> value.isNotBlank() }
                .joinToString(separator = " / ")
            val childItems = choice.items?.toList().orEmpty()
            if (childItems.isNotEmpty()) {
                entries += buildChoicePromptEntries(
                    choices = childItems.toTypedArray(),
                    parentLabel = groupLabel,
                    parentDisabled = parentDisabled || choice.disabled
                )
            } else if (!choice.separator) {
                entries += GeckoChoicePromptEntry(
                    choice = choice,
                    label = groupLabel,
                    selectable = !parentDisabled && !choice.disabled
                )
            }
        }
        return entries
    }

    private fun resolveContentPermissionName(permission: Int): String {
        return when (permission) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "GEOLOCATION"
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "DESKTOP_NOTIFICATION"
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "PERSISTENT_STORAGE"
            GeckoSession.PermissionDelegate.PERMISSION_XR -> "XR"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "AUTOPLAY_INAUDIBLE"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> "AUTOPLAY_AUDIBLE"
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "MEDIA_KEY_SYSTEM_ACCESS"
            GeckoSession.PermissionDelegate.PERMISSION_TRACKING -> "TRACKING"
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "STORAGE_ACCESS"
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> "LOCAL_DEVICE_ACCESS"
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "LOCAL_NETWORK_ACCESS"
            else -> "UNKNOWN_$permission"
        }
    }

    private fun buildFileChooserRequest(
        prompt: GeckoSession.PromptDelegate.FilePrompt
    ): HostIoController.BrowserFileChooserRequest {
        val acceptTokens = prompt.mimeTypes
            ?.map { mimeType -> mimeType.trim() }
            ?.filter { mimeType -> mimeType.isNotEmpty() }
            ?.takeIf { values -> values.isNotEmpty() }
            ?.toTypedArray()
            ?: arrayOf("*/*")
        val chooserMimeTypes = BrowserFileChooserSelectionPolicy.resolveAndroidIntentMimeTypes(acceptTokens)
        val acceptTypes = BrowserFileChooserSelectionPolicy.expandGeckoPromptAcceptTypes(acceptTokens)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
            )
            if (chooserMimeTypes.size == 1) {
                type = chooserMimeTypes.single()
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, chooserMimeTypes)
            }
        }
        return HostIoController.BrowserFileChooserRequest(
            intent = intent,
            acceptTypes = acceptTypes,
            allowMultiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
            forceAcceptTokenSelectionFilter = acceptTypes.any { token -> token.startsWith(".") }
        )
    }

    private data class GeckoChoicePromptEntry(
        val choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice,
        val label: String,
        val selectable: Boolean
    )

    private class GeckoChoicePromptAdapter(
        context: Context,
        private val entries: List<GeckoChoicePromptEntry>
    ) : ArrayAdapter<GeckoChoicePromptEntry>(
        context,
        android.R.layout.simple_list_item_single_choice,
        entries
    ) {
        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean {
            return entries.getOrNull(position)?.selectable == true
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val entry = entries[position]
            (view as? TextView)?.text = entry.label
            view.isEnabled = entry.selectable
            view.alpha = if (entry.selectable) 1f else 0.42f
            return view
        }
    }

    private fun recordLoadError(uri: String?, error: WebRequestError) {
        val safeUri = uri.orEmpty()
        val snapshot = processManager.currentSnapshot()
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = buildString {
                append("event=load_error")
                append(" uri=${normalizeDiagnosticValue(safeUri)}")
                append(" errorCode=${error.code}")
                append(" errorCategory=${error.category}")
                append(" serverReady=${snapshot.isReady}")
                append(" serverLifecycle=${snapshot.lifecycle}")
                append(" snapshotLocalUrl=${normalizeDiagnosticValue(snapshot.localUrl)}")
                append(' ')
                append(currentNetworkDiagnosticState())
                append(' ')
                append(currentGeckoDiagnosticState())
                append(' ')
                append(currentHostMemoryDiagnosticState())
            }
        )
    }

    private fun recordGeckoRendererGone(didCrash: Boolean, source: String) {
        if (browserSurfaceDestroyed) {
            return
        }
        val recoveryUrl = currentUrl
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = "event=renderer_gone source=$source didCrash=$didCrash ${currentGeckoDiagnosticState()} ${currentHostMemoryDiagnosticState()}"
        )
        homeViewModel.isPullGestureRefreshing = false
        if (recoveryUrl.isNotBlank()) {
            homeViewModel.loadedUrl = recoveryUrl
        }
        releaseGeckoActivityBindings(reason = "renderer_gone", closeProcessBridge = true)
        floatingBrowserSurfaceStore.destroyGeckoIfSame(geckoView = geckoView, session = session)
        browserSurfaceDestroyed = true
        if (!activity.isFinishing && !activity.isDestroyed) {
            browserFrame.post { activity.recreate() }
        }
    }

    private fun buildBridgeTarget(): BrowserHostBridgeTarget {
        return BrowserHostBridgeTarget(
            browserEngine = browserEngine,
            surface = geckoView,
            allowedOrigin = runtimeConfigRepository.localServiceUrl(),
            geckoRuntime = runtime(activity),
            geckoSession = session
        )
    }

    private fun shouldOpenExternally(targetUri: Uri): Boolean {
        if (isGeckoInternalOrPageLocalScheme(targetUri.scheme.orEmpty())) {
            return false
        }
        return !isLocalTavernUri(targetUri)
    }

    private fun isGeckoInternalOrPageLocalScheme(scheme: String): Boolean {
        return when (scheme.lowercase()) {
            // WebExtension、about 页面和 blob/data/javascript 都属于当前 GeckoSession 内部处理范围；
            // 误交给外部浏览器会打断宿主桥接注入和 Tavern 的页面内导出流程。
            "about",
            "moz-extension",
            "resource",
            "chrome",
            "blob",
            "data",
            "javascript" -> true
            else -> false
        }
    }

    private fun isLocalTavernUri(targetUri: Uri): Boolean {
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

    private fun isLocalTavernUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return isLocalTavernUri(parsed)
    }

    private fun isCurrentPageFor(baseUrl: String): Boolean {
        return hasLoadedCurrentWebViewPageForBaseUrl(
            currentWebViewUrl = currentUrl,
            baseUrl = baseUrl
        )
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

    private fun forceFreshGeckoViewLoad(baseUrl: String) {
        val targetUrl = buildInitialTavernUrl(baseUrl)
        val requestedClearMask = homeViewModel.browserDataClearMask
        val clearMask = if (requestedClearMask == 0) {
            BrowserDataClearOptions.fullMask
        } else {
            BrowserDataClearOptions.normalizeOrDefault(requestedClearMask)
        }
        homeViewModel.shouldForceFreshWebViewLoad = false
        homeViewModel.browserDataClearMask = 0
        homeViewModel.pendingLocalRetryAttempts = 0
        updateCurrentUrl("")
        homeViewModel.loadedUrl = targetUrl

        // Gecko 的 IndexedDB / LocalStorage / Cache 不归 Android WebStorage 管，必须走 GeckoRuntime StorageController。
        recordCriticalHostDiagnostic(
            category = "geckoview",
            body = "event=force_fresh_geckoview_load targetUrl=${normalizeDiagnosticValue(targetUrl)} action=clear_selected_site_state_and_load clearMask=$clearMask clearFlags=${resolveGeckoClearFlags(clearMask)} ${currentGeckoDiagnosticState()}"
        )
        clearCurrentGeckoPageSessionState(clearMask)
        clearPersistedGeckoSiteState(clearMask = clearMask, targetUrl = targetUrl) {
            bridgeInstaller.install(buildBridgeTarget()) {
                recordCriticalHostDiagnostic(
                    category = "geckoview",
                    body = "event=force_fresh_geckoview_load_completed targetUrl=${normalizeDiagnosticValue(targetUrl)} ${currentGeckoDiagnosticState()}"
                )
                loadTavernUri(targetUrl, replaceHistory = true, bypassCache = true)
            }
        }
    }

    private fun showInitialPagePaintCover(reason: String) {
        if (waitingForInitialPagePaint) {
            return
        }
        waitingForInitialPagePaint = true
        // GeckoView 在页面内容绘制前可能短暂露出白色 compositor surface；保留深色启动遮罩挡住空白帧。
        bootstrapOverlay.isVisible = true
        bootstrapOverlay.bringToFront()
        recordHostDiagnostic(
            category = "geckoview",
            body = "event=initial_paint_cover_show reason=$reason ${currentGeckoDiagnosticState()}"
        )
    }

    /** 只根据进程级 GeckoSession 与 GeckoView 的真实状态判断迁移资格。 */
    override fun canAttachToFloatingBrowser(): Boolean {
        // Activity 可能在 overlay 期间销毁；迁移资格只由进程级 Session 和 View 状态决定。
        return retainedGeckoSurface.canAttachToOverlay()
    }

    /** 把同一 GeckoView/Session 移入 overlay，保持会话 active 且不调用 loadUri。 */
    override fun attachToFloatingBrowser(container: ViewGroup): Boolean {
        // GeckoView 的真实页面状态由当前 GeckoSession 持有；迁移 View 时保持 session active 且不调用 loadUri。
        val attached = retainedGeckoSurface.attachToOverlay(container)
        if (attached) {
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=floating_browser_attached session=$browserSessionIdentity currentUrl=${normalizeDiagnosticValue(currentUrl)} pageLoadCount=${retainedGeckoSurface.pageLoadCount}"
            )
        }
        return attached
    }

    /** overlay 真正可绘制后通过内置扩展记录 Gecko 文档可见性，不读取页面内容。 */
    override fun onFloatingBrowserWindowVisible() {
        val requested = bridgeInstaller.requestDocumentVisibilityDiagnostic("floating_browser_window_visible")
        if (!requested) {
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=document_visibility_request_unavailable reason=floating_browser_window_visible session=$browserSessionIdentity pageLoadCount=${retainedGeckoSurface.pageLoadCount}"
            )
        }
    }

    /** 把同一 GeckoView/Session 恢复到仍存活的 Activity 容器。 */
    override fun attachToActivityBrowser(): Boolean {
        val attached = retainedGeckoSurface.restoreToActivity()
        if (attached) {
            browserFrame.isVisible = true
            geckoView.isVisible = true
            session.setActive(true)
            session.setFocused(true)
            updateRefreshLayoutEnabled()
            recordCriticalHostDiagnostic(
                category = "geckoview",
                body = "event=floating_browser_restored session=$browserSessionIdentity currentUrl=${normalizeDiagnosticValue(currentUrl)} pageLoadCount=${retainedGeckoSurface.pageLoadCount}"
            )
        }
        return attached
    }

    private fun hideInitialPagePaintCover(reason: String) {
        if (!waitingForInitialPagePaint) {
            return
        }
        waitingForInitialPagePaint = false
        bootstrapOverlay.isVisible = false
        updateRefreshLayoutEnabled()
        recordHostDiagnostic(
            category = "geckoview",
            body = "event=initial_paint_cover_hide reason=$reason ${currentGeckoDiagnosticState()}"
        )
    }

    private fun clearCurrentGeckoPageSessionState(clearMask: Int) {
        if (BrowserDataClearOptions.contains(clearMask, BrowserDataClearTarget.HISTORY_AND_FORM_DATA)) {
            runCatching { session.purgeHistory() }
                .onFailure { error ->
                    recordHostDiagnostic(
                        category = "geckoview",
                        body = "event=gecko_session_history_clear_failed error=${normalizeDiagnosticValue(error.message ?: error.javaClass.simpleName)}"
                    )
                }
        }
    }

    private fun clearPersistedGeckoSiteState(
        clearMask: Int,
        targetUrl: String,
        onComplete: () -> Unit
    ) {
        val clearFlags = resolveGeckoClearFlags(clearMask)
        if (clearFlags == 0L) {
            onComplete()
            return
        }
        val host = runCatching { Uri.parse(targetUrl).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) {
            recordHostDiagnostic(
                category = "geckoview",
                body = "event=gecko_site_data_clear_skipped reason=blank_host clearFlags=$clearFlags targetUrl=${normalizeDiagnosticValue(targetUrl)}"
            )
            onComplete()
            return
        }
        runtime(activity).storageController
            .clearDataFromHost(host, clearFlags)
            .accept(
                {
                    recordCriticalHostDiagnostic(
                        category = "geckoview",
                        body = "event=gecko_site_data_cleared host=$host clearFlags=$clearFlags"
                    )
                    onComplete()
                },
                { error ->
                    recordCriticalHostDiagnostic(
                        category = "geckoview",
                        body = "event=gecko_site_data_clear_failed host=$host clearFlags=$clearFlags error=${normalizeDiagnosticValue(error?.message ?: error?.javaClass?.simpleName ?: "unknown")}"
                    )
                    onComplete()
                }
            )
    }

    private fun resolveGeckoClearFlags(clearMask: Int): Long {
        var flags = 0L
        if (BrowserDataClearOptions.contains(clearMask, BrowserDataClearTarget.RESOURCE_CACHE)) {
            flags = flags or StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE
        }
        if (BrowserDataClearOptions.contains(clearMask, BrowserDataClearTarget.SITE_STORAGE)) {
            flags = flags or StorageController.ClearFlags.DOM_STORAGES
        }
        if (BrowserDataClearOptions.contains(clearMask, BrowserDataClearTarget.COOKIES)) {
            flags = flags or StorageController.ClearFlags.COOKIES
        }
        if (BrowserDataClearOptions.contains(clearMask, BrowserDataClearTarget.HISTORY_AND_FORM_DATA)) {
            flags = flags or StorageController.ClearFlags.AUTH_SESSIONS
        }
        return flags
    }

    private fun loadTavernUri(
        targetUrl: String,
        replaceHistory: Boolean = false,
        bypassCache: Boolean = false
    ) {
        val flags = buildGeckoLoadFlags(replaceHistory = replaceHistory, bypassCache = bypassCache)
        if (flags == GeckoSession.LOAD_FLAGS_NONE) {
            session.loadUri(targetUrl)
            return
        }
        session.load(
            GeckoSession.Loader()
                .uri(targetUrl)
                .flags(flags)
        )
    }

    /** 同步 Gecko 当前 URL 到进程级 record，避免 Activity 重建后误触发 loadUri。 */
    private fun updateCurrentUrl(url: String) {
        currentUrl = url
        retainedGeckoSurface.currentUrl = url
    }

    private fun buildGeckoLoadFlags(replaceHistory: Boolean, bypassCache: Boolean): Int {
        var flags = GeckoSession.LOAD_FLAGS_NONE
        if (replaceHistory) {
            flags = flags or GeckoSession.LOAD_FLAGS_REPLACE_HISTORY
        }
        if (bypassCache) {
            flags = flags or GeckoSession.LOAD_FLAGS_BYPASS_CACHE
        }
        return flags
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

    private fun WebResponse.header(name: String): String {
        return headers?.entries
            ?.firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
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

    private fun currentGeckoDiagnosticState(): String {
        return buildString {
            append("engine=GECKOVIEW")
            append(" currentUrl=${normalizeDiagnosticValue(currentUrl)}")
            append(" pageLoadCount=${retainedGeckoSurface.pageLoadCount}")
            append(" rememberedUrl=${normalizeDiagnosticValue(homeViewModel.loadedUrl)}")
            append(" localBaseUrl=${normalizeDiagnosticValue(buildInitialTavernUrl(runtimeConfigRepository.localServiceUrl()))}")
            append(" retryAttempts=${homeViewModel.pendingLocalRetryAttempts}")
            append(" geckoVisible=${geckoView.isVisible}")
            append(" browserContainerVisible=${browserFrame.isVisible}")
            append(" browserContainerEnabled=${browserFrame.isEnabled}")
            append(" overlayVisible=${bootstrapOverlay.isVisible}")
            append(" sessionOpen=${session.isOpen}")
            append(" activityFinishing=${activity.isFinishing}")
            append(" activityDestroyed=${activity.isDestroyed}")
            append(" geckoHardwareAccelerated=${geckoView.isHardwareAccelerated}")
            append(" geckoLayerType=${resolveViewLayerTypeName(geckoView.layerType)}")
            append(" geckoViewportMode=${session.settings.viewportMode}")
            append(" geckoUserAgentMode=${session.settings.userAgentMode}")
            append(" geckoUserAgent=${normalizeDiagnosticValue(GeckoBuildConfig.USER_AGENT_GECKOVIEW_MOBILE)}")
            append(" geckoVersion=${normalizeDiagnosticValue(GeckoBuildConfig.MOZILLA_VERSION)}")
            append(" geckoMilestone=${normalizeDiagnosticValue(GeckoBuildConfig.GRE_MILESTONE)}")
            append(" geckoJavaScriptEnabled=${runtime(activity).settings.javaScriptEnabled}")
            append(" geckoSessionJavaScriptAllowed=${session.settings.allowJavascript}")
            append(" geckoAllowInsecureConnections=${runtime(activity).settings.allowInsecureConnections}")
            append(" geckoWebFontsEnabled=${runtime(activity).settings.webFontsEnabled}")
            append(" geckoWebManifestEnabled=${runtime(activity).settings.webManifestEnabled}")
            append(" geckoExtensionsWebAPIEnabled=${runtime(activity).settings.extensionsWebAPIEnabled}")
            append(" geckoConsoleOutputEnabled=${runtime(activity).settings.consoleOutputEnabled}")
            append(" geckoDebugLoggingRequested=${isAppDebuggable()}")
            append(" geckoRemoteDebuggingEnabled=${runtime(activity).settings.remoteDebuggingEnabled}")
            append(" geckoAboutConfigEnabled=${runtime(activity).settings.aboutConfigEnabled}")
            append(" geckoLowMemoryDetection=${runtime(activity).settings.lowMemoryDetection}")
            append(" geckoUseMaxScreenDepth=${runtime(activity).settings.useMaxScreenDepth}")
            append(" geckoGlMsaaLevel=${runtime(activity).settings.glMsaaLevel}")
            append(" geckoDisplayDensityOverride=${runtime(activity).settings.displayDensityOverride ?: "-"}")
            append(" geckoDisplayDpiOverride=${runtime(activity).settings.displayDpiOverride ?: "-"}")
            append(" geckoScreenSizeOverride=${normalizeDiagnosticValue(runtime(activity).settings.screenSizeOverride?.toShortString())}")
            append(" geckoInputAutoZoomEnabled=${runtime(activity).settings.inputAutoZoomEnabled}")
            append(" geckoDoubleTapZoomingEnabled=${runtime(activity).settings.doubleTapZoomingEnabled}")
            append(" geckoForceUserScalableEnabled=${runtime(activity).settings.forceUserScalableEnabled}")
            append(" configuredBrowserZoomPercent=${BrowserZoomOptions.sanitize(hostConfigStore.browserZoomPercent)}")
            append(" geckoFontSizeFactor=${runtime(activity).settings.fontSizeFactor}")
            append(" geckoFontInflation=${runtime(activity).settings.fontInflationEnabled}")
            append(" geckoAutomaticFontSizeAdjustment=${runtime(activity).settings.automaticFontSizeAdjustment}")
            append(" geckoSessionTrackingProtection=${session.settings.useTrackingProtection}")
            append(" geckoSessionDisplayMode=${session.settings.displayMode}")
            append(" geckoSessionSuspendMediaWhenInactive=${session.settings.suspendMediaWhenInactive}")
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
            append(" geckoWidth=${geckoView.width}")
            append(" geckoHeight=${geckoView.height}")
            append(" geckoLayerType=${resolveViewLayerTypeName(geckoView.layerType)}")
        }
    }

    private fun isAppDebuggable(): Boolean {
        return activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private fun resolveViewLayerTypeName(layerType: Int): String {
        return when (layerType) {
            View.LAYER_TYPE_NONE -> "LAYER_TYPE_NONE"
            View.LAYER_TYPE_SOFTWARE -> "LAYER_TYPE_SOFTWARE"
            View.LAYER_TYPE_HARDWARE -> "LAYER_TYPE_HARDWARE"
            else -> "UNKNOWN"
        }
    }

    private fun recordHostDiagnostic(category: String, body: String) {
        runCatching { hostDiagnosticSink.record(category, body) }
    }

    private fun recordCriticalHostDiagnostic(category: String, body: String) {
        runCatching { criticalHostDiagnosticSink.record(category, body) }
    }
}
