package com.jm.sillydroid.feature.main.floatingbrowser

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.jm.sillydroid.core.model.settings.FloatingBrowserPosition
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.feature.main.MainActivity
import com.jm.sillydroid.feature.main.R
import kotlin.math.abs

/**
 * 执行悬浮球返回主界面的固定顺序。
 *
 * Activity 必须在 overlay 仍可见时启动，部分系统才会把这次用户点击识别为允许的后台界面跳转；
 * 启动提交失败时保留窗口和服务，允许用户再次操作，不能留下无入口的已分离浏览器。
 */
internal fun executeFloatingBrowserReturn(
    launchMainActivity: () -> Boolean,
    hideOverlay: () -> Unit,
    stopService: () -> Unit
): Boolean {
    if (!launchMainActivity()) {
        return false
    }
    hideOverlay()
    stopService()
    return true
}

/**
 * 承载真实浏览器表面的系统 overlay 服务。
 *
 * 允许：创建可拖动悬浮球、裁剪当前浏览器 View、点击返回主界面、锁屏时移除窗口。
 * 不允许：创建第二浏览器会话、启动/停止 Node 后端、使用透明全屏或 1px 隐藏窗口规避系统限制。
 */
class FloatingBrowserService : Service() {
    companion object {
        private const val DOCK_ANIMATION_DURATION_MS = 220L
        private const val OVERLAY_PERMISSION_CHECK_INTERVAL_MS = 1_000L
        private const val OVERLAY_VISIBILITY_DIAGNOSTIC_DELAY_MS = 250L
        private const val ACTION_PREPARE = "com.jm.sillydroid.action.FLOATING_BROWSER_PREPARE"
        private const val ACTION_SHOW = "com.jm.sillydroid.action.FLOATING_BROWSER_SHOW"
        private const val ACTION_HIDE = "com.jm.sillydroid.action.FLOATING_BROWSER_HIDE"
        @Volatile
        private var activeInstance: FloatingBrowserService? = null

        /** 在 Activity 前台时预启动普通服务，避免切后台后再创建服务命中后台启动限制。 */
        fun prepare(context: Context) {
            activeInstance?.let { service ->
                service.mainHandler.post { service.hideOverlay(restoreToActivity = true) }
                return
            }
            context.startService(createIntent(context, ACTION_PREPARE))
        }

        /** 请求已准备的服务显示悬浮浏览器。 */
        fun show(context: Context) {
            activeInstance?.let { service ->
                service.mainHandler.post(service::showOverlay)
                return
            }
            context.startService(createIntent(context, ACTION_SHOW))
        }

        /** 请求服务移除窗口并结束；浏览器由协调器先挂回 Activity。 */
        fun hide(context: Context) {
            activeInstance?.let { service ->
                service.mainHandler.post {
                    service.hideOverlay(restoreToActivity = true)
                    service.stopSelf()
                }
                return
            }
            context.startService(createIntent(context, ACTION_HIDE))
        }

        /** App 回到前台时仅把 overlay 中的浏览器挂回 Activity，不停止已预热的服务。 */
        fun restoreForAppForeground() {
            activeInstance?.let { service ->
                service.mainHandler.post { service.hideOverlay(restoreToActivity = true) }
            }
        }

        /** 无需启动服务即可确保残留实例停止。 */
        fun stop(context: Context) {
            activeInstance?.let { service ->
                service.mainHandler.post {
                    service.hideOverlay(restoreToActivity = true)
                    service.stopSelf()
                }
            }
            context.stopService(Intent(context, FloatingBrowserService::class.java))
        }

        /** 构造仅指向本包私有服务的显式命令 Intent。 */
        private fun createIntent(context: Context, actionName: String): Intent {
            return Intent(context, FloatingBrowserService::class.java).setAction(actionName)
        }
    }

    private val appGraph by lazy {
        (applicationContext as SillyDroidAppGraphProvider).sillyDroidAppGraph
    }
    private val floatingBrowserRuntime by lazy {
        (applicationContext as FloatingBrowserRuntimeProvider).floatingBrowserRuntime
    }
    private val preferences by lazy { appGraph.hostConfigStore }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private var overlayRoot: FrameLayout? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var dockAnimator: ValueAnimator? = null
    private var receiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayPermissionCheck = object : Runnable {
        /** overlay 可见期间定期复核 AppOps 权限；撤销后立即移除窗口并关闭设置真值。 */
        override fun run() {
            if (overlayRoot == null) {
                return
            }
            if (!Settings.canDrawOverlays(this@FloatingBrowserService)) {
                disableAfterWindowFailure()
                return
            }
            mainHandler.postDelayed(this, OVERLAY_PERMISSION_CHECK_INTERVAL_MS)
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        /** 熄屏时移除窗口；亮屏或解锁后仅在设备已可交互且 App 仍在后台时恢复。 */
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> hideOverlay(restoreToActivity = true)
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    if (!appGraph.appForegroundState.isInForeground) {
                        showOverlay()
                    }
                }
            }
        }
    }

    /** 注册当前服务实例和屏幕状态监听；不在创建阶段主动迁移浏览器。 */
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        registerScreenStateReceiver()
    }

    /** 本服务只接受显式 start 命令，不提供绑定接口。 */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 处理预热、显示和隐藏命令；进程死亡后不自动重建会话。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> {
                hideOverlay(restoreToActivity = true)
                stopSelf()
            }
            ACTION_PREPARE -> hideOverlay(restoreToActivity = true)
            null -> Unit
        }
        return START_NOT_STICKY
    }

    /** 销毁服务前移除窗口、解除广播并清理静态实例引用。 */
    override fun onDestroy() {
        hideOverlay(restoreToActivity = true)
        unregisterScreenStateReceiver()
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    /** 创建窗口并迁移浏览器；任何权限或窗口异常都会关闭设置真值。 */
    private fun showOverlay() {
        if (overlayRoot != null || appGraph.appForegroundState.isInForeground) {
            return
        }
        if (!preferences.floatingBrowserEnabled || !preferences.launchWebViewOnReady) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            disableAfterWindowFailure()
            return
        }
        if (!isScreenAvailable() || floatingBrowserRuntime.suppressionRegistry.isSuppressed()) {
            return
        }
        if (!appGraph.bootstrapController.currentSnapshot().isReady) {
            return
        }

        val root = buildOverlayRoot()
        val browserContainer = root.getChildAt(0) as FrameLayout
        if (!floatingBrowserRuntime.coordinator.attachToOverlay(browserContainer)) {
            return
        }
        val layoutParams = buildOverlayLayoutParams()
        runCatching { windowManager.addView(root, layoutParams) }
            .onSuccess {
                overlayRoot = root
                overlayLayoutParams = layoutParams
                scheduleOverlayPermissionCheck()
                scheduleOverlayVisibilityDiagnostic(root)
            }
            .onFailure {
                floatingBrowserRuntime.coordinator.restoreToActivity()
                disableAfterWindowFailure()
            }
    }

    /** 把浏览器挂回 Activity 后再删除窗口，避免 View 在 WindowManager 销毁时一并失效。 */
    private fun hideOverlay(restoreToActivity: Boolean) {
        dockAnimator?.cancel()
        dockAnimator = null
        mainHandler.removeCallbacks(overlayPermissionCheck)
        if (restoreToActivity) {
            val restored = floatingBrowserRuntime.coordinator.restoreToActivity()
            if (!restored) {
                floatingBrowserRuntime.coordinator.detachFromOverlay()
            }
        } else {
            floatingBrowserRuntime.coordinator.detachFromOverlay()
        }
        overlayRoot?.let { root ->
            runCatching { windowManager.removeViewImmediate(root) }
        }
        overlayRoot = null
        overlayLayoutParams = null
    }

    /** 构建只显示真实浏览器裁剪画面、但不允许页面直接接收触摸的悬浮球。 */
    private fun buildOverlayRoot(): FrameLayout {
        val size = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_browser_bubble_size)
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            background = ContextCompat.getDrawable(this@FloatingBrowserService, R.drawable.bg_floating_browser_bubble)
            contentDescription = getString(R.string.floating_browser_bubble_description)
        }
        val browserContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            clipChildren = true
            clipToPadding = true
        }
        root.addView(browserContainer)
        val touchShield = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setBackgroundResource(R.drawable.bg_floating_browser_touch_shield)
            setImageResource(R.drawable.ic_bootstrap_settings)
            imageTintList = ContextCompat.getColorStateList(this@FloatingBrowserService, R.color.floating_browser_icon)
            val padding = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_browser_icon_padding)
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        installBubbleTouch(touchShield)
        root.addView(touchShield)
        return root
    }

    /** 处理拖动与点击；悬浮球不把手势交给底层浏览器。 */
    private fun installBubbleTouch(touchView: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        touchView.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            /** 区分点击与拖动，并始终消费手势以阻止底层浏览器接收输入。 */
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val params = overlayLayoutParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dockAnimator?.cancel()
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY
                        if (!dragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                            dragging = true
                        }
                        if (dragging) {
                            moveOverlayTo(startX + deltaX.toInt(), startY + deltaY.toInt())
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            dockAndPersistOverlayPosition()
                        } else {
                            restoreMainActivity()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            dockAndPersistOverlayPosition()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    /** 限制悬浮球坐标始终位于当前 WindowMetrics 范围内。 */
    private fun moveOverlayTo(requestedX: Int, requestedY: Int) {
        val root = overlayRoot ?: return
        val params = overlayLayoutParams ?: return
        val bounds = currentWindowBounds()
        params.x = requestedX.coerceIn(0, (bounds.width() - root.width).coerceAtLeast(0))
        params.y = requestedY.coerceIn(0, (bounds.height() - root.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(root, params) }
            .onFailure { disableAfterWindowFailure() }
    }

    /** 选择最近边缘，保存归一化位置，并以日志球相同的半隐藏方式完成吸附。 */
    private fun dockAndPersistOverlayPosition() {
        val root = overlayRoot ?: return
        val params = overlayLayoutParams ?: return
        val bounds = currentWindowBounds()
        val bubbleWidth = root.width.takeIf { value -> value > 0 }
            ?: resources.getDimensionPixelSize(R.dimen.sillydroid_floating_browser_bubble_size)
        val side = FloatingBrowserDockingPolicy.resolveNearestSide(
            bubbleCenterX = params.x + bubbleWidth / 2f,
            windowWidth = bounds.width()
        )
        persistOverlayPosition(side)
        animateOverlayToDockSide(side)
    }

    /** 保存吸附边缘和纵向比例，使旋转或分辨率变化后仍能恢复可见位置。 */
    private fun persistOverlayPosition(side: FloatingBrowserDockSide) {
        val root = overlayRoot ?: return
        val params = overlayLayoutParams ?: return
        val bounds = currentWindowBounds()
        val maxY = (bounds.height() - root.height).coerceAtLeast(1)
        preferences.floatingBrowserPosition = FloatingBrowserPosition(
            horizontalFraction = FloatingBrowserDockingPolicy.horizontalFraction(side),
            verticalFraction = (params.y.toFloat() / maxY.toFloat()).coerceIn(0f, 1f)
        )
    }

    /** 用与应用内日志球一致的回弹节奏，把窗口动画到半隐藏边缘。 */
    private fun animateOverlayToDockSide(side: FloatingBrowserDockSide) {
        val root = overlayRoot ?: return
        val params = overlayLayoutParams ?: return
        val bubbleWidth = root.width.takeIf { value -> value > 0 }
            ?: resources.getDimensionPixelSize(R.dimen.sillydroid_floating_browser_bubble_size)
        val targetX = FloatingBrowserDockingPolicy.resolveDockedX(
            side = side,
            windowWidth = currentWindowBounds().width(),
            bubbleWidth = bubbleWidth
        )
        dockAnimator?.cancel()
        if (params.x == targetX) {
            return
        }
        dockAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = DOCK_ANIMATION_DURATION_MS
            interpolator = OvershootInterpolator(0.55f)
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(root, params) }
                    .onFailure {
                        cancel()
                        disableAfterWindowFailure()
                    }
            }
            start()
        }
    }

    /** 点击悬浮球时在窗口仍可见的用户手势期间启动主界面，再恢复同一浏览器表面并移除窗口。 */
    private fun restoreMainActivity() {
        executeFloatingBrowserReturn(
            launchMainActivity = {
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                    )
                    true
                }.getOrDefault(false)
            },
            hideOverlay = { hideOverlay(restoreToActivity = true) },
            stopService = { stopSelf() }
        )
    }

    /** 创建不可聚焦、仅悬浮球范围可触摸的 overlay 参数。 */
    private fun buildOverlayLayoutParams(): WindowManager.LayoutParams {
        val size = resources.getDimensionPixelSize(R.dimen.sillydroid_floating_browser_bubble_size)
        val bounds = currentWindowBounds()
        val stored = preferences.floatingBrowserPosition
        val maxY = (bounds.height() - size).coerceAtLeast(0)
        val dockSide = FloatingBrowserDockingPolicy.resolveStoredSide(stored?.horizontalFraction)
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = FloatingBrowserDockingPolicy.resolveDockedX(
                side = dockSide,
                windowWidth = bounds.width(),
                bubbleWidth = size
            )
            y = ((stored?.verticalFraction ?: 0.5f) * maxY).toInt().coerceIn(0, maxY)
        }
    }

    /** 返回窗口真实边界，避免使用弃用 display 宽高和散落像素常量。 */
    private fun currentWindowBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Point().also { size ->
                windowManager.defaultDisplay.getSize(size)
            }.let { size -> Rect(0, 0, size.x, size.y) }
        }
    }

    /** 只在亮屏且未锁定时允许显示悬浮浏览器。 */
    private fun isScreenAvailable(): Boolean {
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    /** 权限或 WindowManager 契约失败时关闭持久化真值并结束服务。 */
    private fun disableAfterWindowFailure() {
        preferences.floatingBrowserEnabled = false
        hideOverlay(restoreToActivity = true)
        stopSelf()
    }

    /** overlay 显示后启动单一权限复核任务，防止重复 runnable 叠加。 */
    private fun scheduleOverlayPermissionCheck() {
        mainHandler.removeCallbacks(overlayPermissionCheck)
        mainHandler.postDelayed(overlayPermissionCheck, OVERLAY_PERMISSION_CHECK_INTERVAL_MS)
    }

    /** 等 WindowManager 至少完成一轮挂载后再记录页面可见性，排除父容器切换中的瞬时 hidden。 */
    private fun scheduleOverlayVisibilityDiagnostic(root: FrameLayout) {
        root.postDelayed(
            {
                if (overlayRoot === root) {
                    floatingBrowserRuntime.coordinator.notifyOverlayWindowVisible()
                }
            },
            OVERLAY_VISIBILITY_DIAGNOSTIC_DELAY_MS
        )
    }

    /**
     * 注册系统屏幕状态广播。
     *
     * 华为由独立 SystemUI UID 发送 USER_PRESENT，因此必须允许跨 UID 系统广播；最终显示仍由
     * isScreenAvailable、App 前后台、overlay 权限和页面就绪状态共同约束。
     */
    private fun registerScreenStateReceiver() {
        if (receiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    /** 对称注销锁屏监听，防止服务销毁后继续持有实例。 */
    private fun unregisterScreenStateReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(screenStateReceiver) }
        receiverRegistered = false
    }
}
