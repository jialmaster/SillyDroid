package com.jm.sillydroid.feature.main

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
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
import com.jm.sillydroid.feature.main.ui.home.webview.AndroidHostBridge
import com.jm.sillydroid.feature.main.ui.home.webview.HostDiagnosticSink
import com.jm.sillydroid.feature.main.ui.home.webview.TavernWebViewHost
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        contentRoot = findViewById(R.id.contentRoot)
        // 主界面刚启动时先显示 bootstrap overlay；系统栏先跟宿主遮罩底色走，
        // 等 WebView 页面拿到真实背景色后再由页面桥持续同步过去。
        applyHostSurfaceSystemBars()

        composeHosts()
        bootstrapOverlayHost.installAppUpdateCoordinator()
        installSystemUi()
        installWebViewStack(savedInstanceState)
        installBootstrapWiring()
        bootstrapOverlayHost.startBootstrap(false)
    }

    private fun composeHosts() {
        hostIo = HostIoController(
            activity = this,
            runtimeConfigRepository = runtimeConfigRepository,
            hostPreferencesRepository = hostConfigStore,
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
            homeViewModel = homeViewModel,
            displayModeProvider = { hostConfigStore.hostDisplayMode },
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
                onSaved = { fileName ->
                    Toast.makeText(this, getString(R.string.download_saved, fileName), Toast.LENGTH_SHORT).show()
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
        // 只暴露 Tavern 需要的最小宿主能力，给 Android 专属扩展调用设置页、日志悬浮球和版本信息。
        targetWebView.addJavascriptInterface(
            AndroidHostBridge(
                isHostActive = { !isFinishing && !isDestroyed },
                runOnUiThread = { action -> runOnUiThread(action) },
                openSettings = { bootstrapOverlayHost.openBootstrapSettings() },
                showFloatingLogsBubble = { floatingLogsHost.showBubble() },
                applyFloatingLogsBubbleEnabled = { enabled -> floatingLogsHost.setBubbleEnabled(enabled) },
                applyWebViewPullRefreshEnabled = { enabled ->
                    hostConfigStore.webViewPullRefreshEnabled = enabled
                    webViewHost.updateRefreshLayoutEnabled()
                },
                applySystemBarsBackgroundColor = ::applyWebViewSurfaceSystemBars,
                reloadTavern = { webViewHost.reloadTavernWebView(source = "android_host_bridge") },
                hostVersionInfoJson = ::buildAndroidHostVersionInfoJson
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
        val parsedColor = runCatching { Color.parseColor(hexColor.trim()) }
            .getOrDefault(ContextCompat.getColor(this, R.color.tavern_webview_background))
        applyMainSurfaceSystemBars(parsedColor)
    }

    private fun applyMainSurfaceSystemBars(@ColorInt backgroundColor: Int) {
        // contentRoot 会为系统栏安全区留 padding；这块区域本身也必须跟着宿主/WebView 背景走，
        // 否则即使系统栏颜色切对了，仍会看到一条宿主默认白底的空带。
        contentRoot.setBackgroundColor(backgroundColor)
        SystemBarAppearanceController.applyForColor(
            activity = this,
            mode = hostConfigStore.hostDisplayMode,
            backgroundColor = backgroundColor
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

        val webViewColor = lastWebViewSystemBarsColorHex
        if (webViewColor.isNullOrBlank()) {
            applyMainSurfaceSystemBars(
                backgroundColor = ContextCompat.getColor(this, R.color.tavern_webview_background)
            )
            return
        }

        applyWebViewSurfaceSystemBars(webViewColor)
    }

    private fun buildAndroidHostVersionInfoJson(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

        return JSONObject()
            .put("hostVersion", appGraph.appUpdateBuildConfig.hostVersion)
            .put("apkVersionName", packageInfo.versionName.orEmpty().trim())
            .put("apkVersionCode", packageInfo.longVersionCode.toString())
            .put("hostDisplayMode", hostConfigStore.hostDisplayMode.name)
            .put("floatingLogBubbleEnabled", hostConfigStore.floatingLogBubbleEnabled)
            .put("webViewPullRefreshEnabled", hostConfigStore.webViewPullRefreshEnabled)
            .put("unrestrictedFileImportSelectionEnabled", hostConfigStore.unrestrictedFileImportSelectionEnabled)
            .put("serverReady", processManager.currentSnapshot().isReady)
            .toString()
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
