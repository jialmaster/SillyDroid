package com.jm.sillydroid.data.runtime
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.isHttpReadyTransitionSnapshot
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
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

    override fun onCreate() {
        super.onCreate()
        // 9.B: 在每次服务实例创建（含进程重启）时复位内存快照，
        // 防止上次进程残留的 BootstrapSessionRuntimeStore.snapshot 被新会话误用。
        BootstrapSessionRuntimeStore.reset()
        val graph = (applicationContext as SillyDroidAppGraphProvider).sillyDroidAppGraph
        graph.runtimeLogManager.initializeForAppStart()
        ensureNotificationChannel()
        sessionManager = BootstrapSessionManager(
            context = applicationContext,
            scope = serviceScope,
            runtimeLogs = graph.runtimeLogManager,
            hostPreferences = graph.hostConfigStore,
            settingsConfig = graph.tavernConfigRepository(),
            onSnapshotChanged = { snapshot ->
                NotificationManagerCompat.from(this).notify(
                    BootConfig.notificationId,
                    buildNotification(snapshot)
                )
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FOR_SETTINGS) {
            serviceScope.launch {
                sessionManager.stopForSettings()
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
        val notification = buildNotification(BootstrapSessionRuntimeStore.snapshot.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                BootConfig.notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(BootConfig.notificationId, notification)
        }
        sessionManager.start(forceRestart = retry)
        return START_STICKY
    }

    override fun onDestroy() {
        sessionManager.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    // 前台通知的 ready 态文案需要明确告诉用户“当前已经能回到酒馆”，
    // 不能只停留在通用的“已启动”描述，否则用户仍然不知道点击通知后的结果。
    private fun resolveNotificationContentText(snapshot: BootstrapSessionSnapshot): String {
        return resolveForegroundNotificationContentText(snapshot)
    }

    private fun buildNotification(snapshot: BootstrapSessionSnapshot): Notification {
        val normalizedProgress = snapshot.progressPercent.coerceIn(0, 100)
        val showReadyState = shouldShowForegroundReadyState(snapshot)
        val showProgress = snapshot.derivedUiFlags.showProgress && !showReadyState
        val indeterminate = normalizedProgress <= 0
        val contentText = resolveNotificationContentText(snapshot)
        val ongoing = snapshot.lifecycle == BootstrapLifecycle.RUNNING ||
            snapshot.lifecycle == BootstrapLifecycle.READY_MONITORING ||
            snapshot.lifecycle == BootstrapLifecycle.RESTART_SCHEDULED ||
            snapshot.lifecycle == BootstrapLifecycle.PAUSING_FOR_SETTINGS

        return NotificationCompat.Builder(this, BootConfig.notificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(notificationTitle)
            .setContentText(contentText)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentIntent())
            .setProgress(
                if (showProgress) 100 else 0,
                if (showProgress) normalizedProgress else 0,
                showProgress && indeterminate
            )
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            BootConfig.notificationChannelId,
            notificationTitle,
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
