package com.jm.sillydroid.data.runtime
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.jm.sillydroid.core.model.notification.HostForegroundBehavior
import com.jm.sillydroid.core.model.notification.HostNotificationChannel
import com.jm.sillydroid.core.model.notification.HostNotificationKind
import com.jm.sillydroid.core.model.notification.HostNotificationProgress
import com.jm.sillydroid.core.model.notification.HostNotificationSpec
import com.jm.sillydroid.core.model.notification.HostNotificationTapSpec
import com.jm.sillydroid.core.model.notification.HostNotificationAction
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.isHttpReadyTransitionSnapshot
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.domain.notification.HostNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StartupCoordinatorService : Service() {
    companion object {
        private const val ACTION_START = "com.jm.sillydroid.action.START"
        private const val ACTION_RETRY = "com.jm.sillydroid.action.RETRY"
        private const val ACTION_STOP_FOR_SETTINGS = "com.jm.sillydroid.action.STOP_FOR_SETTINGS"
        private const val notificationTitle = "SillyDroid 启动服务"
        private const val foregroundNotificationKey = "foreground-bootstrap"

        fun createStartIntent(context: Context, retry: Boolean = false): Intent {
            return Intent(context, StartupCoordinatorService::class.java).apply {
                action = if (retry) ACTION_RETRY else ACTION_START
            }
        }

        fun createStopForSettingsIntent(context: Context): Intent {
            return Intent(context, StartupCoordinatorService::class.java).apply {
                action = ACTION_STOP_FOR_SETTINGS
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sessionManager: BootstrapSessionManager
    private lateinit var hostNotificationService: HostNotificationService

    override fun onCreate() {
        super.onCreate()
        // 9.B: 在每次服务实例创建（含进程重启）时复位内存快照，
        // 防止上次进程残留的 BootstrapSessionRuntimeStore.snapshot 被新会话误用。
        BootstrapSessionRuntimeStore.reset()
        val graph = (applicationContext as SillyDroidAppGraphProvider).sillyDroidAppGraph
        graph.runtimeLogManager.initializeForAppStart()
        hostNotificationService = graph.hostNotificationService
        hostNotificationService.ensureChannels()
        sessionManager = BootstrapSessionManager(
            context = applicationContext,
            scope = serviceScope,
            runtimeLogs = graph.runtimeLogManager,
            appForegroundState = graph.appForegroundState,
            hostPreferences = graph.hostConfigStore,
            settingsConfig = graph.tavernConfigRepository(),
            onSnapshotChanged = { snapshot ->
                hostNotificationService.postForeground(this@StartupCoordinatorService, buildForegroundNotificationSpec(snapshot))
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FOR_SETTINGS) {
            serviceScope.launch {
                sessionManager.stopForSettings()
                hostNotificationService.remove(foregroundNotificationKey)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val retry = intent?.action == ACTION_RETRY
        // 前台通知必须走统一通知服务的 foreground 入口：
        // 这里不直接 startForeground / notify，避免启动阶段和 ready 阶段出现两套更新语义。
        hostNotificationService.postForeground(
            this,
            buildForegroundNotificationSpec(BootstrapSessionRuntimeStore.snapshot.value)
        )
        sessionManager.start(forceRestart = retry)
        return START_STICKY
    }

    override fun onDestroy() {
        sessionManager.close()
        serviceScope.cancel()
        hostNotificationService.remove(foregroundNotificationKey)
        super.onDestroy()
    }

    // 前台通知的 ready 态文案需要明确告诉用户“当前已经能回到酒馆”，
    // 不能只停留在通用的“已启动”描述，否则用户仍然不知道点击通知后的结果。
    private fun resolveNotificationContentText(snapshot: BootstrapSessionSnapshot): String {
        return resolveForegroundNotificationContentText(snapshot)
    }

    private fun buildForegroundNotificationSpec(snapshot: BootstrapSessionSnapshot): HostNotificationSpec {
        val normalizedProgress = snapshot.progressPercent.coerceIn(0, 100)
        val showReadyState = shouldShowForegroundReadyState(snapshot)
        val progress = when {
            !snapshot.derivedUiFlags.showProgress || showReadyState -> HostNotificationProgress.None
            normalizedProgress <= 0 -> HostNotificationProgress.Indeterminate
            else -> HostNotificationProgress.Determinate(
                current = normalizedProgress,
                max = 100
            )
        }
        val contentText = resolveNotificationContentText(snapshot)
        val ongoing = snapshot.lifecycle == BootstrapLifecycle.RUNNING ||
            snapshot.lifecycle == BootstrapLifecycle.READY_MONITORING ||
            snapshot.lifecycle == BootstrapLifecycle.RESTART_SCHEDULED ||
            snapshot.lifecycle == BootstrapLifecycle.PAUSING_FOR_SETTINGS

        return HostNotificationSpec(
            notificationKey = foregroundNotificationKey,
            kind = HostNotificationKind.FOREGROUND_BOOTSTRAP,
            channel = HostNotificationChannel.FOREGROUND_RUNTIME,
            title = notificationTitle,
            body = contentText,
            progress = progress,
            ongoing = ongoing,
            autoCancel = false,
            tapSpec = HostNotificationTapSpec(action = HostNotificationAction.OPEN_MAIN),
            smallIconResId = android.R.drawable.stat_notify_sync,
            foregroundBehavior = HostForegroundBehavior(
                serviceNotificationId = BootConfig.notificationId,
                foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    null
                }
            )
        )
    }

}

internal fun shouldShowForegroundReadyState(snapshot: BootstrapSessionSnapshot): Boolean {
    // WAIT_HTTP_READY 成功和 READY_MONITORING 生命周期切换是两条连续快照；
    // 前台通知只要看到“HTTP 已就绪”的过渡态，就必须直接按 ready 展示，
    // 不能把中间那条 100% 等待快照保留下来。
    return snapshot.lifecycle == BootstrapLifecycle.READY_MONITORING ||
        snapshot.isHttpReadyTransitionSnapshot()
}

internal fun resolveForegroundNotificationContentText(snapshot: BootstrapSessionSnapshot): String {
    val normalizedProgress = snapshot.progressPercent.coerceIn(0, 100)
    if (shouldShowForegroundReadyState(snapshot)) {
        return "SillyTavern 已启动，点击返回酒馆"
    }
    return if (snapshot.derivedUiFlags.showProgress && normalizedProgress > 0) {
        "${snapshot.statusMessage} (${normalizedProgress}%)"
    } else {
        snapshot.statusMessage
    }
}
