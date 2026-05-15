package com.jm.sillydroid.feature.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.SettingsNavigationContract
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadResult
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel
import com.jm.sillydroid.feature.main.ui.home.bootstrap.BootstrapOverlayRenderer
import com.jm.sillydroid.feature.main.ui.home.bootstrap.BootstrapOverlayText
import com.jm.sillydroid.feature.main.ui.home.bootstrap.BootstrapOverlayViews
import com.jm.sillydroid.feature.main.ui.home.download.AndroidBlobDownloadBridge
import com.jm.sillydroid.feature.main.ui.home.download.BlobDownloadController
import com.jm.sillydroid.feature.main.ui.home.download.BrowserDownloadController
import com.jm.sillydroid.feature.main.ui.home.floatinglogs.FloatingLogsController
import com.jm.sillydroid.feature.main.ui.home.floatinglogs.FloatingLogsLayoutController
import com.jm.sillydroid.feature.main.ui.home.floatinglogs.FloatingLogsText
import com.jm.sillydroid.feature.main.ui.home.floatinglogs.FloatingLogsViews
import com.jm.sillydroid.feature.main.ui.home.notification.AndroidSystemNotificationBridge
import com.jm.sillydroid.feature.main.ui.home.notification.SystemNotificationController
import com.jm.sillydroid.feature.main.ui.home.webview.AndroidHostBridge
import com.jm.sillydroid.feature.main.ui.home.webview.HomeWebViewController
import com.jm.sillydroid.feature.main.ui.home.webview.HomeWebViewRefreshController
import com.jm.sillydroid.feature.main.ui.home.webview.WebReloadTracer
import com.jm.sillydroid.feature.main.ui.home.webview.WebSessionPersistenceController
import com.jm.sillydroid.ui.update.AppUpdateCoordinator
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    companion object {
        private const val mainLogTag = "SillyDroidMain"
        private const val downloadBridgeName = "AndroidDownloadBridge"
        private const val webViewStateKey = "tavern.webview.state"
        private const val loadedUrlStateKey = "tavern.webview.loadedUrl"
        private const val webSessionBridgeName = "StaiWebSessionBridge"
        private const val systemNotificationBridgeName = "AndroidSystemNotificationBridge"
        private const val androidHostBridgeName = "SillyDroidAndroidHostBridge"
        private const val webSessionStoragePreferencesName = "sillydroid-webview-session"
        private const val webSessionStorageSnapshotKey = "session-storage"
    }

    private lateinit var contentRoot: View
    private lateinit var webViewRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var bootstrapOverlay: View
    private lateinit var bootstrapStatus: TextView
    private lateinit var bootstrapRetry: Button
    private lateinit var bootstrapUpdateButtonContainer: View
    private lateinit var bootstrapUpdateButton: ImageButton
    private lateinit var bootstrapUpdateBadge: View
    private lateinit var bootstrapSettingsButton: ImageButton
    private lateinit var bootstrapProgress: ProgressBar
    private lateinit var bootstrapProgressLabel: TextView
    private lateinit var floatingLogsBubble: ImageButton
    private lateinit var floatingLogsPanel: View
    private lateinit var floatingLogsMeta: TextView
    private lateinit var floatingLogsSessionSummary: TextView
    private lateinit var floatingLogsEmpty: TextView
    private lateinit var floatingLogsContent: TextView
    private lateinit var floatingLogsScroll: NestedScrollView
    private lateinit var floatingLogsSelectButton: MaterialButton
    private lateinit var floatingLogsIntervalButton: MaterialButton
    private lateinit var floatingLogsCloseButton: ImageButton
    private lateinit var floatingLogsReloadWebViewButton: MaterialButton
    private lateinit var floatingLogsDownloadButton: MaterialButton
    private lateinit var floatingLogsClearButton: MaterialButton
    private lateinit var floatingLogsOpenSettingsButton: MaterialButton
    private lateinit var floatingLogsScrollToBottomButton: ImageButton
    private lateinit var backPressCallback: OnBackPressedCallback
    private var webSessionPersistenceController: WebSessionPersistenceController? = null
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val appGraph: SillyDroidAppGraph
        get() = (application as SillyDroidAppGraphProvider).sillyDroidAppGraph
    private val hostConfigStore by lazy { appGraph.hostConfigStore }
    private val hostLogRepository by lazy { appGraph.hostLogRepository }
    private val processManager by lazy<BootstrapController> { appGraph.bootstrapController }
    private val runtimeConfigRepository by lazy { appGraph.runtimeConfigRepository }
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.Factory(processManager) }
    private var loadedUrl: String
        get() = homeViewModel.loadedUrl
        set(value) { homeViewModel.loadedUrl = value }
    private var hasRestoredWebViewState: Boolean
        get() = homeViewModel.hasRestoredWebViewState
        set(value) { homeViewModel.hasRestoredWebViewState = value }
    private var isOpeningBootstrapSettings: Boolean
        get() = homeViewModel.isOpeningBootstrapSettings
        set(value) { homeViewModel.isOpeningBootstrapSettings = value }
    private var isPullGestureRefreshing: Boolean
        get() = homeViewModel.isPullGestureRefreshing
        set(value) { homeViewModel.isPullGestureRefreshing = value }
    private var isImeVisible: Boolean
        get() = homeViewModel.isImeVisible
        set(value) { homeViewModel.isImeVisible = value }
    // 本地 baseUrl 加载失败后的连续重试计数；onPageFinished 成功后重置。
    // 限制上限防止服务一直起不来时 WebView 不断闪烁。
    private var pendingLocalRetryAttempts: Int
        get() = homeViewModel.pendingLocalRetryAttempts
        set(value) { homeViewModel.pendingLocalRetryAttempts = value }
    private lateinit var appUpdateCoordinator: AppUpdateCoordinator
    private val webSessionStoragePreferences by lazy {
        getSharedPreferences(webSessionStoragePreferencesName, MODE_PRIVATE)
    }
    private val downloadManager by lazy { getSystemService(DownloadManager::class.java) }
    private val browserDownloadController by lazy {
        BrowserDownloadController(
            downloadManager = downloadManager,
            pendingDescription = { fileName -> getString(R.string.download_status_pending, fileName) }
        )
    }
    private val blobDownloadController by lazy {
        BlobDownloadController(contentResolver)
    }
    private val homeWebViewController by lazy {
        HomeWebViewController(
            context = this,
            webViewProvider = { webView },
            installSessionPersistence = ::installWebSessionPersistenceController,
            installJavascriptInterfaces = ::installWebViewJavascriptInterfaces,
            shouldOpenExternally = ::shouldOpenExternally,
            openExternalBrowser = ::openExternalBrowser,
            onPageStarted = { url -> logActiveWebReloadTrace(phase = "page_started", url = url) },
            onPageCommitVisible = { url -> logActiveWebReloadTrace(phase = "page_commit_visible", url = url) },
            onPageFinished = ::handleWebViewPageFinished,
            isLocalTavernUrl = ::isLocalTavernUrl,
            onMainFrameLocalLoadError = ::scheduleLocalWebViewRetry,
            onRendererGone = ::handleWebViewRendererGone,
            onDownloadRequested = ::handlePageDownload,
            onShowFileChooser = { filePathCallback, fileChooserParams ->
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback
                fileChooserLauncher.launch(fileChooserParams.createIntent())
            }
        )
    }
    private val webReloadTracer by lazy {
        WebReloadTracer(mainLogTag)
    }
    private val homeWebViewRefreshController by lazy {
        HomeWebViewRefreshController(
            refreshLayout = webViewRefreshLayout,
            webView = webView,
            bootstrapOverlay = bootstrapOverlay,
            pullRefreshEnabled = { hostConfigStore.webViewPullRefreshEnabled },
            pullGestureRefreshing = { isPullGestureRefreshing },
            setPullGestureRefreshing = { refreshing -> isPullGestureRefreshing = refreshing },
            imeVisible = { isImeVisible },
            reloadTracer = webReloadTracer
        )
    }
    private val bootstrapOverlayRenderer by lazy {
        BootstrapOverlayRenderer(
            views = BootstrapOverlayViews(
                overlay = bootstrapOverlay,
                status = bootstrapStatus,
                retryButton = bootstrapRetry,
                settingsButton = bootstrapSettingsButton,
                progress = bootstrapProgress,
                progressLabel = bootstrapProgressLabel,
                webViewRefreshLayout = webViewRefreshLayout,
                webView = webView
            ),
            text = BootstrapOverlayText(
                pausedMessage = { getString(R.string.bootstrap_paused_message) },
                pausedDetails = { getString(R.string.bootstrap_paused_details) },
                resumeLabel = { getString(R.string.bootstrap_resume) },
                retryLabel = { getString(R.string.bootstrap_retry) },
                progressLabel = { percent -> getString(R.string.bootstrap_progress_label, percent) },
                progressIndeterminate = { getString(R.string.bootstrap_progress_indeterminate) },
                startupElapsed = { seconds -> getString(R.string.bootstrap_startup_elapsed, seconds) },
                tavernLogTail = { line -> getString(R.string.bootstrap_startup_tavern_log_tail, line) }
            ),
            syncSettingsEntryState = ::syncBootstrapSettingsEntryState,
            showWebView = ::showWebView,
            updateWebViewRefreshLayoutEnabled = ::updateWebViewRefreshLayoutEnabled,
            setPullGestureRefreshing = { refreshing -> isPullGestureRefreshing = refreshing },
            onReadyMonitoring = ::maybePromptDefaultExtensionsAfterBootstrapReady
        )
    }
    private val floatingLogsLayoutController by lazy {
        FloatingLogsLayoutController(
            contentRoot = contentRoot,
            bubble = floatingLogsBubble,
            panel = floatingLogsPanel,
            savedPosition = { hostConfigStore.floatingLogBubblePosition },
            savePosition = { position -> hostConfigStore.floatingLogBubblePosition = position },
            panelWidthPx = { resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_width) },
            panelHeightPx = { resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_height) },
            panelHorizontalMarginPx = { resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_horizontal_margin) },
            panelVerticalMarginPx = { resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_vertical_margin) },
            panelGapPx = { 12f * resources.displayMetrics.density }
        )
    }
    private val floatingLogsController by lazy {
        FloatingLogsController(
            activity = this,
            scope = lifecycleScope,
            dispatchers = appGraph.dispatchers,
            preferences = hostConfigStore,
            logRepository = hostLogRepository,
            layoutController = floatingLogsLayoutController,
            views = FloatingLogsViews(
                contentRoot = contentRoot,
                bubble = floatingLogsBubble,
                panel = floatingLogsPanel,
                meta = floatingLogsMeta,
                sessionSummary = floatingLogsSessionSummary,
                empty = floatingLogsEmpty,
                content = floatingLogsContent,
                scroll = floatingLogsScroll,
                selectButton = floatingLogsSelectButton,
                intervalButton = floatingLogsIntervalButton,
                closeButton = floatingLogsCloseButton,
                reloadWebViewButton = floatingLogsReloadWebViewButton,
                downloadButton = floatingLogsDownloadButton,
                clearButton = floatingLogsClearButton,
                openSettingsButton = floatingLogsOpenSettingsButton,
                scrollToBottomButton = floatingLogsScrollToBottomButton
            ),
            text = FloatingLogsText(
                autoSelectLabel = { getString(R.string.floating_logs_panel_auto_select_label) },
                selectDialogTitle = { getString(R.string.floating_logs_panel_select_label) },
                intervalDialogTitle = { getString(R.string.floating_logs_panel_interval_label) },
                realtimeIntervalLabel = { getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_realtime) },
                oneSecondIntervalLabel = { getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_one_second) },
                threeSecondsIntervalLabel = { getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_three_seconds) },
                fiveSecondsIntervalLabel = { getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_five_seconds) },
                logsMeta = { displayName, updatedAt -> getString(R.string.bootstrap_settings_logs_meta, displayName, updatedAt) },
                emptyContent = { getString(R.string.bootstrap_settings_logs_empty_content) },
                downloadSuccess = { zipFileName, zipPath -> getString(R.string.floating_logs_download_success, zipFileName, zipPath) },
                downloadFailed = { getString(R.string.floating_logs_download_failed) },
                clearConfirmTitle = { getString(R.string.bootstrap_settings_logs_clear_confirm_title) },
                clearConfirmMessage = { getString(R.string.bootstrap_settings_logs_clear_confirm_message) },
                clearConfirmPositiveLabel = { getString(R.string.bootstrap_settings_logs_clear) },
                clearSuccess = { getString(R.string.bootstrap_settings_logs_clear_success) },
                clearFailed = { getString(R.string.bootstrap_settings_logs_clear_failed) }
            ),
            currentSnapshot = { processManager.currentSnapshot() },
            canOpenSettings = ::canOpenBootstrapSettings,
            openSettings = { openBootstrapSettings() },
            reloadTavernWebView = { reloadTavernWebView(source = "floating_logs_button") }
        )
    }
    private val systemNotificationController by lazy {
        SystemNotificationController(
            context = this,
            channelId = runtimeConfigRepository.systemNotificationChannelId,
            channelTitle = getString(R.string.system_notification_channel_title),
            channelDescription = getString(R.string.system_notification_channel_description),
            smallIconResId = android.R.drawable.stat_notify_chat,
            launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            pendingIntentRequestCode = runtimeConfigRepository.notificationId
        )
    }
    // Android 13+ 的宿主通知需要显式运行时授权，否则 NotificationManager 会直接拒发。
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        callback.onReceiveValue(resolveFileChooserUris(result.resultCode, result.data))
    }
    private val bootstrapSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isOpeningBootstrapSettings = false
        if (result.resultCode == Activity.RESULT_OK && shouldStartBootstrapFromSettingsResult(result.data)) {
            homeViewModel.resetForBootstrapRestart()
            bootstrapOverlay.isVisible = true
            webViewRefreshLayout.isVisible = false
            webView.isVisible = false
            startBootstrap(true)
            return@registerForActivityResult
        }

        val currentSnapshot = processManager.currentSnapshot()
        renderBootstrapSession(currentSnapshot)
        if (result.resultCode == Activity.RESULT_OK && shouldReloadTavernUiFromSettingsResult(result.data)) {
            reloadTavernUiIfPossible(currentSnapshot)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        appUpdateCoordinator = AppUpdateCoordinator(
            activity = this,
            appUpdateRepository = appGraph.appUpdateRepository,
            runtimeMetadataRepository = appGraph.runtimeMetadataRepository,
            buildConfig = appGraph.appUpdateBuildConfig,
            dispatchers = appGraph.dispatchers,
            overlayUi = AppUpdateCoordinator.OverlayUi(
                container = bootstrapUpdateButtonContainer,
                button = bootstrapUpdateButton,
                badgeView = bootstrapUpdateBadge
            )
        )
        appUpdateCoordinator.initialize()
        applySystemBarInsets()
        floatingLogsController.configure()
        floatingLogsController.refreshVisibility()
        systemNotificationController.ensureChannel()
        requestNotificationPermissionIfNeeded()
        configureWebView()
        registerBackPressHandler()
        restoreWebViewState(savedInstanceState)
        observeBootstrapSession()
        observeBootstrapEvents()
        bootstrapRetry.setOnClickListener { startBootstrap(true) }
        bootstrapSettingsButton.setOnClickListener { openBootstrapSettings() }
        startBootstrap(false)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        floatingLogsController.refreshVisibility()
        updateWebViewRefreshLayoutEnabled()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Activity 被系统回收后，优先恢复 WebView 现有会话，避免重新 load baseUrl 把页面打回首页。
        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(webViewStateKey, webViewState)
        outState.putString(loadedUrlStateKey, loadedUrl)
    }

    override fun onDestroy() {
        webSessionPersistenceController?.close()
        webSessionPersistenceController = null
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        super.onDestroy()
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

    private fun bindViews() {
        contentRoot = findViewById(R.id.contentRoot)
        webViewRefreshLayout = findViewById(R.id.webViewRefreshLayout)
        webView = findViewById(R.id.webView)
        bootstrapOverlay = findViewById(R.id.bootstrapOverlay)
        bootstrapStatus = findViewById(R.id.bootstrapStatus)
        bootstrapRetry = findViewById(R.id.bootstrapRetry)
        bootstrapUpdateButtonContainer = findViewById(R.id.bootstrapUpdateButtonContainer)
        bootstrapUpdateButton = findViewById(R.id.bootstrapUpdateButton)
        bootstrapUpdateBadge = findViewById(R.id.bootstrapUpdateBadge)
        bootstrapSettingsButton = findViewById(R.id.bootstrapSettingsButton)
        bootstrapProgress = findViewById(R.id.bootstrapProgress)
        bootstrapProgressLabel = findViewById(R.id.bootstrapProgressLabel)
        floatingLogsBubble = findViewById(R.id.floatingLogsBubble)
        floatingLogsPanel = findViewById(R.id.floatingLogsPanel)
        floatingLogsMeta = findViewById(R.id.floatingLogsMeta)
        floatingLogsSessionSummary = findViewById(R.id.floatingLogsSessionSummary)
        floatingLogsEmpty = findViewById(R.id.floatingLogsEmpty)
        floatingLogsContent = findViewById(R.id.floatingLogsContent)
        floatingLogsScroll = findViewById(R.id.floatingLogsScroll)
        floatingLogsSelectButton = findViewById(R.id.floatingLogsSelectButton)
        floatingLogsIntervalButton = findViewById(R.id.floatingLogsIntervalButton)
        floatingLogsCloseButton = findViewById(R.id.floatingLogsCloseButton)
        floatingLogsReloadWebViewButton = findViewById(R.id.floatingLogsReloadWebViewButton)
        floatingLogsDownloadButton = findViewById(R.id.floatingLogsDownloadButton)
        floatingLogsClearButton = findViewById(R.id.floatingLogsClearButton)
        floatingLogsOpenSettingsButton = findViewById(R.id.floatingLogsOpenSettingsButton)
        floatingLogsScrollToBottomButton = findViewById(R.id.floatingLogsScrollToBottomButton)
    }

    private fun applySystemBarInsets() {
        val initialLeftPadding = contentRoot.paddingLeft
        val initialTopPadding = contentRoot.paddingTop
        val initialRightPadding = contentRoot.paddingRight
        val initialBottomPadding = contentRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val systemBarsInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeVisibleNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisibleNow != isImeVisible) {
                isImeVisible = imeVisibleNow
                if (imeVisibleNow) {
                    webViewRefreshLayout.isRefreshing = false
                }
                updateWebViewRefreshLayoutEnabled()
            }
            val imeInsets = if (imeVisibleNow) {
                insets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                null
            }
            view.setPadding(
                initialLeftPadding + systemBarsInsets.left,
                initialTopPadding + systemBarsInsets.top,
                initialRightPadding + systemBarsInsets.right,
                initialBottomPadding + systemBarsInsets.bottom + (imeInsets?.bottom ?: 0)
            )
            floatingLogsController.onContentBoundsChanged()
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    private fun configureWebView() {
        val webViewBackgroundColor = ContextCompat.getColor(this, R.color.tavern_webview_background)
        homeWebViewRefreshController.configure(webViewBackgroundColor)
        homeWebViewController.configure()
    }

    private fun installWebViewJavascriptInterfaces(targetWebView: WebView) {
        // Tavern 页面里的导出既可能是普通 URL，也可能是 blob/data；宿主在这里统一接管保存到系统下载目录。
        targetWebView.addJavascriptInterface(
            AndroidBlobDownloadBridge(
                controller = blobDownloadController,
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
                onFailure = ::showDownloadFailure
            ),
            downloadBridgeName
        )
        // 浏览器通知统一走宿主桥，避免 Android WebView 里再退回不可用的 Notification API。
        targetWebView.addJavascriptInterface(
            AndroidSystemNotificationBridge(
                notificationController = systemNotificationController,
                isHostActive = { !isFinishing && !isDestroyed },
                runOnUiThread = { action -> runOnUiThread(action) },
                requestPermission = ::requestNotificationPermissionIfNeeded
            ),
            systemNotificationBridgeName
        )
        // 只暴露 Tavern 需要的最小宿主能力，给 Android 专属扩展调用设置页、日志悬浮球和版本信息。
        targetWebView.addJavascriptInterface(
            AndroidHostBridge(
                isHostActive = { !isFinishing && !isDestroyed },
                runOnUiThread = { action -> runOnUiThread(action) },
                openSettings = ::openBootstrapSettings,
                showFloatingLogsBubble = {
                    floatingLogsController.showBubble()
                },
                setFloatingLogsBubbleEnabled = { enabled ->
                    floatingLogsController.setBubbleEnabled(enabled)
                },
                setWebViewPullRefreshEnabled = { enabled ->
                    hostConfigStore.webViewPullRefreshEnabled = enabled
                    updateWebViewRefreshLayoutEnabled()
                },
                reloadTavern = { reloadTavernWebView(source = "android_host_bridge") },
                hostVersionInfoJson = ::buildAndroidHostVersionInfoJson
            ),
            androidHostBridgeName
        )
    }

    private fun handleWebViewPageFinished(url: String?) {
        logActiveWebReloadTrace(phase = "page_finished", url = url)
        isPullGestureRefreshing = false
        webViewRefreshLayout.isRefreshing = false
        updateWebViewRefreshLayoutEnabled()
        CookieManager.getInstance().flush()
        blobDownloadController.installBridgeScript(webView, downloadBridgeName)
        if (!url.isNullOrBlank()) {
            loadedUrl = url
            pendingLocalRetryAttempts = 0
        }
        clearActiveWebReloadTrace()
    }

    private fun handleWebViewRendererGone(didCrash: Boolean) {
        Log.e(
            mainLogTag,
            "WebView renderer gone (didCrash=$didCrash). Recreating WebView to keep host process alive."
        )
        if (!isFinishing && !isDestroyed) {
            recreateWebViewAfterRendererGone()
        }
    }

    private fun installWebSessionPersistenceController() {
        webSessionPersistenceController?.close()
        webSessionPersistenceController = WebSessionPersistenceController(
            webView = webView,
            preferences = webSessionStoragePreferences,
            storageKey = webSessionStorageSnapshotKey,
            bridgeName = webSessionBridgeName,
            systemNotificationBridgeName = systemNotificationBridgeName,
            allowedOrigin = { runtimeConfigRepository.localServiceUrl() }
        ).also { controller ->
            controller.install()
        }
    }

    @Suppress("DEPRECATION")
    private fun handlePageDownload(request: BrowserDownloadRequest) {
        when (val result = browserDownloadController.enqueue(request)) {
            is BrowserDownloadResult.Started -> {
                Toast.makeText(this, getString(R.string.download_started, result.fileName), Toast.LENGTH_SHORT).show()
            }

            is BrowserDownloadResult.Failed -> {
                showDownloadFailure(
                    DownloadFailureReport(
                        fileName = result.fileName,
                        message = result.message.ifBlank { getString(R.string.download_failed_unknown) }
                    )
                )
            }

            null -> Unit
        }
    }

    private fun buildDownloadFailureMessage(report: DownloadFailureReport): String {
        val details = report.message.trim().ifBlank { getString(R.string.download_failed_unknown) }
        return getString(R.string.download_failed, report.fileName, details)
    }

    private fun showDownloadFailure(report: DownloadFailureReport) {
        Toast.makeText(this, buildDownloadFailureMessage(report), Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun restoreWebViewState(savedInstanceState: Bundle?) {
        val webViewState = savedInstanceState?.getBundle(webViewStateKey) ?: return
        val restoredState = webView.restoreState(webViewState)
        val restoredUrl = restoredState?.currentItem?.url.orEmpty()
            .ifBlank { savedInstanceState.getString(loadedUrlStateKey).orEmpty() }

        if (restoredUrl.isBlank()) {
            return
        }

        // 恢复出来的 URL 可能在上一轮会话中使用了不同的服务端口。
        // 若与当前 localUrl 不匹配，则不能复用，避免 WebView 以旧端口发起请求
        // 造成永久 ERR_CONNECTION_REFUSED 白屏。
        if (!isLocalTavernUrl(restoredUrl)) {
            return
        }

        loadedUrl = restoredUrl
        hasRestoredWebViewState = true
    }

    private fun observeBootstrapSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.bootstrapSnapshot.collect { snapshot ->
                    renderBootstrapSession(snapshot)
                }
            }
        }
    }

    private fun observeBootstrapEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                processManager.events.collect { event ->
                    when (event) {
                        is BootstrapEvent.AutoRestartScheduled,
                        is BootstrapEvent.SettingsPauseRequested -> {
                            webViewRefreshLayout.isRefreshing = false
                            isPullGestureRefreshing = false
                        }

                        else -> Unit
                    }
                }
            }
        }
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
            .put("floatingLogBubbleEnabled", hostConfigStore.floatingLogBubbleEnabled)
            .put("webViewPullRefreshEnabled", hostConfigStore.webViewPullRefreshEnabled)
            .put("serverReady", processManager.currentSnapshot().isReady)
            .toString()
    }

    private fun renderBootstrapSession(snapshot: BootstrapSessionSnapshot) {
        floatingLogsController.renderSessionSummary(snapshot)
        bootstrapOverlayRenderer.render(snapshot)
    }

    private fun maybePromptDefaultExtensionsAfterBootstrapReady() {
        if (hostConfigStore.defaultExtensionsPromptConsumed) {
            return
        }

        hostConfigStore.defaultExtensionsPromptConsumed = true
        val repositoryCount = loadDefaultExtensionRepositoryCount()
        if (repositoryCount <= 0) {
            return
        }
        openBootstrapSettings(openDefaultExtensionsInstaller = true)
    }

    private fun loadDefaultExtensionRepositoryCount(): Int {
        return appGraph.defaultExtensionRepositoryCount()
    }

    private fun showWebView(baseUrl: String) {
        bootstrapOverlay.isVisible = false
        webViewRefreshLayout.isVisible = true
        webView.isVisible = true
        updateWebViewRefreshLayoutEnabled()
        if (hasRestoredWebViewState) {
            // 已恢复出原来的 WebView 会话时，不再重新 load baseUrl，避免把前端状态重置到首页。
            hasRestoredWebViewState = false
            return
        }

        if (isCurrentWebViewPageFor(baseUrl)) {
            return
        }

        val targetUrl = buildInitialWebViewUrl(baseUrl)
        loadedUrl = targetUrl
        webView.loadUrl(targetUrl)
    }

    private fun updateWebViewRefreshLayoutEnabled() {
        homeWebViewRefreshController.updateEnabled()
    }

    private fun logActiveWebReloadTrace(phase: String, url: String? = null, extra: String? = null) {
        webReloadTracer.log(phase = phase, url = url, extra = extra)
    }

    private fun clearActiveWebReloadTrace() {
        webReloadTracer.clear()
    }

    private fun reloadTavernWebView(source: String): Boolean {
        return homeWebViewRefreshController.reload(source)
    }

    private fun startBootstrap(forceRestart: Boolean) {
        processManager.start(forceRestart)
    }

    private fun reloadTavernUiIfPossible(snapshot: BootstrapSessionSnapshot) {
        if (!snapshot.isReady || !webView.isVisible) {
            return
        }

        reloadTavernWebView(source = "host_state_ready")
    }

    private fun canOpenBootstrapSettings(snapshot: BootstrapSessionSnapshot): Boolean {
        return snapshot.derivedUiFlags.canOpenSettings && !isOpeningBootstrapSettings
    }

    private fun syncBootstrapSettingsEntryState(snapshot: BootstrapSessionSnapshot) {
        val settingsEnabled = canOpenBootstrapSettings(snapshot)
        bootstrapSettingsButton.isEnabled = settingsEnabled
        bootstrapSettingsButton.alpha = if (settingsEnabled) 1f else 0.35f
        floatingLogsController.syncSettingsEntryState(snapshot)
    }

    private fun openBootstrapSettings(openDefaultExtensionsInstaller: Boolean = false) {
        if (isOpeningBootstrapSettings) {
            return
        }

        val snapshot = processManager.currentSnapshot()
        if (!canOpenBootstrapSettings(snapshot)) {
            syncBootstrapSettingsEntryState(snapshot)
            return
        }

        isOpeningBootstrapSettings = true
        syncBootstrapSettingsEntryState(snapshot)
        val settingsIntent = appGraph.createSettingsIntent(
            activity = this,
            openExtensionsTab = openDefaultExtensionsInstaller,
            openDefaultExtensionsInstaller = openDefaultExtensionsInstaller
        )
        lifecycleScope.launch {
            val pauseResult = runCatching {
                processManager.stopForSettingsAndAwait(getString(R.string.bootstrap_settings_open_stop_timeout))
            }
            pauseResult.onFailure { exception ->
                isOpeningBootstrapSettings = false
                syncBootstrapSettingsEntryState(processManager.currentSnapshot())
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@MainActivity,
                        exception.message ?: getString(R.string.bootstrap_settings_open_stop_timeout),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onSuccess {
                if (isFinishing || isDestroyed) {
                    isOpeningBootstrapSettings = false
                    syncBootstrapSettingsEntryState(processManager.currentSnapshot())
                    return@launch
                }
                bootstrapSettingsLauncher.launch(settingsIntent)
            }
        }
    }

    private fun shouldStartBootstrapFromSettingsResult(data: Intent?): Boolean {
        return data?.getBooleanExtra(SettingsNavigationContract.resultShouldStartKey, false) == true
    }

    private fun shouldReloadTavernUiFromSettingsResult(data: Intent?): Boolean {
        return data?.getBooleanExtra(SettingsNavigationContract.resultShouldReloadTavernUiKey, false) == true
    }

    private fun isCurrentWebViewPageFor(baseUrl: String): Boolean {
        val currentUrl = webView.url.orEmpty().ifBlank { loadedUrl }
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

    private fun shouldOpenExternally(targetUri: Uri): Boolean {
        return !isLocalTavernUri(targetUri)
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

    private fun scheduleLocalWebViewRetry(failingUrl: String) {
        if (pendingLocalRetryAttempts >= 5) {
            // 估计是服务侧長期起不来；交给 startup overlay 接手，不再闪烁。
            return
        }
        if (!processManager.currentSnapshot().isReady) {
            return
        }
        pendingLocalRetryAttempts += 1
        val delayMillis = (500L * pendingLocalRetryAttempts).coerceAtMost(3_000L)
        webView.postDelayed(
            {
                if (isFinishing || isDestroyed) {
                    return@postDelayed
                }
                if (!processManager.currentSnapshot().isReady) {
                    return@postDelayed
                }
                if (failingUrl == loadedUrl || failingUrl.startsWith(loadedUrl.trimEnd('/'))) {
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
        val parent = crashedWebView.parent as? android.view.ViewGroup
        val indexInParent = parent?.indexOfChild(crashedWebView) ?: -1
        val layoutParams = crashedWebView.layoutParams
        val targetUrl = processManager.currentSnapshot().localUrl
            .ifBlank { runtimeConfigRepository.localServiceUrl() }

        // 旧 WebView 必须先从视图树移除再 destroy，避免 native 资源泄漏。
        webSessionPersistenceController?.close()
        webSessionPersistenceController = null
        parent?.removeView(crashedWebView)
        runCatching { crashedWebView.destroy() }

        val newWebView = WebView(this).apply {
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
        configureWebView()
        homeViewModel.resetAfterRendererRecreated()
        if (processManager.currentSnapshot().isReady) {
            showWebView(targetUrl)
        }
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

    private fun openExternalBrowser(targetUri: Uri): Boolean {
        return try {
            startActivity(
                Intent(Intent.ACTION_VIEW, targetUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.browser_open_external_failed, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun resolveFileChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) {
            return null
        }

        val clipData = data?.clipData
        if (clipData != null) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }

        val selectedUri = data?.data ?: return emptyArray()
        return arrayOf(selectedUri)
    }
}

