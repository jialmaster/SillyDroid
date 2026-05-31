package com.jm.sillydroid.feature.main

import android.app.ActivityManager
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.ui.window.SystemBarAppearanceController
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.feature.main.ui.extensions.DefaultExtensionsInstallerLauncher
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel
import com.jm.sillydroid.feature.main.ui.home.bootstrap.BootstrapOverlayHost
import com.jm.sillydroid.feature.main.ui.home.download.AndroidBlobDownloadBridge
import com.jm.sillydroid.feature.main.ui.home.floatinglogs.FloatingLogsHost
import com.jm.sillydroid.feature.main.ui.home.io.HostIoController
import com.jm.sillydroid.feature.main.ui.home.notification.AndroidSystemNotificationBridge
import com.jm.sillydroid.feature.main.ui.home.system.SystemBarInsetsController
import com.jm.sillydroid.feature.main.ui.home.system.resolveMainHostDisplayMode
import com.jm.sillydroid.feature.main.ui.home.webview.AndroidHostBridge
import com.jm.sillydroid.feature.main.ui.home.webview.HostDiagnosticSink
import com.jm.sillydroid.feature.main.ui.home.webview.TavernWebViewHost
import com.jm.sillydroid.feature.main.ui.home.webview.WebViewRuntimeCompatibility
import org.json.JSONObject

/**
 * MainActivity 现在只做"装配"：构造五个 host（FloatingLogs / BootstrapOverlay / TavernWebView /
 * HostIo / SystemBarInsets），把它们之间的回调线连起来，剩下的视图字段、状态、controller、
 * launcher 全在各自 host 内部。
 *
 * 跨 host 的关键回调：
 *  - WebView 的 JS 桥安装由 [installWebViewJavascriptInterfaces] 拼装：blob 下载（HostIo）、
 *    系统通知（HostIo）、宿主能力桥（含 FloatingLogs / BootstrapOverlay）。
 *  - SystemBarInsets 的 IME 变化通知 TavernWebView 暂停下拉刷新。
 *  - SystemBarInsets 的 bounds 变化通知 FloatingLogs 重新计算可视范围。
 */
class MainActivity : AppCompatActivity() {
    private companion object {
        private const val downloadBridgeName = "AndroidDownloadBridge"
        private const val systemNotificationBridgeName = "AndroidSystemNotificationBridge"
        private const val androidHostBridgeName = "SillyDroidAndroidHostBridge"
    }

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
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.Factory(this, processManager) }

    private lateinit var hostIo: HostIoController
    private lateinit var floatingLogsHost: FloatingLogsHost
    private lateinit var webViewHost: TavernWebViewHost
    private lateinit var bootstrapOverlayHost: BootstrapOverlayHost
    private lateinit var systemBarInsetsController: SystemBarInsetsController
    private var lastWebViewSystemBarsColorHex: String? = null
    private var lastWebViewStatusBarColorHex: String? = null
    private var lastWebViewNavigationBarColorHex: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        installWebViewStack(savedInstanceState)
        installBootstrapWiring()
        inspectWebViewRuntimeBeforeBootstrap()
        bootstrapOverlayHost.startBootstrap(false)
    }

    private fun composeHosts() {
        hostIo = HostIoController(
            activity = this,
            runtimeConfigRepository = runtimeConfigRepository,
            hostPreferencesRepository = hostConfigStore,
            hostNotificationService = appGraph.hostNotificationService,
            hostDownloadNotificationCoordinator = appGraph.hostDownloadNotificationCoordinator,
            blobDownloadBridgeName = downloadBridgeName,
            downloadDiagnosticSink = { body ->
                recordDetailedHostDiagnostic(category = "download", body = body)
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
            reloadTavernWebView = { webViewHost.reloadTavernWebView(source = "floating_logs_button") }
        )
        webViewHost = TavernWebViewHost(
            activity = this,
            homeViewModel = homeViewModel,
            hostConfigStore = hostConfigStore,
            runtimeConfigRepository = runtimeConfigRepository,
            processManager = processManager,
            installJavascriptInterfaces = ::installWebViewJavascriptInterfaces,
            installBlobBridgeScriptOnPageFinished = { webView ->
                hostIo.blobDownloadController.installBridgeScript(
                    webView = webView,
                    bridgeName = downloadBridgeName,
                    allowedOrigin = runtimeConfigRepository.localServiceUrl()
                )
            },
            restoreHostSystemBarAppearance = ::applyHostSurfaceSystemBars,
            onDownloadRequested = { request -> hostIo.handlePageDownload(request) },
            onShowFileChooser = { fileChooserParams, callback -> hostIo.launchFileChooser(fileChooserParams, callback) },
            jsErrorSink = ::recordDetailedWebViewJsError,
            hostDiagnosticSink = HostDiagnosticSink { category, body ->
                recordDetailedHostDiagnostic(category = category, body = body)
            },
            refreshApplicationExitInfo = { hostLogRepository.refreshApplicationExitInfoAsync() }
        )
        bootstrapOverlayHost = BootstrapOverlayHost(
            activity = this,
            homeViewModel = homeViewModel,
            processManager = processManager,
            appGraph = appGraph,
            webViewHost = webViewHost,
            floatingLogsHost = floatingLogsHost,
            onMaybePromptDefaultExtensionsAfterBootstrapReady = ::maybePromptDefaultExtensionsAfterBootstrapReady
        )
        systemBarInsetsController = SystemBarInsetsController(
            contentRoot = contentRoot,
            statusBarBackground = statusBarBackground,
            navigationBarBackground = navigationBarBackground,
            homeViewModel = homeViewModel,
            displayModeProvider = { effectiveMainDisplayMode() },
            onImeChanged = { visible -> webViewHost.onImeVisibilityChanged(visible) },
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

    private fun installWebViewStack(savedInstanceState: Bundle?) {
        webViewHost.configure()
        registerBackPressHandler()
        webViewHost.restoreState(savedInstanceState)
    }

    private fun installBootstrapWiring() {
        bootstrapOverlayHost.observe()
        bootstrapOverlayHost.bindButtons()
    }

    override fun onResume() {
        super.onResume()
        reapplyCurrentSystemBars()
        floatingLogsHost.refreshVisibility()
        webViewHost.onResume()
        webViewHost.updateRefreshLayoutEnabled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webViewHost.saveState(outState)
    }

    override fun onDestroy() {
        webViewHost.onDestroy()
        hostIo.cancelPendingFileChooser()
        hostIo.blobDownloadController.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::webViewHost.isInitialized) {
            webViewHost.onTrimMemory(level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::webViewHost.isInitialized) {
            webViewHost.onLowMemory()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

    private fun installWebViewJavascriptInterfaces(targetWebView: WebView) {
        // Tavern 页面里的导出既可能是普通 URL，也可能是 blob/data；宿主在这里统一接管保存到系统下载目录。
        targetWebView.addJavascriptInterface(
            AndroidBlobDownloadBridge(
                controller = hostIo.blobDownloadController,
                scope = lifecycleScope,
                dispatchers = appGraph.dispatchers,
                runOnUiThread = { action -> runOnUiThread(action) },
                unknownErrorMessage = { getString(R.string.download_failed_unknown) },
                emptyPayloadMessage = { getString(R.string.download_failed_empty_payload) },
                onPreparing = { fileName ->
                    Toast.makeText(this, getString(R.string.download_status_preparing, fileName), Toast.LENGTH_SHORT).show()
                },
                onSaving = { fileName ->
                    Toast.makeText(this, getString(R.string.download_status_saving, fileName), Toast.LENGTH_SHORT).show()
                },
                onSaved = { savedFile ->
                    // blob/data 导出没有 DownloadManager downloadId；保存完成后直接交给既有下载通知协调器发统一通知。
                    appGraph.hostDownloadNotificationCoordinator.postBrowserDownloadSaved(
                        fileName = savedFile.fileName,
                        mimeType = savedFile.mimeType,
                        contentUri = savedFile.contentUri,
                        displayPath = savedFile.displayPath
                    )
                    Toast.makeText(this, getString(R.string.download_saved, savedFile.fileName), Toast.LENGTH_SHORT).show()
                },
                onFailure = hostIo::showDownloadFailure,
                diagnosticSink = { body ->
                    recordDetailedHostDiagnostic(category = "download", body = body)
                }
            ),
            downloadBridgeName
        )
        // 角色导出这类 blob 下载要求在页面脚本执行前就接管 createObjectURL/blob 响应；
        // 这里先注册 document-start 脚本，再由 pageFinished 对当前可见页面补打一遍。
        hostIo.blobDownloadController.installBridgeScript(
            webView = targetWebView,
            bridgeName = downloadBridgeName,
            allowedOrigin = runtimeConfigRepository.localServiceUrl()
        )
        // 浏览器通知统一走宿主桥，避免 Android WebView 里再退回不可用的 Notification API。
        targetWebView.addJavascriptInterface(
            AndroidSystemNotificationBridge(
                notificationController = hostIo.systemNotificationController,
                isHostActive = { !isFinishing && !isDestroyed },
                runOnUiThread = { action -> runOnUiThread(action) },
                requestPermission = { hostIo.requestNotificationPermissionIfNeeded() }
            ),
            systemNotificationBridgeName
        )
        // 只暴露 Tavern 需要的最小宿主能力，给 Android 专属扩展调用设置页、当前页面外开、日志悬浮球和版本信息。
        targetWebView.addJavascriptInterface(
            AndroidHostBridge(
                isHostActive = { !isFinishing && !isDestroyed },
                runOnUiThread = { action -> runOnUiThread(action) },
                openSettings = { bootstrapOverlayHost.openBootstrapSettings() },
                showFloatingLogsBubble = { floatingLogsHost.showBubble() },
                requestOpenCurrentPageInBrowser = {
                    webViewHost.openCurrentPageInExternalBrowser()
                },
                applyFloatingLogsBubbleEnabled = { enabled -> floatingLogsHost.setBubbleEnabled(enabled) },
                applyWebViewPullRefreshEnabled = { enabled ->
                    hostConfigStore.webViewPullRefreshEnabled = enabled
                    webViewHost.updateRefreshLayoutEnabled()
                },
                applySystemBarsBackgroundColor = ::applyWebViewSurfaceSystemBars,
                applySystemBarsBackgroundColors = ::applyWebViewSurfaceSystemBars,
                reloadTavern = { webViewHost.reloadTavernWebView(source = "android_host_bridge") },
                hostVersionInfoJson = ::buildAndroidHostVersionInfoJson,
                recordWebPerformanceDiagnosticPayload = { payload ->
                    recordDetailedHostDiagnostic(category = "web_performance", body = payload)
                }
            ),
            androidHostBridgeName
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
        if (!::webViewHost.isInitialized || !webViewHost.webViewRefreshLayout.isShown) {
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
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val webViewPackageInfo = runCatching { WebViewCompat.getCurrentWebViewPackage(this) }.getOrNull()
        val webViewRuntimeCompatibility = if (::webViewHost.isInitialized) {
            webViewHost.currentRuntimeCompatibility()
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
            .put("hostDisplayMode", hostConfigStore.hostDisplayMode.name)
            .put("floatingLogBubbleEnabled", hostConfigStore.floatingLogBubbleEnabled)
            .put("webViewPullRefreshEnabled", hostConfigStore.webViewPullRefreshEnabled)
            .put("unrestrictedFileImportSelectionEnabled", hostConfigStore.unrestrictedFileImportSelectionEnabled)
            .put("serverReady", processManager.currentSnapshot().isReady)
            .toString()
    }

    private fun inspectWebViewRuntimeBeforeBootstrap() {
        val compatibility = webViewHost.currentRuntimeCompatibility()
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
                webViewHost.reloadTavernUiIfPossible(processManager.currentSnapshot())
            }
        ).launch()
    }
}
