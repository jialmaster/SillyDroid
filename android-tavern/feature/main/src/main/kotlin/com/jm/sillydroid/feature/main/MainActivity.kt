package com.jm.sillydroid.feature.main

import android.app.ActivityManager
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jm.sillydroid.core.model.logs.HostLogBundleUploadRequestConfig
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.BrowserZoomOptions
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.ui.window.SystemBarAppearanceController
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.feature.main.diagnostics.formatTrimMemoryLevel
import com.jm.sillydroid.feature.main.ui.extensions.DefaultExtensionsInstallerLauncher
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeActions
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeInstaller
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeInstallerFactory
import com.jm.sillydroid.feature.main.ui.home.bootstrap.BootstrapOverlayHost
import com.jm.sillydroid.feature.main.ui.home.floatinglogs.FloatingLogsHost
import com.jm.sillydroid.feature.main.ui.home.io.HostIoController
import com.jm.sillydroid.feature.main.ui.home.system.SystemBarInsetsController
import com.jm.sillydroid.feature.main.ui.home.system.resolveMainHostDisplayMode
import com.jm.sillydroid.feature.main.ui.home.webview.DisabledBrowserHost
import com.jm.sillydroid.feature.main.ui.home.webview.HostDiagnosticSink
import com.jm.sillydroid.feature.main.ui.home.webview.TavernBrowserHost
import com.jm.sillydroid.feature.main.ui.home.webview.TavernGeckoViewHost
import com.jm.sillydroid.feature.main.ui.home.webview.TavernWebViewHost
import com.jm.sillydroid.feature.main.ui.home.webview.UnavailableBrowserHost
import com.jm.sillydroid.feature.main.ui.home.webview.WebViewRendererGoneInfo
import com.jm.sillydroid.feature.main.ui.home.webview.WebViewRuntimeCompatibility
import com.jm.sillydroid.feature.main.ui.home.webview.shouldAutoUploadRendererGoneBundle
import com.jm.sillydroid.feature.main.ui.home.webview.toDiagnosticText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MainActivity 现在只做"装配"：构造五个 host（FloatingLogs / BootstrapOverlay / TavernBrowser /
 * HostIo / SystemBarInsets），把它们之间的回调线连起来，剩下的视图字段、状态、controller、
 * launcher 全在各自 host 内部。
 *
 * 跨 host 的关键回调：
 *  - 浏览器内核桥接由 BrowserHostBridgeInstaller 拼装：blob 下载（HostIo）、
 *    系统通知（HostIo）、宿主能力桥（含 FloatingLogs / BootstrapOverlay）。
 *  - SystemBarInsets 的 IME 变化通知 TavernBrowser 暂停下拉刷新。
 *  - SystemBarInsets 的 bounds 变化通知 FloatingLogs 重新计算可视范围。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var contentRoot: android.view.View
    private lateinit var statusBarBackground: android.view.View
    private lateinit var navigationBarBackground: android.view.View
    private lateinit var backPressCallback: OnBackPressedCallback

    private val appGraph: SillyDroidAppGraph
        get() = (application as SillyDroidAppGraphProvider).sillyDroidAppGraph
    private val hostConfigStore by lazy { appGraph.hostConfigStore }
    private val hostLogRepository by lazy { appGraph.hostLogRepository }
    private val processManager by lazy<BootstrapController> { appGraph.bootstrapController }
    private val runtimeConfigRepository by lazy { appGraph.runtimeConfigRepository }
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.Factory(processManager) }

    private lateinit var hostIo: HostIoController
    private lateinit var floatingLogsHost: FloatingLogsHost
    private lateinit var browserHost: TavernBrowserHost
    private lateinit var bootstrapOverlayHost: BootstrapOverlayHost
    private lateinit var systemBarInsetsController: SystemBarInsetsController
    private var lastWebViewSystemBarsColorHex: String? = null
    private var lastWebViewStatusBarColorHex: String? = null
    private var lastWebViewNavigationBarColorHex: String? = null

    private val feedbackImageLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (::floatingLogsHost.isInitialized) {
            floatingLogsHost.onFeedbackImagesSelected(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordActivityLifecycleDiagnostic(
            event = "on_create",
            extra = "savedStatePresent=${savedInstanceState != null} savedStateKeys=${savedInstanceState?.keySet()?.sorted()?.joinToString(separator = "|").orEmpty()}"
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        allowMainContentIntoDisplayCutout()
        setContentView(R.layout.activity_main)
        contentRoot = findViewById(R.id.contentRoot)
        statusBarBackground = findViewById(R.id.statusBarBackground)
        navigationBarBackground = findViewById(R.id.navigationBarBackground)
        // 主界面刚启动时先显示 bootstrap overlay；系统栏先跟宿主遮罩底色走，
        // 等 WebView 页面拿到真实背景色后再由页面桥持续同步过去。
        applyHostSurfaceSystemBars()

        composeHosts()
        bootstrapOverlayHost.installAppUpdateCoordinator()
        installSystemUi()
        installBrowserStack()
        installBootstrapWiring()
        if (shouldUseWebViewSurface()) {
            inspectWebViewRuntimeBeforeBootstrap()
        }
        bootstrapOverlayHost.startBootstrap(false)
        if (!maybePromptCrashLogUploadConsent()) {
            maybeShowWebViewRendererCrashBrowserEngineHint()
        }
    }

    private fun composeHosts() {
        hostIo = HostIoController(
            activity = this,
            runtimeConfigRepository = runtimeConfigRepository,
            hostPreferencesRepository = hostConfigStore,
            hostNotificationService = appGraph.hostNotificationService,
            hostDownloadNotificationCoordinator = appGraph.hostDownloadNotificationCoordinator,
            scope = lifecycleScope,
            dispatchers = appGraph.dispatchers,
            downloadDiagnosticSink = { body ->
                recordDetailedHostDiagnostic(category = "download", body = body)
            },
            hostDiagnosticSink = { category, body ->
                recordDefaultHostDiagnostic(category = category, body = body)
            }
        )
        floatingLogsHost = FloatingLogsHost(
            activity = this,
            contentRoot = contentRoot,
            dispatchers = appGraph.dispatchers,
            preferences = hostConfigStore,
            logRepository = hostLogRepository,
            currentSnapshot = { processManager.currentSnapshot() },
            canOpenSettings = { snapshot -> bootstrapOverlayHost.canOpenBootstrapSettings(snapshot) },
            openSettings = { bootstrapOverlayHost.openBootstrapSettings() },
            openCurrentPageInBrowser = { browserHost.openCurrentPageInExternalBrowser() },
            reloadTavernWebView = { browserHost.reloadTavernWebView(source = "floating_logs_button") },
            applyBrowserZoomPercent = ::applyBrowserZoomPercentFromFloatingLogs,
            feedbackImageLauncher = feedbackImageLauncher,
            feedbackUploadConfig = {
                HostLogBundleUploadRequestConfig(
                    uploadUrl = appGraph.appUpdateBuildConfig.crashLogUploadUrl,
                    writerApiKey = appGraph.appUpdateBuildConfig.crashLogUploadWriterApiKey,
                    source = "floating-log-feedback",
                    crashType = "user-feedback"
                )
            },
            recordHostDiagnostic = ::recordDefaultHostDiagnostic
        )
        browserHost = createBrowserHost()
        bootstrapOverlayHost = BootstrapOverlayHost(
            activity = this,
            homeViewModel = homeViewModel,
            processManager = processManager,
            appGraph = appGraph,
            browserHost = browserHost,
            floatingLogsHost = floatingLogsHost,
            onMaybePromptDefaultExtensionsAfterBootstrapReady = ::maybePromptDefaultExtensionsAfterBootstrapReady,
            recreateMainActivityForBrowserEngineChange = {
                recordDefaultHostDiagnostic(
                    category = "browser",
                    body = "event=browser_engine_change_recreate_requested currentEngine=${browserHost.browserEngine.name} configuredEngine=${hostConfigStore.browserEngine.name}"
                )
                recreate()
            }
        )
        systemBarInsetsController = SystemBarInsetsController(
            contentRoot = contentRoot,
            statusBarBackground = statusBarBackground,
            navigationBarBackground = navigationBarBackground,
            homeViewModel = homeViewModel,
            displayModeProvider = { effectiveMainDisplayMode() },
            onImeChanged = { visible -> browserHost.onImeVisibilityChanged(visible) },
            onContentBoundsChanged = { floatingLogsHost.onContentBoundsChanged() }
        )
    }

    private fun installSystemUi() {
        systemBarInsetsController.install()
        floatingLogsHost.configure()
        floatingLogsHost.refreshVisibility()
        hostIo.ensureNotificationChannel()
        hostIo.requestNotificationPermissionIfNeeded()
    }

    private fun installBrowserStack() {
        if (!shouldUseWebViewSurface()) {
            // 纯后台模式只启动本地 Tavern 服务；不配置也不加载宿主浏览器页面。
            registerBackPressHandler()
            return
        }
        browserHost.configure()
        registerBackPressHandler()
    }

    private fun installBootstrapWiring() {
        bootstrapOverlayHost.observe()
        bootstrapOverlayHost.bindButtons()
    }

    override fun onResume() {
        super.onResume()
        reapplyCurrentSystemBars()
        floatingLogsHost.refreshVisibility()
        if (shouldUseWebViewSurface()) {
            browserHost.updateRefreshLayoutEnabled()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        recordActivityLifecycleDiagnostic(
            event = "on_save_instance_state",
            extra = "webViewSurface=${shouldUseWebViewSurface()} webViewStatePersistence=disabled"
        )
    }

    override fun onDestroy() {
        recordActivityLifecycleDiagnostic(
            event = "on_destroy",
            extra = "browserHostInitialized=${::browserHost.isInitialized} changingConfigMask=$changingConfigurations"
        )
        if (::browserHost.isInitialized) {
            browserHost.onDestroy()
        }
        hostIo.cancelPendingFileChooser()
        hostIo.blobDownloadController.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        recordActivityLifecycleDiagnostic(
            event = "on_trim_memory",
            extra = "level=${formatTrimMemoryLevel(level)} rawLevel=$level"
        )
        if (::browserHost.isInitialized) {
            browserHost.onTrimMemory(level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        recordActivityLifecycleDiagnostic(event = "on_low_memory")
        if (::browserHost.isInitialized) {
            browserHost.onLowMemory()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转屏幕只刷新宿主安全区与悬浮控件边界，避免销毁 MainActivity 后重建 WebView 导致酒馆页面重新加载。
        recordActivityLifecycleDiagnostic(
            event = "on_configuration_changed",
            extra = "orientation=${newConfig.orientation} screenLayout=${newConfig.screenLayout}"
        )
        if (::systemBarInsetsController.isInitialized) {
            systemBarInsetsController.refresh()
        }
        if (::floatingLogsHost.isInitialized) {
            floatingLogsHost.onContentBoundsChanged()
        }
        if (::browserHost.isInitialized) {
            browserHost.updateRefreshLayoutEnabled()
        }
    }

    private fun registerBackPressHandler() {
        backPressCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 酒馆宿主与 server apk 一样，返回键默认退到桌面保留当前 task，避免 finish Activity 后重进直接冷启动 WebView。
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressCallback)
    }

    private fun shouldUseWebViewSurface(): Boolean = hostConfigStore.launchWebViewOnReady

    private fun maybePromptCrashLogUploadConsent(): Boolean {
        if (hostConfigStore.crashLogUploadPromptConsumed) {
            uploadPendingCrashLogBundle(trigger = "app_start")
            return false
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_log_upload_consent_title)
            .setMessage(R.string.crash_log_upload_consent_message)
            .setCancelable(false)
            .setNegativeButton(R.string.crash_log_upload_consent_decline) { _, _ ->
                hostConfigStore.crashLogUploadEnabled = false
                // 授权提示只打扰用户一次；拒绝后后续升级也不再弹窗，仍可在“关于”页手动开启。
                hostConfigStore.crashLogUploadPromptConsumed = true
                recordDefaultHostDiagnostic(
                    category = "log_upload",
                    body = "event=crash_log_upload_consent_decided enabled=false promptConsumed=true"
                )
                maybeShowWebViewRendererCrashBrowserEngineHint()
            }
            .setPositiveButton(R.string.crash_log_upload_consent_accept) { _, _ ->
                hostConfigStore.crashLogUploadEnabled = true
                // 授权提示只打扰用户一次；授权状态后续通过“关于”页开关管理。
                hostConfigStore.crashLogUploadPromptConsumed = true
                recordDefaultHostDiagnostic(
                    category = "log_upload",
                    body = "event=crash_log_upload_consent_decided enabled=true promptConsumed=true"
                )
                uploadPendingCrashLogBundle(trigger = "post_consent")
                maybeShowWebViewRendererCrashBrowserEngineHint()
            }
            .show()
        return true
    }

    private fun requestWebViewRendererCrashBrowserEngineHint() {
        homeViewModel.requestWebViewRendererCrashBrowserEngineHint()
        recordDefaultHostDiagnostic(
            category = "webview",
            body = "event=renderer_crash_browser_engine_hint_requested"
        )
    }

    private fun maybeShowWebViewRendererCrashBrowserEngineHint() {
        if (!homeViewModel.consumeWebViewRendererCrashBrowserEngineHint()) {
            maybeShowWebViewDocumentStartUnsupportedHint()
            return
        }
        if (isFinishing || isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.webview_renderer_crash_browser_engine_hint_title)
            .setMessage(R.string.webview_renderer_crash_browser_engine_hint_message)
            .setNegativeButton(R.string.webview_renderer_crash_browser_engine_hint_later, null)
            .setPositiveButton(R.string.webview_renderer_crash_browser_engine_hint_open_settings) { _, _ ->
                bootstrapOverlayHost.openBootstrapSettings()
            }
            .show()
        recordDefaultHostDiagnostic(
            category = "webview",
            body = "event=renderer_crash_browser_engine_hint_shown"
        )
    }

    private fun requestWebViewDocumentStartUnsupportedHint() {
        homeViewModel.requestWebViewDocumentStartUnsupportedHint()
        recordDefaultHostDiagnostic(
            category = "webview",
            body = "event=document_start_unsupported_hint_requested"
        )
    }

    private fun maybeShowWebViewDocumentStartUnsupportedHint() {
        if (!homeViewModel.consumeWebViewDocumentStartUnsupportedHint()) {
            return
        }
        if (isFinishing || isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.webview_document_start_unsupported_hint_title)
            .setMessage(R.string.webview_document_start_unsupported_hint_message)
            .setNegativeButton(R.string.webview_document_start_unsupported_hint_later, null)
            .setPositiveButton(R.string.webview_document_start_unsupported_hint_open_settings) { _, _ ->
                bootstrapOverlayHost.openBootstrapSettings()
            }
            .show()
        recordDefaultHostDiagnostic(
            category = "webview",
            body = "event=document_start_unsupported_hint_shown"
        )
    }

    private fun uploadPendingCrashLogBundle(trigger: String) {
        if (!hostConfigStore.crashLogUploadEnabled) {
            recordDefaultHostDiagnostic(
                category = "log_upload",
                body = "event=auto_upload_skipped reason=consent_disabled trigger=$trigger"
            )
            return
        }
        val pendingRendererGoneKey = hostConfigStore.pendingRendererGoneAutoUploadKey
        if (!pendingRendererGoneKey.isNullOrBlank()) {
            uploadPendingRendererGoneLogBundle(trigger = trigger, uploadKey = pendingRendererGoneKey)
            return
        }
        val uploadKey = hostLogRepository.currentCrashAutoUploadKey() ?: return
        if (hostConfigStore.lastCrashLogAutoUploadKey == uploadKey) {
            return
        }

        lifecycleScope.launch {
            val result = withContext(appGraph.dispatchers.io) {
                runCatching {
                    hostLogRepository.uploadCrashBundle(
                        config = HostLogBundleUploadRequestConfig(
                            uploadUrl = appGraph.appUpdateBuildConfig.crashLogUploadUrl,
                            writerApiKey = appGraph.appUpdateBuildConfig.crashLogUploadWriterApiKey,
                            source = "automatic-crash-log-upload",
                            crashType = "uncaught-exception",
                            notes = "pending crash log auto upload; trigger=$trigger"
                        )
                    )
                }
            }
            result.onSuccess { upload ->
                hostConfigStore.lastCrashLogAutoUploadKey = uploadKey
                hostLogRepository.clearUploadedCrashLog()
                recordDefaultHostDiagnostic(
                    category = "log_upload",
                    body = "event=auto_upload_success trigger=$trigger crashLogId=${upload.crashLogId} archiveSizeBytes=${upload.archiveSizeBytes}"
                )
            }.onFailure { error ->
                recordDefaultHostDiagnostic(
                    category = "log_upload",
                    body = "event=auto_upload_failed trigger=$trigger reason=${error.javaClass.simpleName} message=${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun uploadPendingRendererGoneLogBundle(trigger: String, uploadKey: String) {
        val crashType = hostConfigStore.pendingRendererGoneAutoUploadCrashType
            ?: "webview-renderer-gone"
        val notes = hostConfigStore.pendingRendererGoneAutoUploadNotes.orEmpty()
        if (hostConfigStore.lastCrashLogAutoUploadKey == uploadKey) {
            clearPendingRendererGoneAutoUpload()
            recordDefaultHostDiagnostic(
                category = "log_upload",
                body = "event=auto_upload_skipped reason=duplicate trigger=$trigger crashType=$crashType key=$uploadKey"
            )
            return
        }

        lifecycleScope.launch {
            val result = withContext(appGraph.dispatchers.io) {
                runCatching {
                    hostLogRepository.uploadCrashBundle(
                        config = HostLogBundleUploadRequestConfig(
                            uploadUrl = appGraph.appUpdateBuildConfig.crashLogUploadUrl,
                            writerApiKey = appGraph.appUpdateBuildConfig.crashLogUploadWriterApiKey,
                            source = "pending-webview-renderer-gone",
                            crashType = crashType,
                            notes = notes
                        )
                    )
                }
            }
            result.onSuccess { upload ->
                hostConfigStore.lastCrashLogAutoUploadKey = uploadKey
                clearPendingRendererGoneAutoUpload()
                recordDefaultHostDiagnostic(
                    category = "log_upload",
                    body = "event=auto_upload_success trigger=$trigger crashType=$crashType crashLogId=${upload.crashLogId} archiveSizeBytes=${upload.archiveSizeBytes}"
                )
            }.onFailure { error ->
                recordDefaultHostDiagnostic(
                    category = "log_upload",
                    body = "event=auto_upload_failed trigger=$trigger crashType=$crashType reason=${error.javaClass.simpleName} message=${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun uploadRendererGoneLogBundle(info: WebViewRendererGoneInfo) {
        if (!shouldAutoUploadRendererGoneBundle(info, activityFinishing = isFinishing, activityDestroyed = isDestroyed)) {
            recordDefaultHostDiagnostic(
                category = "log_upload",
                body = "event=auto_upload_skipped reason=renderer_cleanup_noise trigger=webview_renderer_gone didCrash=${info.didCrash} activityFinishing=$isFinishing activityDestroyed=$isDestroyed"
            )
            return
        }
        if (!hostConfigStore.crashLogUploadEnabled) {
            recordDefaultHostDiagnostic(
                category = "log_upload",
                body = "event=auto_upload_skipped reason=consent_disabled trigger=webview_renderer_gone didCrash=${info.didCrash}"
            )
            return
        }
        val existingPendingKey = hostConfigStore.pendingRendererGoneAutoUploadKey
        if (!existingPendingKey.isNullOrBlank()) {
            recordDefaultHostDiagnostic(
                category = "log_upload",
                body = "event=auto_upload_skipped reason=pending_exists trigger=webview_renderer_gone key=$existingPendingKey"
            )
            return
        }

        val uploadKey = rendererGoneAutoUploadKey()
        hostConfigStore.pendingRendererGoneAutoUploadKey = uploadKey
        hostConfigStore.pendingRendererGoneAutoUploadCrashType = if (info.didCrash) {
            "webview-renderer-crash"
        } else {
            "webview-renderer-gone"
        }
        // renderer gone 发生后系统写入 ApplicationExitInfo/tombstone 可能延迟；
        // 这里只记录待上传标志，下一次启动再打包，避免太早上传导致关键 native 退出信息缺失。
        hostConfigStore.pendingRendererGoneAutoUploadNotes = info.toDiagnosticText()
        recordDefaultHostDiagnostic(
            category = "log_upload",
            body = "event=auto_upload_mark_pending trigger=webview_renderer_gone key=$uploadKey didCrash=${info.didCrash} uploadOnNextStart=true"
        )
    }

    private fun clearPendingRendererGoneAutoUpload() {
        hostConfigStore.pendingRendererGoneAutoUploadKey = null
    }

    private fun rendererGoneAutoUploadKey(): String =
        // WebView renderer gone 不一定产生 App 闪退文件；用事件时间生成待上传标志，
        // 同一标志会在下一次启动上传成功后清理，上传失败则保留到后续启动继续补刷。
        "webview_renderer_gone:${System.currentTimeMillis()}"

    private fun createBrowserHost(): TavernBrowserHost {
        if (!shouldUseWebViewSurface()) {
            return DisabledBrowserHost(
                activity = this,
                browserEngine = hostConfigStore.browserEngine,
                criticalHostDiagnosticSink = HostDiagnosticSink { category, body ->
                    recordDefaultHostDiagnostic(category = category, body = body)
                }
            )
        }
        return when (hostConfigStore.browserEngine) {
            BrowserEngine.SYSTEM_WEBVIEW -> createSystemWebViewHost()
            BrowserEngine.GECKOVIEW -> createGeckoViewHost()
        }
    }

    private fun createSystemWebViewHost(): TavernBrowserHost {
        return runCatching {
            TavernWebViewHost(
                activity = this,
                homeViewModel = homeViewModel,
                hostConfigStore = hostConfigStore,
                runtimeConfigRepository = runtimeConfigRepository,
                processManager = processManager,
                bridgeInstaller = createBrowserBridgeInstaller(BrowserEngine.SYSTEM_WEBVIEW),
                restoreHostSystemBarAppearance = ::applyHostSurfaceSystemBars,
                onDownloadRequested = { request -> hostIo.handlePageDownload(request) },
                onShowFileChooser = { fileChooserParams, callback -> hostIo.launchFileChooser(fileChooserParams, callback) },
                jsErrorSink = ::recordDetailedWebViewJsError,
                hostDiagnosticSink = HostDiagnosticSink { category, body ->
                    recordDetailedHostDiagnostic(category = category, body = body)
                },
                criticalHostDiagnosticSink = HostDiagnosticSink { category, body ->
                    recordDefaultHostDiagnostic(category = category, body = body)
                },
                refreshApplicationExitInfo = { hostLogRepository.refreshApplicationExitInfoAsync() },
                uploadRendererGoneLogBundle = ::uploadRendererGoneLogBundle,
                onWebViewRendererCrash = ::requestWebViewRendererCrashBrowserEngineHint,
                onDocumentStartScriptUnsupported = ::requestWebViewDocumentStartUnsupportedHint
            )
        }.getOrElse { error ->
            recordDefaultHostDiagnostic(
                category = "browser",
                body = "event=system_webview_host_create_failed reason=${error.javaClass.name} message=${error.message.orEmpty()}"
            )
            UnavailableBrowserHost(
                activity = this,
                browserEngine = BrowserEngine.SYSTEM_WEBVIEW,
                unavailableReason = error,
                openSettings = {
                    if (::bootstrapOverlayHost.isInitialized) {
                        bootstrapOverlayHost.openBootstrapSettings()
                    }
                },
                restoreHostSystemBarAppearance = ::applyHostSurfaceSystemBars,
                criticalHostDiagnosticSink = HostDiagnosticSink { category, body ->
                    recordDefaultHostDiagnostic(category = category, body = body)
                }
            )
        }
    }

    private fun createGeckoViewHost(): TavernBrowserHost {
        return TavernGeckoViewHost(
            activity = this,
            homeViewModel = homeViewModel,
            hostConfigStore = hostConfigStore,
            runtimeConfigRepository = runtimeConfigRepository,
            processManager = processManager,
            bridgeInstaller = createBrowserBridgeInstaller(BrowserEngine.GECKOVIEW),
            restoreHostSystemBarAppearance = ::applyHostSurfaceSystemBars,
            onDownloadRequested = { request -> hostIo.handleBrowserResponseDownload(request) },
            onShowFileChooser = { request, callback -> hostIo.launchFileChooser(request, callback) },
            scope = lifecycleScope,
            ioDispatcher = appGraph.dispatchers.io,
            hostDiagnosticSink = HostDiagnosticSink { category, body ->
                recordDetailedHostDiagnostic(category = category, body = body)
            },
            criticalHostDiagnosticSink = HostDiagnosticSink { category, body ->
                recordDefaultHostDiagnostic(category = category, body = body)
            }
        )
    }

    private fun createBrowserBridgeInstaller(browserEngine: BrowserEngine): BrowserHostBridgeInstaller {
        return BrowserHostBridgeInstallerFactory(
            activity = this,
            hostIo = hostIo,
            downloadNotificationCoordinator = appGraph.hostDownloadNotificationCoordinator,
            actions = createBrowserHostBridgeActions(),
            scope = lifecycleScope,
            dispatchers = appGraph.dispatchers,
            diagnosticSink = { category, body ->
                if (category == "download") {
                    recordDetailedHostDiagnostic(category = category, body = body)
                } else {
                    recordDefaultHostDiagnostic(category = category, body = body)
                }
            }
        ).create(browserEngine)
    }

    private fun createBrowserHostBridgeActions(): BrowserHostBridgeActions {
        return BrowserHostBridgeActions(
            isHostActive = { !isFinishing && !isDestroyed },
            runOnUiThread = { action -> runOnUiThread(action) },
            openSettings = { bootstrapOverlayHost.openBootstrapSettings() },
            showFloatingLogsBubble = { floatingLogsHost.showBubble() },
            requestOpenCurrentPageInBrowser = { browserHost.openCurrentPageInExternalBrowser() },
            applyFloatingLogsBubbleEnabled = { enabled -> floatingLogsHost.setBubbleEnabled(enabled) },
            applyBrowserPullRefreshEnabled = { enabled ->
                hostConfigStore.webViewPullRefreshEnabled = enabled
                browserHost.updateRefreshLayoutEnabled()
            },
            applySystemBarsBackgroundColor = ::applyWebViewSurfaceSystemBars,
            applySystemBarsBackgroundColors = ::applyWebViewSurfaceSystemBars,
            reloadTavern = { browserHost.reloadTavernWebView(source = "android_host_bridge") },
            hostVersionInfoJson = ::buildAndroidHostVersionInfoJson,
            recordWebPerformanceDiagnosticPayload = { payload ->
                recordWebPerformanceDiagnosticPayload(payload)
            }
        )
    }

    private fun applyHostSurfaceSystemBars() {
        applyMainSurfaceSystemBars(
            backgroundColor = ContextCompat.getColor(this, R.color.bootstrap_overlay_background)
        )
    }

    private fun applyWebViewSurfaceSystemBars(hexColor: String) {
        lastWebViewSystemBarsColorHex = hexColor
        lastWebViewStatusBarColorHex = null
        lastWebViewNavigationBarColorHex = null
        val parsedColor = runCatching { Color.parseColor(hexColor.trim()) }
            .getOrDefault(ContextCompat.getColor(this, R.color.tavern_webview_background))
        applyMainSurfaceSystemBars(parsedColor)
    }

    private fun applyWebViewSurfaceSystemBars(statusBarHexColor: String, navigationBarHexColor: String) {
        lastWebViewSystemBarsColorHex = null
        lastWebViewStatusBarColorHex = statusBarHexColor
        lastWebViewNavigationBarColorHex = navigationBarHexColor
        val statusBarColor = runCatching { Color.parseColor(statusBarHexColor.trim()) }
            .getOrDefault(ContextCompat.getColor(this, R.color.tavern_webview_background))
        val navigationBarColor = runCatching { Color.parseColor(navigationBarHexColor.trim()) }
            .getOrDefault(statusBarColor)
        applyMainSurfaceSystemBars(
            statusBarColor = statusBarColor,
            navigationBarColor = navigationBarColor
        )
    }

    private fun applyMainSurfaceSystemBars(@ColorInt backgroundColor: Int) {
        applyMainSurfaceSystemBars(
            statusBarColor = backgroundColor,
            navigationBarColor = backgroundColor
        )
    }

    private fun applyMainSurfaceSystemBars(
        @ColorInt statusBarColor: Int,
        @ColorInt navigationBarColor: Int
    ) {
        // contentRoot 只负责内容安全区；顶部状态栏和底部手势区要分别着色，
        // 否则双色主题在 edge-to-edge 下会被根容器单色背景合并成一个颜色。
        statusBarBackground.setBackgroundColor(statusBarColor)
        navigationBarBackground.setBackgroundColor(navigationBarColor)
        SystemBarAppearanceController.applyForColors(
            activity = this,
            mode = effectiveMainDisplayMode(),
            statusBarColor = statusBarColor,
            navigationBarColor = navigationBarColor
        )
        if (::systemBarInsetsController.isInitialized) {
            systemBarInsetsController.refresh()
        }
    }

    private fun reapplyCurrentSystemBars() {
        // 从设置页返回主界面时，系统栏显示模式可能已经变化；
        // 这里按“当前宿主实际显示的是启动遮罩还是 WebView 页面”重新应用一次，保证设置立即生效。
        if (!::browserHost.isInitialized || !browserHost.browserContainer.isShown) {
            applyHostSurfaceSystemBars()
            return
        }

        val webViewStatusColor = lastWebViewStatusBarColorHex
        val webViewNavigationColor = lastWebViewNavigationBarColorHex
        if (!webViewStatusColor.isNullOrBlank() && !webViewNavigationColor.isNullOrBlank()) {
            applyWebViewSurfaceSystemBars(webViewStatusColor, webViewNavigationColor)
            return
        }

        val webViewColor = lastWebViewSystemBarsColorHex
        if (webViewColor.isNullOrBlank()) {
            applyMainSurfaceSystemBars(
                backgroundColor = ContextCompat.getColor(this, R.color.tavern_webview_background)
            )
            return
        }

        applyWebViewSurfaceSystemBars(webViewColor)
    }

    private fun effectiveMainDisplayMode(): HostDisplayMode {
        // 主界面横屏复用“只隐藏顶部通知栏”的既有沉浸语义，让 WebView 默认贴边显示。
        return resolveMainHostDisplayMode(
            configuredMode = hostConfigStore.hostDisplayMode,
            isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
    }

    private fun allowMainContentIntoDisplayCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        window.attributes = window.attributes.also { attributes ->
            // 小米等挖孔屏横屏时，cutout 默认安全区会把 WebView 从左侧挤出一块空白；
            // 主界面已经自行处理系统栏 inset，因此这里允许内容进入短边 cutout 区域。
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun buildAndroidHostVersionInfoJson(): String {
        val packageInfo = currentPackageInfo()
        val webViewPackageInfo = runCatching { WebViewCompat.getCurrentWebViewPackage(this) }.getOrNull()
        val browserRuntimeInfo = if (::browserHost.isInitialized) {
            browserHost.currentBrowserRuntimeInfo()
        } else {
            null
        }
        val webViewRuntimeCompatibility = if (::browserHost.isInitialized) {
            (browserHost as? TavernWebViewHost)?.currentRuntimeCompatibility()
        } else {
            null
        }
        val activityManager = getSystemService(ActivityManager::class.java)

        return JSONObject()
            .put("hostVersion", appGraph.appUpdateBuildConfig.hostVersion)
            .put("apkVersionName", packageInfo.versionName.orEmpty().trim())
            .put("apkVersionCode", packageInfo.longVersionCode.toString())
            .put("androidSdkInt", Build.VERSION.SDK_INT)
            .put("deviceManufacturer", Build.MANUFACTURER.orEmpty().trim())
            .put("deviceModel", Build.MODEL.orEmpty().trim())
            .put("deviceHardware", Build.HARDWARE.orEmpty().trim())
            .put("isLowRamDevice", activityManager?.isLowRamDevice == true)
            .put("appMemoryClassMb", activityManager?.memoryClass ?: 0)
            .put("appLargeMemoryClassMb", activityManager?.largeMemoryClass ?: 0)
            // 毛玻璃自动性能档需要知道实际 WebView provider/version；不同厂商 WebView 的合成层表现差异很大。
            .put("webViewPackageName", webViewPackageInfo?.packageName.orEmpty().trim())
            .put("webViewVersionName", webViewPackageInfo?.versionName.orEmpty().trim())
            .put("webViewVersionCode", webViewPackageInfo?.let { PackageInfoCompat.getLongVersionCode(it) }?.toString().orEmpty())
            // provider 版本在华为设备上可能是 14/114 这类厂商版本；真实 CSS/JS 能力以 UA 里的 Chromium 版本为准。
            .put("webViewChromiumVersion", webViewRuntimeCompatibility?.chromiumVersion.orEmpty())
            .put("webViewChromiumMajorVersion", webViewRuntimeCompatibility?.chromiumMajorVersion ?: 0)
            .put("webViewRecommendedChromiumMajorVersion", WebViewRuntimeCompatibility.recommendedChromiumMajorVersion())
            .put("webViewOutdated", webViewRuntimeCompatibility?.isOutdated == true)
            .put("browserEngine", if (::browserHost.isInitialized) browserHost.browserEngine.name else hostConfigStore.browserEngine.name)
            .put("browserRuntimeName", browserRuntimeInfo?.runtimeName.orEmpty())
            .put("browserPackageName", browserRuntimeInfo?.packageName.orEmpty())
            .put("browserVersionName", browserRuntimeInfo?.versionName.orEmpty())
            .put("browserVersionCode", browserRuntimeInfo?.versionCode.orEmpty())
            .put("browserCoreName", browserRuntimeInfo?.coreName.orEmpty())
            .put("browserCoreVersion", browserRuntimeInfo?.coreVersion.orEmpty())
            .put("browserCoreMajorVersion", browserRuntimeInfo?.coreMajorVersion ?: 0)
            .put("browserRecommendedCoreMajorVersion", browserRuntimeInfo?.recommendedCoreMajorVersion ?: 0)
            .put("browserOutdated", browserRuntimeInfo?.outdated == true)
            .put("browserUserAgent", browserRuntimeInfo?.userAgent.orEmpty())
            .put("browserZoomPercent", hostConfigStore.browserZoomPercent)
            // 该方法会被 JS Bridge 从 WebView JavaBridge 线程调用；版本信息只能读取宿主配置快照，
            // 不能反向触碰 WebView/GeckoView UI 对象，否则部分 WebView 会抛出跨线程访问异常。
            .put("browserAppliedZoomPercent", hostConfigStore.browserZoomPercent)
            .put("hostDisplayMode", hostConfigStore.hostDisplayMode.name)
            .put("launchWebViewOnReady", hostConfigStore.launchWebViewOnReady)
            .put("floatingLogBubbleEnabled", hostConfigStore.floatingLogBubbleEnabled)
            .put("webViewPullRefreshEnabled", hostConfigStore.webViewPullRefreshEnabled)
            .put("unrestrictedFileImportSelectionEnabled", hostConfigStore.unrestrictedFileImportSelectionEnabled)
            .put("serverReady", processManager.currentSnapshot().isReady)
            .toString()
    }

    private fun applyBrowserZoomPercentFromFloatingLogs(percent: Int): Boolean {
        val sanitizedPercent = BrowserZoomOptions.sanitize(percent)
        hostConfigStore.browserZoomPercent = sanitizedPercent
        if (!::browserHost.isInitialized || !shouldUseWebViewSurface()) {
            recordDefaultHostDiagnostic(
                category = "browser",
                body = "event=browser_zoom_apply_skipped reason=browser_unavailable percent=$sanitizedPercent"
            )
            return false
        }
        return browserHost.setBrowserZoomPercent(sanitizedPercent)
    }

    private fun currentPackageInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }

    private fun inspectWebViewRuntimeBeforeBootstrap() {
        val compatibility = (browserHost as? TavernWebViewHost)?.currentRuntimeCompatibility()
        if (compatibility == null) {
            hostLogRepository.recordHostDiagnostic(
                category = "browser",
                body = "event=startup_browser_engine webViewRuntimeCompatibility=skipped ${browserHost.currentBrowserRuntimeInfo().toDiagnosticText()}"
            )
            return
        }
        // 这条日志属于启动前兼容性结论，必须常驻导出日志，避免旧 WebView 导致主题/布局异常时缺少根因证据。
        hostLogRepository.recordHostDiagnostic(
            category = "webview",
            body = "event=startup_runtime_compatibility ${compatibility.toDiagnosticText()}"
        )
        if (compatibility.isOutdated) {
            showOutdatedWebViewHint(compatibility)
        }
    }

    private fun showOutdatedWebViewHint(compatibility: WebViewRuntimeCompatibility) {
        if (isFinishing || isDestroyed) {
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.webview_outdated_dialog_title)
            .setMessage(
                getString(
                    R.string.webview_outdated_dialog_message,
                    compatibility.chromiumVersion.ifBlank { getString(R.string.webview_unknown_version) }
                )
            )
            .setPositiveButton(R.string.webview_outdated_dialog_continue, null)
            .show()
    }

    private fun recordActivityLifecycleDiagnostic(event: String, extra: String = "") {
        // Activity 重建会让 WebView 看起来像“自己刷新”；这些低频生命周期线索默认导出，
        // 便于客户未开启详细诊断时也能区分配置变化、系统回收和普通页面 reload。
        recordDefaultHostDiagnostic(
            category = "activity",
            body = buildString {
                append("event=$event")
                append(" taskId=$taskId")
                append(" changingConfigurations=$isChangingConfigurations")
                append(" finishing=$isFinishing")
                append(" destroyed=$isDestroyed")
                append(' ')
                append(currentConfigurationDiagnosticText())
                if (extra.isNotBlank()) {
                    append(' ')
                    append(extra)
                }
            }
        )
    }

    private fun currentConfigurationDiagnosticText(): String {
        val configuration = resources.configuration
        return buildString {
            append("orientation=${formatOrientation(configuration.orientation)}")
            append(" uiModeNight=${formatNightMode(configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)}")
            append(" screenLayoutSize=${formatScreenLayoutSize(configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK)}")
            append(" densityDpi=${configuration.densityDpi}")
            append(" fontScale=${configuration.fontScale}")
            append(" keyboard=${configuration.keyboard}")
            append(" hardKeyboardHidden=${configuration.hardKeyboardHidden}")
            append(" navigation=${configuration.navigation}")
        }
    }

    private fun formatOrientation(orientation: Int): String {
        @Suppress("DEPRECATION")
        return when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_SQUARE -> "square"
            Configuration.ORIENTATION_UNDEFINED -> "undefined"
            else -> "unknown_$orientation"
        }
    }

    private fun formatNightMode(maskedUiMode: Int): String {
        return when (maskedUiMode) {
            Configuration.UI_MODE_NIGHT_NO -> "no"
            Configuration.UI_MODE_NIGHT_YES -> "yes"
            Configuration.UI_MODE_NIGHT_UNDEFINED -> "undefined"
            else -> "unknown_$maskedUiMode"
        }
    }

    private fun formatScreenLayoutSize(maskedScreenLayout: Int): String {
        return when (maskedScreenLayout) {
            Configuration.SCREENLAYOUT_SIZE_SMALL -> "small"
            Configuration.SCREENLAYOUT_SIZE_NORMAL -> "normal"
            Configuration.SCREENLAYOUT_SIZE_LARGE -> "large"
            Configuration.SCREENLAYOUT_SIZE_XLARGE -> "xlarge"
            Configuration.SCREENLAYOUT_SIZE_UNDEFINED -> "undefined"
            else -> "unknown_$maskedScreenLayout"
        }
    }

    private fun recordDefaultHostDiagnostic(category: String, body: String) {
        hostLogRepository.recordHostDiagnostic(category = category, body = body)
    }

    private fun recordWebPerformanceDiagnosticPayload(payload: String) {
        // 浏览器内核/缩放/能力摘要是一行低频证据，默认写盘便于 release 现场直接判断
        // GeckoView 与系统 WebView 的 viewport、DPR、IndexedDB/WebGL/WASM 能力差异。
        recordDefaultHostDiagnostic(category = "web_performance", body = payload)
    }

    // 宿主详细诊断日志只在“调试模式”开启时写盘；
    // 默认模式下保留启动日志、服务日志和崩溃日志，避免常态运行时把 host-diagnostics / js-error 写得过于噪杂。
    private fun recordDetailedHostDiagnostic(category: String, body: String) {
        if (!hostConfigStore.debugDiagnosticsEnabled) {
            return
        }
        hostLogRepository.recordHostDiagnostic(category = category, body = body)
    }

    private fun recordDetailedWebViewJsError(line: String) {
        if (!hostConfigStore.debugDiagnosticsEnabled) {
            return
        }
        hostLogRepository.recordWebViewJsError(line)
    }

    private fun maybePromptDefaultExtensionsAfterBootstrapReady() {
        if (hostConfigStore.defaultExtensionsPromptConsumed) {
            return
        }

        hostConfigStore.defaultExtensionsPromptConsumed = true
        val repositoryCount = appGraph.defaultExtensionRepositoryCount()
        if (repositoryCount <= 0) {
            return
        }
        // 在主界面用一个独立小窗触发完整流程：GitHub 可达性预检 + 按仓库批量预检 + 用户勾选确认 +
        // 百分比进度 + 结果汇总；底层直接复用设置页 BootstrapSettingsExtensionsCoordinator，避免
        // MainActivity 自维护一份阉割版逻辑导致两侧分叉。
        DefaultExtensionsInstallerLauncher(
            activity = this,
            dispatchers = appGraph.dispatchers,
            extensionsRepository = appGraph.extensionsRepository(),
            onTavernUiReloadRequired = {
                browserHost.reloadTavernUiIfPossible(processManager.currentSnapshot())
            }
        ).launch()
    }
}
