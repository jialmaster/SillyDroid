package com.jm.sillydroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Message
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private enum class FloatingLogsBubbleDockSide {
        LEFT,
        RIGHT
    }

    private data class BrowserDownloadRequest(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private data class BlobDownloadRequest(
        val fileName: String,
        val mimeType: String,
        val base64Data: String
    )

    private data class DownloadFailureReport(
        val fileName: String,
        val message: String
    )

    private data class SystemNotificationRequest(
        val notificationId: String,
        val title: String,
        val body: String,
        val tag: String
    )

    companion object {
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
    private lateinit var floatingLogsBubble: TextView
    private lateinit var floatingLogsPanel: View
    private lateinit var floatingLogsMeta: TextView
    private lateinit var floatingLogsEmpty: TextView
    private lateinit var floatingLogsContent: TextView
    private lateinit var floatingLogsScroll: NestedScrollView
    private lateinit var floatingLogsSelectButton: MaterialButton
    private lateinit var floatingLogsIntervalButton: MaterialButton
    private lateinit var floatingLogsCloseButton: ImageButton
    private lateinit var backPressCallback: OnBackPressedCallback
    private var webSessionScriptHandler: ScriptHandler? = null
    private var loadedUrl = ""
    private var hasRestoredWebViewState = false
    private var skipNextWebViewStateRestore = false
    private var isOpeningBootstrapSettings = false
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var floatingLogsRefreshJob: Job? = null
    private var floatingLogsRealtimeRenderJob: Job? = null
    private var floatingLogsRealtimeRenderPending = false
    private var floatingLogsFileObserver: FileObserver? = null
    private var lastFloatingLogSnapshot: HostLogSnapshot? = null
    private var floatingLogsAvailableEntries: List<HostLogEntry> = emptyList()
    private var floatingLogsSelectedLogPath: String? = null
    private var floatingLogsAutoScrollEnabled = true
    private var floatingLogsBubbleDockSide = FloatingLogsBubbleDockSide.RIGHT
    private val floatingLogsBubbleTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val floatingLogsPanelGapPx by lazy { 12f * resources.displayMetrics.density }
    private val floatingLogsBubbleHiddenWidthPx by lazy { floatingLogsBubble.width / 2f }
    private val floatingLogsBubbleRevealInterpolator = OvershootInterpolator(0.9f)
    private val floatingLogsBubbleDockInterpolator = OvershootInterpolator(0.55f)

    private data class FloatingLogsPanelSize(
        val width: Int,
        val height: Int,
        val layoutChanged: Boolean
    )
    private val hostConfigStore by lazy { BootstrapHostConfigStore(this) }
    private val defaultExtensionsProgressHost = DefaultExtensionsProgressHost()
    private val defaultExtensionsCoordinator by lazy {
        BootstrapSettingsExtensionsCoordinator.createHeadless(
            activity = this,
            progressHost = defaultExtensionsProgressHost,
            showError = ::showDefaultExtensionsError,
            showBanner = ::showDefaultExtensionsMessage,
            showMessage = ::showDefaultExtensionsMessage,
            onTavernUiReloadRequired = {
                reloadTavernUiIfPossible(StartupRuntimeStore.state.value)
            }
        )
    }
    private lateinit var appUpdateCoordinator: AppUpdateCoordinator
    private val webSessionStoragePreferences by lazy {
        getSharedPreferences(webSessionStoragePreferencesName, MODE_PRIVATE)
    }
    private val downloadManager by lazy { getSystemService(DownloadManager::class.java) }
    // Android 13+ 的宿主通知需要显式运行时授权，否则 NotificationManager 会直接拒发。
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        callback.onReceiveValue(resolveFileChooserUris(result.resultCode, result.data))
    }
    private val bootstrapSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isOpeningBootstrapSettings = false
        if (result.resultCode == Activity.RESULT_OK && BootstrapSettingsActivity.shouldStartBootstrap(result.data)) {
            skipNextWebViewStateRestore = true
            hasRestoredWebViewState = false
            loadedUrl = ""
            recreate()
            return@registerForActivityResult
        }

        val currentState = StartupRuntimeStore.state.value
        renderBootstrapState(currentState)
        if (result.resultCode == Activity.RESULT_OK && BootstrapSettingsActivity.shouldReloadTavernUi(result.data)) {
            reloadTavernUiIfPossible(currentState)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        appUpdateCoordinator = AppUpdateCoordinator(
            activity = this,
            downloadManager = downloadManager,
            overlayUi = AppUpdateCoordinator.OverlayUi(
                container = bootstrapUpdateButtonContainer,
                button = bootstrapUpdateButton,
                badgeView = bootstrapUpdateBadge
            )
        )
        appUpdateCoordinator.initialize()
        applySystemBarInsets()
        configureFloatingLogsUi()
        refreshFloatingLogsVisibility()
        ensureSystemNotificationChannel()
        requestNotificationPermissionIfNeeded()
        configureWebView()
        registerBackPressHandler()
        restoreWebViewState(savedInstanceState)
        observeBootstrapState()
        bootstrapRetry.setOnClickListener { startBootstrap(true) }
        bootstrapSettingsButton.setOnClickListener { openBootstrapSettings() }
        startBootstrap(false)
    }

    override fun onStart() {
        super.onStart()
        appUpdateCoordinator.onStart()
        if (floatingLogsPanel.isVisible) {
            startFloatingLogsRefreshLoop()
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateCoordinator.onResume()
        refreshFloatingLogsVisibility()
    }

    override fun onStop() {
        appUpdateCoordinator.onStop()
        stopFloatingLogsRefreshLoop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (skipNextWebViewStateRestore) {
            outState.remove(webViewStateKey)
            outState.remove(loadedUrlStateKey)
            return
        }

        // Activity 被系统回收后，优先恢复 WebView 现有会话，避免重新 load baseUrl 把页面打回首页。
        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(webViewStateKey, webViewState)
        outState.putString(loadedUrlStateKey, loadedUrl)
    }

    override fun onDestroy() {
        webSessionScriptHandler?.remove()
        webSessionScriptHandler = null
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        defaultExtensionsProgressHost.hide()
        appUpdateCoordinator.onDestroy()
        stopFloatingLogsRefreshLoop()
        skipNextWebViewStateRestore = false
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
        floatingLogsEmpty = findViewById(R.id.floatingLogsEmpty)
        floatingLogsContent = findViewById(R.id.floatingLogsContent)
        floatingLogsScroll = findViewById(R.id.floatingLogsScroll)
        floatingLogsSelectButton = findViewById(R.id.floatingLogsSelectButton)
        floatingLogsIntervalButton = findViewById(R.id.floatingLogsIntervalButton)
        floatingLogsCloseButton = findViewById(R.id.floatingLogsCloseButton)
    }

    private fun configureFloatingLogsUi() {
        val disableAutoScrollTouchListener = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                floatingLogsAutoScrollEnabled = false
            }
            false
        }
        floatingLogsBubble.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var downViewX = 0f
            private var downViewY = 0f
            private var dragging = false
            private var longPressTriggered = false
            private var longPressRunnable: Runnable? = null

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        floatingLogsBubble.animate().cancel()
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downViewX = view.x
                        downViewY = view.y
                        dragging = false
                        longPressTriggered = false
                        longPressRunnable?.let(view::removeCallbacks)
                        longPressRunnable = Runnable {
                            if (!dragging && !longPressTriggered) {
                                longPressTriggered = true
                                openBootstrapSettings()
                            }
                        }.also { runnable ->
                            view.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY
                        if (!dragging && (abs(deltaX) > floatingLogsBubbleTouchSlop || abs(deltaY) > floatingLogsBubbleTouchSlop)) {
                            dragging = true
                            longPressRunnable?.let(view::removeCallbacks)
                        }
                        if (dragging) {
                            moveFloatingLogsBubbleTo(downViewX + deltaX, downViewY + deltaY)
                            if (floatingLogsPanel.isVisible) {
                                repositionFloatingLogsPanel()
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let(view::removeCallbacks)
                        longPressRunnable = null
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        if (!dragging && !longPressTriggered) {
                            view.performClick()
                        } else if (dragging) {
                            floatingLogsBubbleDockSide = resolveFloatingLogsBubbleDockSide(view.x + view.width / 2f)
                            persistFloatingLogsBubblePosition()
                            alignFloatingLogsBubbleToDockState(animated = true)
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let(view::removeCallbacks)
                        longPressRunnable = null
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        if (dragging) {
                            floatingLogsBubbleDockSide = resolveFloatingLogsBubbleDockSide(view.x + view.width / 2f)
                            persistFloatingLogsBubblePosition()
                            alignFloatingLogsBubbleToDockState(animated = true)
                        }
                        return true
                    }
                }
                return false
            }
        })
        floatingLogsBubble.setOnClickListener {
            if (floatingLogsPanel.isVisible) {
                setFloatingLogsPanelVisible(false)
            } else {
                revealFloatingLogsBubbleAndOpenPanel()
            }
        }
        floatingLogsCloseButton.setOnClickListener {
            setFloatingLogsPanelVisible(false)
        }
        floatingLogsPanel.setOnTouchListener(disableAutoScrollTouchListener)
        floatingLogsScroll.setOnTouchListener(disableAutoScrollTouchListener)
        floatingLogsContent.setOnTouchListener(disableAutoScrollTouchListener)
        floatingLogsScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (isFloatingLogsScrolledToBottom()) {
                floatingLogsAutoScrollEnabled = true
            }
        }
        configureFloatingLogsControlButtons()
        updateFloatingLogsControlLabels()
    }

    private fun configureFloatingLogsControlButtons() {
        floatingLogsSelectButton.setOnClickListener {
            showFloatingLogsSelectDialog()
        }
        floatingLogsIntervalButton.setOnClickListener {
            showFloatingLogsIntervalDialog()
        }
    }

    private fun refreshFloatingLogsVisibility() {
        val enabled = hostConfigStore.floatingLogBubbleEnabled
        floatingLogsBubble.isVisible = enabled
        if (!enabled) {
            setFloatingLogsPanelVisible(false)
            return
        }

        floatingLogsBubble.post {
            restoreFloatingLogsBubblePosition()
            if (floatingLogsPanel.isVisible) {
                repositionFloatingLogsPanel()
            }
        }
    }

    private fun setFloatingLogsPanelVisible(visible: Boolean) {
        val shouldShow = visible && hostConfigStore.floatingLogBubbleEnabled
        if (shouldShow) {
            revealFloatingLogsBubble(animated = true) {
                floatingLogsPanel.alpha = 0f
                floatingLogsPanel.isVisible = true
                repositionFloatingLogsPanel {
                    floatingLogsPanel.alpha = 1f
                    floatingLogsAutoScrollEnabled = true
                    scrollFloatingLogsToBottom()
                    startFloatingLogsRefreshLoop()
                }
            }
        } else {
            floatingLogsPanel.alpha = 1f
            floatingLogsPanel.isVisible = false
            stopFloatingLogsRefreshLoop()
            dockFloatingLogsBubble(animated = true)
        }
    }

    private fun revealFloatingLogsBubbleAndOpenPanel() {
        setFloatingLogsPanelVisible(true)
    }

    private fun updateFloatingLogsPanelLayout(): FloatingLogsPanelSize? {
        if (contentRoot.width <= 0 || contentRoot.height <= 0) {
            return null
        }

        val desiredWidth = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_width)
        val desiredHeight = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_height)
        val horizontalMargin = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_horizontal_margin)
        val verticalMargin = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_vertical_margin)
        val availableWidth = (contentRoot.width - contentRoot.paddingLeft - contentRoot.paddingRight - horizontalMargin).coerceAtLeast(0)
        val availableHeight = (contentRoot.height - contentRoot.paddingTop - contentRoot.paddingBottom - verticalMargin).coerceAtLeast(0)
        val targetWidth = desiredWidth.coerceAtMost(availableWidth)
        val targetHeight = desiredHeight.coerceAtMost(availableHeight)
        if (targetWidth <= 0 || targetHeight <= 0) {
            return null
        }
        val layoutParams = floatingLogsPanel.layoutParams
        var layoutChanged = false
        if (layoutParams.width != targetWidth || layoutParams.height != targetHeight) {
            layoutParams.width = targetWidth
            layoutParams.height = targetHeight
            floatingLogsPanel.layoutParams = layoutParams
            layoutChanged = true
        }
        return FloatingLogsPanelSize(width = targetWidth, height = targetHeight, layoutChanged = layoutChanged)
    }

    private fun moveFloatingLogsBubbleTo(targetX: Float, targetY: Float) {
        if (contentRoot.width <= 0 || contentRoot.height <= 0 || floatingLogsBubble.width <= 0 || floatingLogsBubble.height <= 0) {
            return
        }

        val minX = contentRoot.paddingLeft.toFloat()
        val maxX = (contentRoot.width - contentRoot.paddingRight - floatingLogsBubble.width).toFloat().coerceAtLeast(minX)
        val minY = contentRoot.paddingTop.toFloat()
        val maxY = (contentRoot.height - contentRoot.paddingBottom - floatingLogsBubble.height).toFloat().coerceAtLeast(minY)

        floatingLogsBubble.x = targetX.coerceIn(minX, maxX)
        floatingLogsBubble.y = targetY.coerceIn(minY, maxY)
    }

    private fun alignFloatingLogsBubbleToDockState(animated: Boolean) {
        if (floatingLogsPanel.isVisible) {
            revealFloatingLogsBubble(animated) {
                repositionFloatingLogsPanel()
            }
        } else {
            dockFloatingLogsBubble(animated)
        }
    }

    private fun dockFloatingLogsBubble(animated: Boolean) {
        animateFloatingLogsBubbleX(
            targetX = resolveDockedBubbleX(floatingLogsBubbleDockSide),
            animated = animated,
            durationMs = 220L,
            interpolator = floatingLogsBubbleDockInterpolator,
            endAction = null
        )
    }

    private fun revealFloatingLogsBubble(animated: Boolean, onEnd: (() -> Unit)? = null) {
        animateFloatingLogsBubbleX(
            targetX = resolveExposedBubbleX(floatingLogsBubbleDockSide),
            animated = animated,
            durationMs = 240L,
            interpolator = floatingLogsBubbleRevealInterpolator,
            endAction = onEnd
        )
    }

    private fun animateFloatingLogsBubbleX(
        targetX: Float,
        animated: Boolean,
        durationMs: Long,
        interpolator: OvershootInterpolator,
        endAction: (() -> Unit)?
    ) {
        if (floatingLogsBubble.width <= 0 || contentRoot.width <= 0) {
            floatingLogsBubble.x = targetX
            endAction?.invoke()
            return
        }

        floatingLogsBubble.animate().cancel()
        if (!animated || abs(floatingLogsBubble.x - targetX) < 1f) {
            floatingLogsBubble.x = targetX
            endAction?.invoke()
            return
        }

        floatingLogsBubble.animate()
            .x(targetX)
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .withEndAction {
                endAction?.invoke()
            }
            .start()
    }

    private fun resolveDockedBubbleX(side: FloatingLogsBubbleDockSide): Float {
        val minX = contentRoot.paddingLeft.toFloat()
        val maxX = (contentRoot.width - contentRoot.paddingRight - floatingLogsBubble.width).toFloat().coerceAtLeast(minX)
        return when (side) {
            FloatingLogsBubbleDockSide.LEFT -> minX - floatingLogsBubbleHiddenWidthPx
            FloatingLogsBubbleDockSide.RIGHT -> maxX + floatingLogsBubbleHiddenWidthPx
        }
    }

    private fun resolveExposedBubbleX(side: FloatingLogsBubbleDockSide): Float {
        val minX = contentRoot.paddingLeft.toFloat()
        val maxX = (contentRoot.width - contentRoot.paddingRight - floatingLogsBubble.width).toFloat().coerceAtLeast(minX)
        return when (side) {
            FloatingLogsBubbleDockSide.LEFT -> minX
            FloatingLogsBubbleDockSide.RIGHT -> maxX
        }
    }

    private fun resolveFloatingLogsBubbleDockSide(bubbleCenterX: Float): FloatingLogsBubbleDockSide {
        val contentCenterX = contentRoot.width / 2f
        return if (bubbleCenterX <= contentCenterX) {
            FloatingLogsBubbleDockSide.LEFT
        } else {
            FloatingLogsBubbleDockSide.RIGHT
        }
    }

    private fun restoreFloatingLogsBubblePosition() {
        val savedPosition = hostConfigStore.floatingLogBubblePosition
        if (contentRoot.width <= 0 || contentRoot.height <= 0 || floatingLogsBubble.width <= 0 || floatingLogsBubble.height <= 0) {
            return
        }

        floatingLogsBubbleDockSide = when {
            savedPosition == null -> floatingLogsBubbleDockSide
            savedPosition.horizontalFraction < 0.5f -> FloatingLogsBubbleDockSide.LEFT
            else -> FloatingLogsBubbleDockSide.RIGHT
        }

        val minX = contentRoot.paddingLeft.toFloat()
        val minY = contentRoot.paddingTop.toFloat()
        val maxY = (contentRoot.height - contentRoot.paddingBottom - floatingLogsBubble.height).toFloat().coerceAtLeast(minY)
        val rangeY = (maxY - minY).coerceAtLeast(0f)
        val targetY = if (savedPosition == null) {
            floatingLogsBubble.y.coerceIn(minY, maxY)
        } else {
            minY + rangeY * savedPosition.verticalFraction
        }

        moveFloatingLogsBubbleTo(
            targetX = resolveExposedBubbleX(floatingLogsBubbleDockSide),
            targetY = targetY
        )
        alignFloatingLogsBubbleToDockState(animated = false)
    }

    private fun persistFloatingLogsBubblePosition() {
        if (contentRoot.width <= 0 || contentRoot.height <= 0 || floatingLogsBubble.width <= 0 || floatingLogsBubble.height <= 0) {
            return
        }

        val minY = contentRoot.paddingTop.toFloat()
        val maxY = (contentRoot.height - contentRoot.paddingBottom - floatingLogsBubble.height).toFloat().coerceAtLeast(minY)
        val rangeY = maxY - minY

        hostConfigStore.floatingLogBubblePosition = BootstrapHostConfigStore.FloatingLogBubblePosition(
            horizontalFraction = if (floatingLogsBubbleDockSide == FloatingLogsBubbleDockSide.LEFT) 0f else 1f,
            verticalFraction = if (rangeY <= 0f) 1f else ((floatingLogsBubble.y - minY) / rangeY).coerceIn(0f, 1f)
        )
    }

    private fun repositionFloatingLogsPanel(onPositioned: (() -> Unit)? = null) {
        floatingLogsPanel.post {
            if (!floatingLogsPanel.isVisible || contentRoot.width <= 0 || contentRoot.height <= 0) {
                return@post
            }
            val panelSize = updateFloatingLogsPanelLayout() ?: return@post
            if (panelSize.layoutChanged) {
                floatingLogsPanel.post {
                    repositionFloatingLogsPanel(onPositioned)
                }
                return@post
            }
            if (floatingLogsBubble.width <= 0 || floatingLogsBubble.height <= 0) {
                return@post
            }

            val horizontalInset = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_horizontal_margin) / 2f
            val verticalInset = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_vertical_margin) / 2f
            val minX = contentRoot.paddingLeft + horizontalInset
            val maxX = (contentRoot.width - contentRoot.paddingRight - panelSize.width - horizontalInset).toFloat().coerceAtLeast(minX)
            val minY = contentRoot.paddingTop + verticalInset
            val maxY = (contentRoot.height - contentRoot.paddingBottom - panelSize.height - verticalInset).toFloat().coerceAtLeast(minY)

            val preferredX = floatingLogsBubble.x + (floatingLogsBubble.width - panelSize.width) / 2f
            val preferredAboveY = floatingLogsBubble.y - panelSize.height - floatingLogsPanelGapPx
            val preferredBelowY = floatingLogsBubble.y + floatingLogsBubble.height + floatingLogsPanelGapPx
            val resolvedY = when {
                preferredAboveY >= minY -> preferredAboveY
                preferredBelowY <= maxY -> preferredBelowY
                else -> maxY
            }

            floatingLogsPanel.x = preferredX.coerceIn(minX, maxX)
            floatingLogsPanel.y = resolvedY.coerceIn(minY, maxY)
            onPositioned?.invoke()
        }
    }

    private fun startFloatingLogsRefreshLoop() {
        stopFloatingLogsRefreshLoop()
        if (!floatingLogsPanel.isVisible) {
            return
        }

        if (hostConfigStore.floatingLogRefreshIntervalMillis == BootstrapHostConfigStore.floatingLogRefreshIntervalRealtimeMillis) {
            startFloatingLogsRealtimeObserver()
            requestFloatingLogsRealtimeRefresh()
            return
        }

        floatingLogsRefreshJob = lifecycleScope.launch {
            while (isActive && floatingLogsPanel.isVisible) {
                renderFloatingLatestLog()
                delay(hostConfigStore.floatingLogRefreshIntervalMillis.toLong())
            }
        }
    }

    private fun stopFloatingLogsRefreshLoop() {
        floatingLogsRefreshJob?.cancel()
        floatingLogsRefreshJob = null
        floatingLogsRealtimeRenderJob?.cancel()
        floatingLogsRealtimeRenderJob = null
        floatingLogsRealtimeRenderPending = false
        floatingLogsFileObserver?.stopWatching()
        floatingLogsFileObserver = null
    }

    private fun startFloatingLogsRealtimeObserver() {
        if (floatingLogsFileObserver != null) {
            return
        }

        val logsDir = File(filesDir, "android-tavern/logs").apply {
            mkdirs()
        }
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE
        floatingLogsFileObserver = object : FileObserver(logsDir, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) {
                    val selectedFileName = floatingLogsSelectedLogPath?.let { File(it).name }
                    if (selectedFileName != null) {
                        if (!path.equals(selectedFileName, ignoreCase = true)) {
                            return
                        }
                    } else if (!path.endsWith(".log", ignoreCase = true)) {
                        return
                    }
                }

                contentRoot.post {
                    if (floatingLogsPanel.isVisible &&
                        hostConfigStore.floatingLogRefreshIntervalMillis == BootstrapHostConfigStore.floatingLogRefreshIntervalRealtimeMillis
                    ) {
                        requestFloatingLogsRealtimeRefresh()
                    }
                }
            }
        }.also { observer ->
            observer.startWatching()
        }
    }

    private fun requestFloatingLogsRealtimeRefresh(resetAutoScroll: Boolean = false) {
        if (resetAutoScroll) {
            floatingLogsAutoScrollEnabled = true
        }
        if (!floatingLogsPanel.isVisible) {
            return
        }

        if (floatingLogsRealtimeRenderJob?.isActive == true) {
            floatingLogsRealtimeRenderPending = true
            return
        }

        floatingLogsRealtimeRenderJob = lifecycleScope.launch {
            renderFloatingLatestLog()
        }.also { job ->
            job.invokeOnCompletion {
                floatingLogsRealtimeRenderJob = null
                if (floatingLogsRealtimeRenderPending) {
                    floatingLogsRealtimeRenderPending = false
                    contentRoot.post {
                        requestFloatingLogsRealtimeRefresh()
                    }
                }
            }
        }
    }

    private suspend fun renderFloatingLatestLog() {
        val preferTavernServerLog = StartupRuntimeStore.state.value.isReady
        val result = withContext(Dispatchers.IO) {
            val entries = HostLogReader.listEntries(this@MainActivity)
            val selectedEntry = floatingLogsSelectedLogPath?.let { selectedPath ->
                entries.firstOrNull { entry -> entry.sourceFile.absolutePath == selectedPath }
            }
            val snapshot = when {
                selectedEntry != null -> HostLogReader.readSnapshot(
                    context = this@MainActivity,
                    logFile = selectedEntry.sourceFile,
                    maxChars = 200_000,
                    entry = selectedEntry
                )
                else -> HostLogReader.readPreferredSnapshot(
                    context = this@MainActivity,
                    preferTavernServerLog = preferTavernServerLog,
                    maxChars = 200_000,
                    entries = entries
                )
            }
            Triple(entries, selectedEntry, snapshot)
        }
        val entries = result.first
        val selectedEntry = result.second
        val snapshot = result.third
        floatingLogsAvailableEntries = entries
        if (floatingLogsSelectedLogPath != null && selectedEntry == null) {
            floatingLogsSelectedLogPath = null
        }
        updateFloatingLogsControlLabels()

        if (snapshot == lastFloatingLogSnapshot) {
            if (floatingLogsAutoScrollEnabled) {
                scrollFloatingLogsToBottom()
            }
            return
        }

        lastFloatingLogSnapshot = snapshot
        floatingLogsEmpty.isVisible = snapshot == null
        floatingLogsMeta.isVisible = snapshot != null
        floatingLogsContent.isVisible = snapshot != null

        if (snapshot == null) {
            floatingLogsMeta.text = ""
            floatingLogsContent.text = ""
            return
        }

        floatingLogsMeta.text = getString(R.string.bootstrap_settings_logs_meta, snapshot.displayName, snapshot.updatedAt)
        floatingLogsContent.text = snapshot.content.ifBlank { getString(R.string.bootstrap_settings_logs_empty_content) }
        if (floatingLogsAutoScrollEnabled) {
            scrollFloatingLogsToBottom()
        }
    }

    private fun scrollFloatingLogsToBottom() {
        floatingLogsContent.post {
            if (!floatingLogsPanel.isVisible) {
                return@post
            }

            val visibleHeight = floatingLogsScroll.height - floatingLogsScroll.paddingTop - floatingLogsScroll.paddingBottom
            if (visibleHeight <= 0) {
                return@post
            }

            val layoutBottom = floatingLogsContent.layout?.let { layout ->
                if (floatingLogsContent.lineCount > 0) {
                    layout.getLineBottom(floatingLogsContent.lineCount - 1)
                } else {
                    floatingLogsContent.height
                }
            } ?: floatingLogsContent.height
            val targetScrollY = (layoutBottom + floatingLogsContent.paddingBottom - visibleHeight).coerceAtLeast(0)
            floatingLogsScroll.scrollTo(0, targetScrollY)
        }
    }

    private fun isFloatingLogsScrolledToBottom(): Boolean {
        val contentView = floatingLogsScroll.getChildAt(0) ?: return true
        val remainingScroll = contentView.bottom - (floatingLogsScroll.height + floatingLogsScroll.scrollY)
        val tolerancePx = (8 * resources.displayMetrics.density).toInt()
        return remainingScroll <= tolerancePx
    }

    private fun refreshFloatingLogsNow(resetAutoScroll: Boolean = false) {
        if (hostConfigStore.floatingLogRefreshIntervalMillis == BootstrapHostConfigStore.floatingLogRefreshIntervalRealtimeMillis) {
            requestFloatingLogsRealtimeRefresh(resetAutoScroll)
            return
        }
        if (resetAutoScroll) {
            floatingLogsAutoScrollEnabled = true
        }
        lifecycleScope.launch {
            renderFloatingLatestLog()
        }
    }

    private fun updateFloatingLogsControlLabels() {
        val selectedLogLabel = floatingLogsSelectedLogPath?.let { selectedPath ->
            floatingLogsAvailableEntries.firstOrNull { entry -> entry.sourceFile.absolutePath == selectedPath }?.displayName
        } ?: getString(R.string.floating_logs_panel_auto_select_label)

        floatingLogsSelectButton.text = selectedLogLabel
        floatingLogsSelectButton.isEnabled = floatingLogsAvailableEntries.isNotEmpty()
        floatingLogsIntervalButton.text = floatingLogsRefreshIntervalLabel(hostConfigStore.floatingLogRefreshIntervalMillis)
        floatingLogsIntervalButton.isEnabled = true
    }

    private fun showFloatingLogsSelectDialog() {
        if (floatingLogsAvailableEntries.isEmpty()) {
            return
        }

        val optionLabels = buildList {
            add(getString(R.string.floating_logs_panel_auto_select_label))
            floatingLogsAvailableEntries.forEach { entry ->
                add(entry.displayName)
            }
        }
        val checkedItem = floatingLogsSelectedLogPath?.let { selectedPath ->
            floatingLogsAvailableEntries.indexOfFirst { entry -> entry.sourceFile.absolutePath == selectedPath }
                .takeIf { index -> index >= 0 }
                ?.plus(1)
        } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.floating_logs_panel_select_label)
            .setSingleChoiceItems(optionLabels.toTypedArray(), checkedItem) { dialog, which ->
                val selectedPath = if (which == 0) {
                    null
                } else {
                    floatingLogsAvailableEntries.getOrNull(which - 1)?.sourceFile?.absolutePath
                }
                if (floatingLogsSelectedLogPath != selectedPath) {
                    floatingLogsSelectedLogPath = selectedPath
                    updateFloatingLogsControlLabels()
                    refreshFloatingLogsNow(resetAutoScroll = true)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFloatingLogsIntervalDialog() {
        val intervalOptions = BootstrapHostConfigStore.floatingLogRefreshIntervalOptions
        val optionLabels = intervalOptions.map(::floatingLogsRefreshIntervalLabel)
        val checkedItem = intervalOptions.indexOf(hostConfigStore.floatingLogRefreshIntervalMillis).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.floating_logs_panel_interval_label)
            .setSingleChoiceItems(optionLabels.toTypedArray(), checkedItem) { dialog, which ->
                val interval = intervalOptions.getOrNull(which) ?: return@setSingleChoiceItems
                if (hostConfigStore.floatingLogRefreshIntervalMillis != interval) {
                    hostConfigStore.floatingLogRefreshIntervalMillis = interval
                    updateFloatingLogsControlLabels()
                    if (floatingLogsPanel.isVisible) {
                        stopFloatingLogsRefreshLoop()
                        startFloatingLogsRefreshLoop()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun floatingLogsRefreshIntervalLabel(intervalMillis: Int): String {
        return when (intervalMillis) {
            BootstrapHostConfigStore.floatingLogRefreshIntervalRealtimeMillis -> getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_realtime)
            BootstrapHostConfigStore.floatingLogRefreshIntervalThreeSecondsMillis -> getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_three_seconds)
            BootstrapHostConfigStore.floatingLogRefreshIntervalFiveSecondsMillis -> getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_five_seconds)
            else -> getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_one_second)
        }
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
            view.setPadding(
                initialLeftPadding + systemBarsInsets.left,
                initialTopPadding + systemBarsInsets.top,
                initialRightPadding + systemBarsInsets.right,
                initialBottomPadding + systemBarsInsets.bottom
            )
            if (floatingLogsBubble.isVisible) {
                view.post {
                    restoreFloatingLogsBubblePosition()
                    if (floatingLogsPanel.isVisible) {
                        repositionFloatingLogsPanel()
                    }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 退到后台时不要把 WebView renderer 主动降成 waived，尽量降低返回前台后整页被系统重载、前端重新初始化的概率。
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        }
        installWebSessionPersistenceBridge()
        // Tavern 页面里的导出既可能是普通 URL，也可能是 blob/data；宿主在这里统一接管保存到系统下载目录。
        webView.addJavascriptInterface(BlobDownloadBridge(), downloadBridgeName)
        // 浏览器通知统一走宿主桥，避免 Android WebView 里再退回不可用的 Notification API。
        webView.addJavascriptInterface(SystemNotificationBridge(), systemNotificationBridgeName)
        // 只暴露 Tavern 需要的最小宿主能力，给 Android 专属扩展调用设置页、日志悬浮球和版本信息。
        webView.addJavascriptInterface(AndroidHostBridge(), androidHostBridgeName)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUri = request?.url ?: return false
                if (request.isForMainFrame && shouldOpenExternally(targetUri)) {
                    return openExternalBrowser(targetUri)
                }
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val targetUri = url?.let(Uri::parse) ?: return false
                return if (shouldOpenExternally(targetUri)) {
                    openExternalBrowser(targetUri)
                } else {
                    false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 冷启动后主动把 WebView 新写入的 cookie 落盘，避免后台回收前只保存在内存里。
                CookieManager.getInstance().flush()
                installBlobDownloadBridge()
                if (!url.isNullOrBlank()) {
                    loadedUrl = url
                }
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handlePageDownload(
                BrowserDownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType
                )
            )
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val proxyWebView = WebView(this@MainActivity)
                var handled = false

                fun forwardToBrowser(targetUrl: String?) {
                    if (handled || targetUrl.isNullOrBlank() || targetUrl == "about:blank") {
                        return
                    }

                    handled = true
                    openExternalBrowser(Uri.parse(targetUrl))
                    proxyWebView.stopLoading()
                    proxyWebView.destroy()
                }

                proxyWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        forwardToBrowser(request?.url?.toString())
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        forwardToBrowser(url)
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        forwardToBrowser(url)
                    }
                }

                transport.webView = proxyWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) {
                    return false
                }

                // WebView 的 input[type=file] 只会经由 WebChromeClient 回调到宿主；这里把系统文件选择结果原样回传给页面。
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                return true
            }
        }
    }

    private fun installWebSessionPersistenceBridge() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "当前设备的 Android System WebView 不支持 WebMessageListener，无法固化 WebView sessionStorage。"
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "当前设备的 Android System WebView 不支持 document-start script，无法在页面初始化前恢复 sessionStorage。"
        }

        val allowedOriginRules = setOf(BootConfig.localServiceUrl(this))
        // 宿主只在本地 Tavern 页面注入这条桥，避免把 sessionStorage 固化能力暴露到其他来源。
        WebViewCompat.addWebMessageListener(
            webView,
            webSessionBridgeName,
            allowedOriginRules,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                        if (!isMainFrame || sourceOrigin.toString() != BootConfig.localServiceUrl(this@MainActivity)) {
                        return
                    }

                    persistWebStateChange(message.data)
                }
            }
        )
        refreshWebSessionPersistenceScript()
    }

    private fun refreshWebSessionPersistenceScript() {
        webSessionScriptHandler?.remove()
        // 这段脚本必须跑在 Tavern 业务脚本之前，先恢复上次的 sessionStorage，再监听后续变化持久化回宿主。
        webSessionScriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildWebSessionPersistenceScript(),
            setOf(BootConfig.localServiceUrl(this))
        )
    }

    private fun buildWebSessionPersistenceScript(): String {
        val persistedSnapshot = JSONObject.quote(readPersistedWebSessionSnapshot())

        return """
            (function() {
                const persistedPayload = $persistedSnapshot;
                const nativeBridge = globalThis.$webSessionBridgeName;

                const restoredEntries = persistedPayload ? JSON.parse(persistedPayload) : {};
                sessionStorage.clear();
                for (const [key, value] of Object.entries(restoredEntries)) {
                    if (typeof value === 'string') {
                        sessionStorage.setItem(key, value);
                    }
                }

                if (globalThis.__staiAndroidSessionPersistenceInstalled) {
                    return;
                }

                globalThis.__staiAndroidSessionPersistenceInstalled = true;

                const collectSessionStorage = function() {
                    const snapshot = {};
                    for (let index = 0; index < sessionStorage.length; index += 1) {
                        const key = sessionStorage.key(index);
                        if (typeof key === 'string') {
                            snapshot[key] = sessionStorage.getItem(key) ?? '';
                        }
                    }
                    return snapshot;
                };

                const publishStateChange = function(type) {
                    nativeBridge.postMessage(JSON.stringify({
                        type,
                        sessionStorage: collectSessionStorage()
                    }));
                };

                const originalSetItem = sessionStorage.setItem.bind(sessionStorage);
                sessionStorage.setItem = function(key, value) {
                    const result = originalSetItem(key, value);
                    publishStateChange('sessionStorage');
                    return result;
                };

                const originalRemoveItem = sessionStorage.removeItem.bind(sessionStorage);
                sessionStorage.removeItem = function(key) {
                    const result = originalRemoveItem(key);
                    publishStateChange('sessionStorage');
                    return result;
                };

                const originalClear = sessionStorage.clear.bind(sessionStorage);
                sessionStorage.clear = function() {
                    const result = originalClear();
                    publishStateChange('sessionStorage');
                    return result;
                };

                addEventListener('pagehide', function() {
                    publishStateChange('pagehide');
                }, true);
                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'hidden') {
                        publishStateChange('visibilitychange');
                    }
                }, true);

                ${buildAndroidHostNotificationShimScript()}
            })();
        """.trimIndent()
    }

    private fun buildAndroidHostNotificationShimScript(): String {
        return """
            const nativeNotificationBridge = globalThis.$systemNotificationBridgeName;
            if (nativeNotificationBridge && !globalThis.__staiAndroidHostNotificationInstalled) {
                globalThis.__staiAndroidHostNotificationInstalled = true;

                // 第三方 Tavern 前端只认识浏览器 Notification API；这里把它直接映射成宿主原生通知。
                const createNotificationEvent = function(type) {
                    return typeof Event === 'function' ? new Event(type) : { type: type };
                };

                class AndroidHostNotification {
                    constructor(title, options) {
                        const normalizedOptions = options && typeof options === 'object' ? options : {};
                        this.title = String(title ?? '');
                        this.body = typeof normalizedOptions.body === 'string' ? normalizedOptions.body : '';
                        this.tag = typeof normalizedOptions.tag === 'string' ? normalizedOptions.tag : '';
                        this.data = normalizedOptions.data;
                        this.icon = typeof normalizedOptions.icon === 'string' ? normalizedOptions.icon : '';
                        this.onclick = null;
                        this.onerror = null;
                        this.onshow = null;
                        this.onclose = null;
                        this.listeners = new Map();

                        const shown = nativeNotificationBridge.showNotification(JSON.stringify({
                            notificationId: this.tag || this.title,
                            title: this.title,
                            body: this.body,
                            tag: this.tag
                        }));

                        Promise.resolve().then(() => {
                            this.dispatchEvent(createNotificationEvent(shown ? 'show' : 'error'));
                        });
                    }

                    addEventListener(type, listener) {
                        if (typeof listener !== 'function') {
                            return;
                        }

                        const currentListeners = this.listeners.get(type) || [];
                        currentListeners.push(listener);
                        this.listeners.set(type, currentListeners);
                    }

                    removeEventListener(type, listener) {
                        const currentListeners = this.listeners.get(type) || [];
                        this.listeners.set(type, currentListeners.filter((item) => item !== listener));
                    }

                    dispatchEvent(event) {
                        const currentListeners = this.listeners.get(event.type) || [];
                        for (const listener of currentListeners) {
                            listener.call(this, event);
                        }

                        const handler = this['on' + event.type];
                        if (typeof handler === 'function') {
                            handler.call(this, event);
                        }

                        return true;
                    }

                    close() {
                        this.dispatchEvent(createNotificationEvent('close'));
                    }

                    static requestPermission(callback) {
                        const permission = nativeNotificationBridge.requestPermission();
                        if (typeof callback === 'function') {
                            callback(permission);
                        }
                        return Promise.resolve(permission);
                    }
                }

                Object.defineProperty(AndroidHostNotification, 'permission', {
                    configurable: true,
                    enumerable: true,
                    get() {
                        return nativeNotificationBridge.permissionState();
                    }
                });
                Object.defineProperty(AndroidHostNotification, 'maxActions', {
                    configurable: true,
                    enumerable: true,
                    value: 0
                });

                globalThis.Notification = AndroidHostNotification;
                if (typeof window !== 'undefined') {
                    window.Notification = AndroidHostNotification;
                }
            }
        """.trimIndent()
    }

    private fun readPersistedWebSessionSnapshot(): String {
        return webSessionStoragePreferences.getString(webSessionStorageSnapshotKey, "{}") ?: "{}"
    }

    private fun persistWebStateChange(payload: String?) {
        val changeEnvelope = if (payload.isNullOrBlank()) JSONObject() else JSONObject(payload)
        val sessionStorageSnapshot = changeEnvelope.optJSONObject("sessionStorage") ?: JSONObject()

        persistWebSessionSnapshot(sessionStorageSnapshot)
        // WebView 状态变更后顺手 flush cookie，尽量避免后台被系统回收前 cookie 仍只留在内存里。
        CookieManager.getInstance().flush()
    }

    private fun persistWebSessionSnapshot(snapshot: JSONObject) {
        val normalizedSnapshot = JSONObject()
        val keys = snapshot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            normalizedSnapshot.put(key, snapshot.optString(key))
        }

        // 宿主只保存当前 Tavern origin 的 sessionStorage 快照；下次 WebView 冷启动会在页面业务脚本执行前恢复这份状态。
        webSessionStoragePreferences.edit()
            .putString(webSessionStorageSnapshotKey, normalizedSnapshot.toString())
            .commit()
    }

    private fun installBlobDownloadBridge() {
        webView.evaluateJavascript(
            """
            (function() {
              const nativeBridge = window.$downloadBridgeName;
              if (!nativeBridge || typeof nativeBridge.saveBase64File !== 'function') {
                return;
              }

              if (window.__staiAndroidDownloadBridgeInstalled) {
                return;
              }

              window.__staiAndroidDownloadBridgeInstalled = true;

              const blobStore = new Map();
              const originalCreateObjectUrl = URL.createObjectURL.bind(URL);
              const originalRevokeObjectUrl = URL.revokeObjectURL.bind(URL);

              URL.createObjectURL = function(object) {
                const objectUrl = originalCreateObjectUrl(object);
                if (object instanceof Blob) {
                  blobStore.set(objectUrl, object);
                }
                return objectUrl;
              };

              URL.revokeObjectURL = function(objectUrl) {
                blobStore.delete(objectUrl);
                return originalRevokeObjectUrl(objectUrl);
              };

              function readBlobAsBase64(blob) {
                return new Promise(function(resolve, reject) {
                  const reader = new FileReader();
                  reader.onloadend = function() {
                    const result = typeof reader.result === 'string' ? reader.result : '';
                    const commaIndex = result.indexOf(',');
                    if (commaIndex < 0) {
                      reject(new Error('无法解析导出数据'));
                      return;
                    }
                    resolve(result.slice(commaIndex + 1));
                  };
                  reader.onerror = function() {
                    reject(reader.error || new Error('无法读取导出数据'));
                  };
                  reader.readAsDataURL(blob);
                });
              }

              function resolveBlob(href) {
                if (href.startsWith('blob:')) {
                  return Promise.resolve(blobStore.get(href) || null);
                }

                if (href.startsWith('data:')) {
                  return fetch(href).then(function(response) {
                    return response.blob();
                  });
                }

                return Promise.resolve(null);
              }

              document.addEventListener('click', function(event) {
                const target = event.target;
                const anchor = target && typeof target.closest === 'function' ? target.closest('a[href]') : null;
                if (!anchor) {
                  return;
                }

                const href = anchor.href || '';
                if (!href.startsWith('blob:') && !href.startsWith('data:')) {
                  return;
                }

                event.preventDefault();
                event.stopPropagation();

                const fileName = (anchor.getAttribute('download') || '').trim() || 'download';
                nativeBridge.onBlobDownloadPreparing(fileName);

                resolveBlob(href)
                  .then(function(blob) {
                    if (!blob) {
                      throw new Error('未找到导出数据');
                    }

                    return readBlobAsBase64(blob).then(function(base64) {
                      nativeBridge.saveBase64File(JSON.stringify({
                        fileName: fileName,
                        mimeType: blob.type || '',
                        base64: base64
                      }));
                    });
                  })
                  .catch(function(error) {
                    nativeBridge.reportDownloadFailure(JSON.stringify({
                      fileName: fileName,
                      message: error && error.message ? error.message : '导出失败'
                    }));
                  });
              }, true);
            })();
            """.trimIndent(),
            null
        )
    }

    @Suppress("DEPRECATION")
    private fun handlePageDownload(request: BrowserDownloadRequest) {
        val targetUrl = request.url.trim()
        if (targetUrl.isBlank() || targetUrl.startsWith("blob:") || targetUrl.startsWith("data:")) {
            return
        }

        val fileName = resolveDownloadFileName(
            rawName = URLUtil.guessFileName(targetUrl, request.contentDisposition, request.mimeType),
            fallbackUrl = targetUrl
        )

        try {
            val systemRequest = DownloadManager.Request(Uri.parse(targetUrl)).apply {
                // 普通 URL 下载直接交给系统 DownloadManager，避免 WebView 自己吞掉下载请求。
                setTitle(fileName)
                setDescription(getString(R.string.download_status_pending, fileName))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setVisibleInDownloadsUi(true)
                setMimeType(request.mimeType.ifBlank { "application/octet-stream" })

                val cookieHeader = CookieManager.getInstance().getCookie(targetUrl)
                if (!cookieHeader.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookieHeader)
                }

                if (request.userAgent.isNotBlank()) {
                    addRequestHeader("User-Agent", request.userAgent)
                }

                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            downloadManager.enqueue(systemRequest)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            showDownloadFailure(
                DownloadFailureReport(
                    fileName = fileName,
                    message = error.message ?: getString(R.string.download_failed_unknown)
                )
            )
        }
    }

    private fun resolveDownloadFileName(rawName: String?, fallbackUrl: String?): String {
        val candidate = rawName.orEmpty().trim()
        if (candidate.isNotBlank()) {
            return candidate.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        }

        if (!fallbackUrl.isNullOrBlank()) {
            return URLUtil.guessFileName(fallbackUrl, null, null)
        }

        return "download"
    }

    private fun buildDownloadFailureMessage(report: DownloadFailureReport): String {
        val details = report.message.trim().ifBlank { getString(R.string.download_failed_unknown) }
        return getString(R.string.download_failed, report.fileName, details)
    }

    private fun showDownloadFailure(report: DownloadFailureReport) {
        Toast.makeText(this, buildDownloadFailureMessage(report), Toast.LENGTH_LONG).show()
    }

    private fun parseBlobDownloadRequest(payload: String?): BlobDownloadRequest? {
        if (payload.isNullOrBlank()) {
            return null
        }

        val json = JSONObject(payload)
        val fileName = resolveDownloadFileName(json.optString("fileName"), null)
        val mimeType = json.optString("mimeType").trim().ifBlank { "application/octet-stream" }
        val base64Data = json.optString("base64").trim()
        if (base64Data.isBlank()) {
            return null
        }

        return BlobDownloadRequest(fileName = fileName, mimeType = mimeType, base64Data = base64Data)
    }

    private fun parseDownloadFailureReport(payload: String?): DownloadFailureReport {
        if (payload.isNullOrBlank()) {
            return DownloadFailureReport(
                fileName = resolveDownloadFileName(null, null),
                message = getString(R.string.download_failed_unknown)
            )
        }

        val json = JSONObject(payload)
        return DownloadFailureReport(
            fileName = resolveDownloadFileName(json.optString("fileName"), null),
            message = json.optString("message").trim().ifBlank { getString(R.string.download_failed_unknown) }
        )
    }

    private suspend fun persistBlobDownload(request: BlobDownloadRequest): String {
        return withContext(Dispatchers.IO) {
            val resolver = applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, request.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            // blob/data 导出没有真实 URL，宿主直接写入系统 Downloads 提供者，保证用户能在下载目录里看到文件。
            val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")

            try {
                resolver.openOutputStream(targetUri)?.use { output ->
                    output.write(Base64.decode(request.base64Data, Base64.DEFAULT))
                    output.flush()
                } ?: throw IllegalStateException("无法写入下载文件")

                val completedValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(targetUri, completedValues, null, null)
                request.fileName
            } catch (error: Exception) {
                resolver.delete(targetUri, null, null)
                throw error
            }
        }
    }

    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun onBlobDownloadPreparing(fileName: String?) {
            val resolvedFileName = resolveDownloadFileName(fileName, null)
            runOnUiThread {
                Toast.makeText(this@MainActivity, getString(R.string.download_status_preparing, resolvedFileName), Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveBase64File(payload: String?) {
            val request = try {
                parseBlobDownloadRequest(payload)
            } catch (error: Exception) {
                null
            }

            if (request == null) {
                reportDownloadFailure(
                    JSONObject()
                        .put("fileName", resolveDownloadFileName(null, null))
                        .put("message", getString(R.string.download_failed_empty_payload))
                        .toString()
                )
                return
            }

            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, getString(R.string.download_status_saving, request.fileName), Toast.LENGTH_SHORT).show()

                val result = runCatching {
                    persistBlobDownload(request)
                }

                result.onSuccess { fileName ->
                    Toast.makeText(this@MainActivity, getString(R.string.download_saved, fileName), Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    showDownloadFailure(
                        DownloadFailureReport(
                            fileName = request.fileName,
                            message = error.message ?: getString(R.string.download_failed_unknown)
                        )
                    )
                }
            }
        }

        @JavascriptInterface
        fun reportDownloadFailure(payload: String?) {
            val report = try {
                parseDownloadFailureReport(payload)
            } catch (error: Exception) {
                DownloadFailureReport(
                    fileName = resolveDownloadFileName(null, null),
                    message = getString(R.string.download_failed_unknown)
                )
            }

            runOnUiThread {
                showDownloadFailure(report)
            }
        }
    }

    private inner class SystemNotificationBridge {
        @JavascriptInterface
        fun showNotification(payload: String?): Boolean {
            val request = parseSystemNotificationRequest(payload) ?: return false

            if (!canPostSystemNotifications()) {
                runOnUiThread { requestNotificationPermissionIfNeeded() }
                return false
            }

            ensureSystemNotificationChannel()
            return showSystemNotification(request)
        }

        @JavascriptInterface
        fun permissionState(): String {
            return resolveNotificationPermissionState()
        }

        @JavascriptInterface
        fun requestPermission(): String {
            runOnUiThread { requestNotificationPermissionIfNeeded() }
            return resolveNotificationPermissionState()
        }
    }

    private inner class AndroidHostBridge {
        @JavascriptInterface
        fun openSettings(): Boolean {
            if (isFinishing || isDestroyed) {
                return false
            }

            runOnUiThread {
                openBootstrapSettings()
            }
            return true
        }

        @JavascriptInterface
        fun showFloatingLogsBubble(): Boolean {
            if (isFinishing || isDestroyed) {
                return false
            }

            runOnUiThread {
                hostConfigStore.floatingLogBubbleEnabled = true
                refreshFloatingLogsVisibility()
                revealFloatingLogsBubble(animated = true)
            }
            return true
        }

        @JavascriptInterface
        fun setFloatingLogsBubbleEnabled(enabled: Boolean): Boolean {
            if (isFinishing || isDestroyed) {
                return false
            }

            runOnUiThread {
                hostConfigStore.floatingLogBubbleEnabled = enabled
                refreshFloatingLogsVisibility()
                if (enabled) {
                    revealFloatingLogsBubble(animated = true)
                }
            }
            return true
        }

        @JavascriptInterface
        fun getHostVersionInfo(): String {
            return buildAndroidHostVersionInfoJson()
        }
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

    private fun ensureSystemNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            BootConfig.systemNotificationChannelId,
            getString(R.string.system_notification_channel_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.system_notification_channel_description)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canPostSystemNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveNotificationPermissionState(): String {
        return if (canPostSystemNotifications()) "granted" else "default"
    }

    private fun parseSystemNotificationRequest(payload: String?): SystemNotificationRequest? {
        val normalizedPayload = payload?.trim().orEmpty()
        if (normalizedPayload.isBlank()) {
            return null
        }

        val json = JSONObject(normalizedPayload)
        val title = json.optString("title").trim().ifBlank { "通知" }
        val body = json.optString("body").trim()
        val notificationId = json.optString("notificationId").trim()
        val tag = json.optString("tag").trim()
        return SystemNotificationRequest(
            notificationId = notificationId,
            title = title,
            body = body,
            tag = tag
        )
    }

    private fun showSystemNotification(request: SystemNotificationRequest): Boolean {
        val notification = NotificationCompat.Builder(this, BootConfig.systemNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(request.title)
            .setContentText(request.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.body))
            .setAutoCancel(true)
            .setContentIntent(createSystemNotificationIntent())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notifyTag = request.tag.ifBlank { request.notificationId }
        NotificationManagerCompat.from(this).notify(notifyTag.ifBlank { null }, resolveNotificationRequestCode(request), notification)
        return true
    }

    private fun createSystemNotificationIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, BootConfig.notificationId, launchIntent, flags)
    }

    private fun resolveNotificationRequestCode(request: SystemNotificationRequest): Int {
        val seed = request.notificationId
            .ifBlank { request.tag }
            .ifBlank { request.title }
            .hashCode()
        return if (seed == Int.MIN_VALUE) 0 else seed.absoluteValue
    }

    private fun restoreWebViewState(savedInstanceState: Bundle?) {
        val webViewState = savedInstanceState?.getBundle(webViewStateKey) ?: return
        val restoredState = webView.restoreState(webViewState)
        val restoredUrl = restoredState?.currentItem?.url.orEmpty()
            .ifBlank { savedInstanceState.getString(loadedUrlStateKey).orEmpty() }

        if (restoredUrl.isBlank()) {
            return
        }

        loadedUrl = restoredUrl
        hasRestoredWebViewState = true
    }

    private fun observeBootstrapState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StartupRuntimeStore.state.collect { state ->
                    renderBootstrapState(state)
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
            .put("hostVersion", BuildConfig.SILLYDROID_HOST_VERSION)
            .put("apkVersionName", packageInfo.versionName.orEmpty().trim())
            .put("apkVersionCode", packageInfo.longVersionCode.toString())
            .put("floatingLogBubbleEnabled", hostConfigStore.floatingLogBubbleEnabled)
            .put("serverReady", StartupRuntimeStore.state.value.isReady)
            .toString()
    }

    private fun renderBootstrapState(state: StartupState) {
        val displayMessage = if (state.phase == StartupPhase.CONFIGURING) {
            getString(R.string.bootstrap_paused_message)
        } else {
            state.message
        }
        val displayDetails = if (state.phase == StartupPhase.CONFIGURING) {
            getString(R.string.bootstrap_paused_details)
        } else {
            state.details
        }
        val details = displayDetails.takeIf { it.isNotBlank() }
        bootstrapStatus.text = if (details == null) {
            displayMessage
        } else {
            buildString {
                append(displayMessage)
                append('\n')
                append(details)
            }
        }

        bootstrapRetry.isVisible = state.canRetry || state.phase == StartupPhase.CONFIGURING
        bootstrapRetry.text = if (state.phase == StartupPhase.CONFIGURING) {
            getString(R.string.bootstrap_resume)
        } else {
            getString(R.string.bootstrap_retry)
        }
        bootstrapProgress.isVisible = !state.canRetry && state.phase != StartupPhase.CONFIGURING
        bootstrapProgressLabel.isVisible = bootstrapProgress.isVisible
        bootstrapSettingsButton.isVisible = !state.isReady
        bootstrapSettingsButton.isEnabled = !state.isReady && !isOpeningBootstrapSettings
        bootstrapProgress.max = 100
        val progressPercent = state.progressPercent.coerceIn(0, 100)
        bootstrapProgress.isIndeterminate = progressPercent <= 0
        if (!bootstrapProgress.isIndeterminate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                bootstrapProgress.setProgress(progressPercent, true)
            } else {
                bootstrapProgress.progress = progressPercent
            }
            bootstrapProgressLabel.text = getString(R.string.bootstrap_progress_label, progressPercent)
        } else {
            bootstrapProgressLabel.text = getString(R.string.bootstrap_progress_indeterminate)
        }

        if (state.isReady) {
            showWebView(state.localUrl)
            maybePromptDefaultExtensionsAfterBootstrapReady()
        } else {
            bootstrapOverlay.isVisible = true
            webView.isVisible = false
        }
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

        val baseMessage = getString(R.string.bootstrap_settings_extensions_default_prompt_message, repositoryCount)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bootstrap_settings_extensions_default_prompt_title)
            .setMessage(baseMessage)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_install, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            var githubReachable = false
            var githubCheckVersion = 0

            fun runGithubCheck() {
                val checkVersion = githubCheckVersion + 1
                githubCheckVersion = checkVersion
                positiveButton.isEnabled = false
                updateDefaultExtensionsPromptMessage(
                    dialog = dialog,
                    baseMessage = baseMessage,
                    statusMessage = getString(R.string.bootstrap_settings_extensions_github_checking)
                )
                defaultExtensionsCoordinator.checkDefaultRepositoriesGithubReachability { reachable, failureMessage ->
                    if (checkVersion != githubCheckVersion) {
                        return@checkDefaultRepositoriesGithubReachability
                    }

                    githubReachable = reachable
                    positiveButton.isEnabled = true
                    positiveButton.text = getString(
                        if (reachable) {
                            R.string.bootstrap_settings_extensions_install
                        } else {
                            R.string.bootstrap_settings_extensions_github_check_action
                        }
                    )
                    updateDefaultExtensionsPromptMessage(
                        dialog = dialog,
                        baseMessage = baseMessage,
                        statusMessage = if (reachable) null else failureMessage
                    )
                }
            }

            positiveButton.setOnClickListener {
                if (githubReachable) {
                    dialog.dismiss()
                    defaultExtensionsCoordinator.autoInstallDefaultRepositories()
                    return@setOnClickListener
                }

                runGithubCheck()
            }

            runGithubCheck()
        }

        dialog.show()
    }

    private fun updateDefaultExtensionsPromptMessage(
        dialog: androidx.appcompat.app.AlertDialog,
        baseMessage: String,
        statusMessage: String?
    ) {
        val resolvedMessage = listOfNotNull(
            baseMessage.trim().takeIf { it.isNotEmpty() },
            statusMessage?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString(separator = "\n\n")
        dialog.findViewById<TextView>(android.R.id.message)?.text = resolvedMessage
    }

    private fun showDefaultExtensionsMessage(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showDefaultExtensionsError(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bootstrap_settings_extensions_install)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun loadDefaultExtensionRepositoryCount(): Int {
        val paths = HostPaths.from(this)
        val packagedConfigFile = File(paths.bootstrapRoot, "default-extensions/sillydroid-build-config.json")
        if (!packagedConfigFile.isFile) {
            return 0
        }

        return runCatching {
            val root = JSONObject(packagedConfigFile.readText())
            val repositories = root.optJSONArray("defaultExtensionRepositories")
                ?: root.optJSONArray("repositories")
                ?: return@runCatching 0
            (0 until repositories.length()).count { index ->
                val repository = repositories.optJSONObject(index) ?: return@count false
                repository.optString("displayName").trim().isNotBlank() &&
                    repository.optString("repositoryUrl").trim().isNotBlank()
            }
        }.getOrDefault(0)
    }

    private fun showWebView(baseUrl: String) {
        bootstrapOverlay.isVisible = false
        webView.isVisible = true
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

    private fun startBootstrap(forceRestart: Boolean) {
        val intent = StartupCoordinatorService.createStartIntent(this, forceRestart)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun reloadTavernUiIfPossible(state: StartupState) {
        if (!state.isReady || !webView.isVisible) {
            return
        }

        webView.reload()
    }

    private fun openBootstrapSettings(openDefaultExtensionsInstaller: Boolean = false) {
        if (isOpeningBootstrapSettings) {
            return
        }

        isOpeningBootstrapSettings = true
        bootstrapSettingsButton.isEnabled = false
        bootstrapSettingsLauncher.launch(
            BootstrapSettingsActivity.createIntent(
                activity = this,
                openExtensionsTab = openDefaultExtensionsInstaller,
                openDefaultExtensionsInstaller = openDefaultExtensionsInstaller
            )
        )
    }

    private inner class DefaultExtensionsProgressHost : ExtensionInstallProgressHost {
        private var dialog: androidx.appcompat.app.AlertDialog? = null
        private var progressIndicator: LinearProgressIndicator? = null
        private var progressLabelView: TextView? = null

        override fun show(message: String, percent: Int?, indeterminate: Boolean) {
            if (isFinishing || isDestroyed) {
                return
            }

            ensureDialog()
            val indicator = progressIndicator ?: return
            val labelView = progressLabelView ?: return
            labelView.text = message
            indicator.isIndeterminate = indeterminate
            if (!indeterminate) {
                indicator.max = 100
                indicator.setProgressCompat(percent ?: 0, true)
            }
            if (dialog?.isShowing != true) {
                dialog?.show()
            }
        }

        override fun hide() {
            dialog?.dismiss()
            dialog = null
            progressIndicator = null
            progressLabelView = null
        }

        private fun ensureDialog() {
            if (dialog != null) {
                return
            }

            val density = resources.displayMetrics.density
            val horizontalPadding = (24 * density).toInt()
            val verticalPadding = (16 * density).toInt()
            val topSpacing = (14 * density).toInt()
            val contentView = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            }
            val indicator = LinearProgressIndicator(this@MainActivity).apply {
                isIndeterminate = true
            }
            val label = TextView(this@MainActivity).apply {
                setPadding(0, topSpacing, 0, 0)
            }
            contentView.addView(
                indicator,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            contentView.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            progressIndicator = indicator
            progressLabelView = label
            dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.bootstrap_settings_extensions_default_title)
                .setView(contentView)
                .setCancelable(false)
                .create()
                .also { createdDialog ->
                    createdDialog.setCanceledOnTouchOutside(false)
                }
        }
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
        val localUri = Uri.parse(BootConfig.localServiceUrl(this))
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

