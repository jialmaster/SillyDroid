package com.jm.sillydroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StartupCoordinatorService : Service() {
    companion object {
        private const val logTag = "StartupCoordinator"
        private const val ACTION_START = "com.jm.sillydroid.action.START"
        private const val ACTION_RETRY = "com.jm.sillydroid.action.RETRY"
        private const val ACTION_STOP_FOR_SETTINGS = "com.jm.sillydroid.action.STOP_FOR_SETTINGS"

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
    private var bootstrapJob: Job? = null
    private var serverMonitorJob: Job? = null
    private var serverProcess: ManagedProcess? = null
    private var serverLogObserver: FileObserver? = null
    private var startupPhaseBaseDetails: String? = null
    private var lastObservedServerLogLine = ""
    private val startupLogFile: File
        get() = File(filesDir, "android-tavern/logs/startup.log")
    private val serverLogFile: File
        get() = File(filesDir, "android-tavern/logs/sillydroid-server.log")

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FOR_SETTINGS) {
            serviceScope.launch {
                interruptForSettings()
            }
            return START_NOT_STICKY
        }

        val retry = intent?.action == ACTION_RETRY
        val notification = buildNotification(StartupRuntimeStore.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                BootConfig.notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(BootConfig.notificationId, notification)
        }
        startBootstrap(retry)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bootstrapJob?.cancel()
        stopServerLogObserver()
        stopManagedProcesses()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startBootstrap(forceRestart: Boolean) {
        if (!forceRestart) {
            if (bootstrapJob?.isActive == true) {
                return
            }

            val currentState = StartupRuntimeStore.state.value
            val serverAlive = serverProcess?.isAlive() == true
            if (currentState.isReady && serverAlive) {
                return
            }

            if (currentState.canRetry) {
                return
            }
        }

        bootstrapJob?.cancel()
        bootstrapJob = serviceScope.launch {
            if (forceRestart) {
                stopManagedProcesses()
            }
            runBootstrap()
        }
    }

    private suspend fun runBootstrap() {
        resetStartupLog()
        stopServerLogObserver()
        try {
            val paths = HostPaths.from(applicationContext)
            TavernConfigRepository(applicationContext).syncStoredPortFromFile()
            val servicePort = BootConfig.servicePort(applicationContext)
            val localUrl = BootConfig.localServiceUrl(applicationContext)
            val readinessUrl = BootConfig.readinessUrl(applicationContext)

            if (HealthProbe.isReady(readinessUrl)) {
                appendStartupLog("Detected existing local Tavern server at $localUrl, reusing current instance.")
                updateState(
                    StartupState(
                        phase = StartupPhase.READY,
                        message = "已连接到现有本地 Tavern 服务，正在打开 WebView。",
                        localUrl = localUrl,
                        progressPercent = 100
                    )
                )
                return
            }

            updateState(
                StartupState(
                    phase = StartupPhase.EXTRACTING,
                    message = "正在准备 Tavern bootstrap 资产。",
                    details = "首次启动时需要解包离线 rootfs、Node runtime 和 Tavern 资源。",
                    localUrl = localUrl,
                    progressPercent = 5
                )
            )
            AssetExtractor(applicationContext).extractBootstrap(paths) { message, details, progressPercent ->
                updateState(
                    StartupState(
                        phase = StartupPhase.EXTRACTING,
                        message = message,
                        details = details,
                        localUrl = localUrl,
                        progressPercent = progressPercent
                    )
                )
            }

            updateState(
                StartupState(
                    phase = StartupPhase.VALIDATING,
                    message = "正在校验 Tavern runtime 与启动脚本。",
                    details = "正在检查 bootstrap 目录结构与 DNS 配置。",
                    localUrl = localUrl,
                    progressPercent = 84
                )
            )
            BootstrapLayoutVerifier(paths).verify()
            AndroidDnsConfigWriter(applicationContext).write(paths)

            val launcher = LinuxRuntimeLauncher(paths)
            updateState(
                StartupState(
                    phase = StartupPhase.VALIDATING,
                    message = "正在初始化离线 Linux 运行时。",
                    details = "首次启动时这里可能需要几十秒，请稍等。",
                    localUrl = localUrl,
                    progressPercent = 88
                )
            )
            RootfsRuntimeProvisioner(launcher, paths).ensure { elapsedSeconds ->
                updateState(
                    StartupState(
                        phase = StartupPhase.VALIDATING,
                        message = "正在初始化离线 Linux 运行时。",
                        details = "正在执行 rootfs 校验脚本，已耗时 ${elapsedSeconds} 秒。",
                        localUrl = localUrl,
                        progressPercent = 88
                    )
                )
            }

            updateStartupPhaseState(
                phase = StartupPhase.STARTING_SERVER,
                message = "正在拉起 Tavern。",
                baseDetails = "正在启动本地 Node 服务进程。",
                localUrl = localUrl,
                progressPercent = 94
            )
            stopManagedProcesses()
            serverProcess = ServerController(launcher, paths, servicePort).start()

            updateStartupPhaseState(
                phase = StartupPhase.WAITING_READY,
                message = "正在等待本地 Tavern 服务就绪。",
                baseDetails = "正在探测本地 HTTP 服务响应。",
                localUrl = localUrl,
                progressPercent = 96
            )
            if (!HealthProbe.awaitReady(readinessUrl) { attempt, totalAttempts ->
                    val progressPercent = (96 + ((attempt.toDouble() / totalAttempts.toDouble()) * 3.0).toInt()).coerceIn(96, 99)
                    updateStartupPhaseState(
                        phase = StartupPhase.WAITING_READY,
                        message = "正在等待本地 Tavern 服务就绪。",
                        baseDetails = "健康检查 $attempt/$totalAttempts，已耗时 ${attempt} 秒。",
                        localUrl = localUrl,
                        progressPercent = progressPercent
                    )
                }) {
                throw BootstrapException("本地 Tavern 服务在等待窗口内未就绪。")
            }

            stopServerLogObserver()
            updateState(
                StartupState(
                    phase = StartupPhase.READY,
                    message = "本地 Tavern 服务已就绪，正在打开 WebView。",
                    localUrl = localUrl,
                    progressPercent = 100
                )
            )
            startServerMonitor()
        } catch (_: CancellationException) {
            appendStartupLog("Bootstrap cancelled.")
            stopServerLogObserver()
        } catch (exception: BootstrapException) {
            appendStartupLog("BootstrapException: ${exception.message ?: exception.javaClass.simpleName}")
            appendStartupLog(formatThrowable(exception))
            stopServerLogObserver()
            updateState(
                StartupState(
                    phase = StartupPhase.BLOCKED,
                    message = "Tavern bootstrap 资产还不完整。",
                    details = exception.message.orEmpty()
                )
            )
        } catch (exception: Exception) {
            appendStartupLog("Exception: ${exception.message ?: exception.javaClass.simpleName}")
            appendStartupLog(formatThrowable(exception))
            stopServerLogObserver()
            updateState(
                StartupState(
                    phase = StartupPhase.ERROR,
                    message = "本地 Tavern 服务启动失败。",
                    details = exception.message ?: exception.javaClass.simpleName
                )
            )
        }
    }

    private suspend fun interruptForSettings() {
        bootstrapJob?.cancel(CancellationException("Interrupted for bootstrap settings."))
        bootstrapJob = null
        stopServerLogObserver()
        updateState(
            StartupState(
                phase = StartupPhase.PAUSING,
                message = "正在暂停本地 Tavern 服务。",
                details = "正在停止本地服务进程，完成后会进入设置状态。",
                localUrl = BootConfig.localServiceUrl(applicationContext),
                progressPercent = 0
            )
        )
        stopManagedProcesses()
        withContext(Dispatchers.Main.immediate) {
            updateState(
                StartupState(
                    phase = StartupPhase.CONFIGURING,
                    message = "已暂停启动，请调整 Tavern 配置。",
                    localUrl = BootConfig.localServiceUrl(applicationContext),
                    progressPercent = 0
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun updateState(state: StartupState) {
        appendStartupLog(
            buildString {
                append("state=")
                append(state.phase)
                append(" message=")
                append(state.message)
                if (state.details.isNotBlank()) {
                    append(" details=")
                    append(state.details)
                }
            }
        )
        StartupRuntimeStore.update(state)
        NotificationManagerCompat.from(this).notify(BootConfig.notificationId, buildNotification(state))
    }

    private fun resetStartupLog() {
        startupLogFile.parentFile?.mkdirs()
        startupLogFile.writeText("")
    }

    private fun appendStartupLog(message: String) {
        startupLogFile.parentFile?.mkdirs()
        startupLogFile.appendText("${System.currentTimeMillis()} $message\n")
    }

    private fun updateStartupPhaseState(
        phase: StartupPhase,
        message: String,
        baseDetails: String,
        localUrl: String,
        progressPercent: Int
    ) {
        startupPhaseBaseDetails = baseDetails
        ensureServerLogObserverStarted()
        lastObservedServerLogLine = readServerLogLastLine()
        updateState(
            StartupState(
                phase = phase,
                message = message,
                details = buildStartupPhaseDetails(baseDetails),
                localUrl = localUrl,
                progressPercent = progressPercent
            )
        )
    }

    private fun ensureServerLogObserverStarted() {
        if (serverLogObserver != null) {
            return
        }

        val logsDir = serverLogFile.parentFile?.apply {
            mkdirs()
        } ?: return
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE
        serverLogObserver = object : FileObserver(logsDir, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && !path.equals(serverLogFile.name, ignoreCase = true)) {
                    return
                }

                serviceScope.launch {
                    refreshStartupPhaseLogTail()
                }
            }
        }.also { observer ->
            observer.startWatching()
        }
    }

    private fun stopServerLogObserver() {
        serverLogObserver?.stopWatching()
        serverLogObserver = null
        startupPhaseBaseDetails = null
        lastObservedServerLogLine = ""
    }

    private fun refreshStartupPhaseLogTail() {
        val currentState = StartupRuntimeStore.state.value
        val baseDetails = startupPhaseBaseDetails ?: return
        if (currentState.phase != StartupPhase.STARTING_SERVER && currentState.phase != StartupPhase.WAITING_READY) {
            return
        }

        val latestLine = readServerLogLastLine()
        if (latestLine == lastObservedServerLogLine) {
            return
        }

        lastObservedServerLogLine = latestLine
        val updatedDetails = buildStartupPhaseDetails(baseDetails)
        if (updatedDetails == currentState.details) {
            return
        }

        updateState(currentState.copy(details = updatedDetails))
    }

    private fun buildStartupPhaseDetails(baseDetails: String): String {
        val lastServerLogLine = readServerLogLastLine()
        if (lastServerLogLine.isBlank()) {
            return baseDetails
        }

        return buildString {
            append(baseDetails)
            append('\n')
            append(getString(R.string.bootstrap_startup_tavern_log_tail, lastServerLogLine))
        }
    }

    private fun readServerLogLastLine(maxChars: Int = 220): String {
        val lastLine = runCatching {
            if (!serverLogFile.exists()) {
                return@runCatching ""
            }

            serverLogFile.useLines { lines ->
                lines
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .lastOrNull()
                    .orEmpty()
            }
        }.getOrDefault("")

        if (lastLine.length <= maxChars) {
            return lastLine
        }

        return lastLine.takeLast(maxChars)
    }

    private fun readServerLogExcerpt(maxLines: Int = 24, maxChars: Int = 1800): String {
        val excerpt = runCatching {
            if (!serverLogFile.exists()) {
                return@runCatching ""
            }

            serverLogFile.readLines()
                .takeLast(maxLines)
                .joinToString("\n") { it.trimEnd() }
                .trim()
        }.getOrDefault("")

        if (excerpt.length <= maxChars) {
            return excerpt
        }

        return excerpt.takeLast(maxChars).trimStart()
    }

    private fun buildServerExitDetails(exitCode: Int): String {
        val excerpt = readServerLogExcerpt()
        if (excerpt.isBlank()) {
            return "Tavern 进程退出码：$exitCode"
        }

        return buildString {
            append("Tavern 进程退出码：")
            append(exitCode)
            append("\n\n最近服务日志：\n")
            append(excerpt)
        }
    }

    private fun recordServerExitDiagnostics(details: String) {
        val compactDetails = details.replace('\n', ' ').trim()
        appendStartupLog("Server diagnostics: $compactDetails")
        Log.e(logTag, details)
    }

    private fun formatThrowable(throwable: Throwable): String {
        val stringWriter = StringWriter()
        PrintWriter(stringWriter).use { writer ->
            throwable.printStackTrace(writer)
        }

        return stringWriter.toString().trimEnd()
    }

    private fun buildNotification(state: StartupState): Notification {
        return NotificationCompat.Builder(this, BootConfig.notificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.bootstrap_notification_title))
            .setContentText(state.message)
            .setOngoing(!state.isReady)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentIntent())
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            BootConfig.notificationChannelId,
            getString(R.string.bootstrap_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startServerMonitor() {
        val currentServerProcess = serverProcess ?: return
        serverMonitorJob?.cancel()
        serverMonitorJob = serviceScope.launch {
            val exitCode = currentServerProcess.waitFor()
            if (!isActive) {
                return@launch
            }

            val details = buildServerExitDetails(exitCode)
            appendStartupLog("Server process exited unexpectedly. exitCode=$exitCode")
            recordServerExitDiagnostics(details)
            serverProcess = null
            updateState(
                StartupState(
                    phase = StartupPhase.ERROR,
                    message = "本地 Tavern 服务已退出。",
                    details = details,
                    progressPercent = 0
                )
            )
        }
    }

    private fun stopManagedProcesses() {
        serverMonitorJob?.cancel()
        serverMonitorJob = null
        serverProcess?.stop()
        serverProcess = null
        val cleanedProcessCount = ServerProcessJanitor.cleanupLingeringServerProcesses()
        if (cleanedProcessCount > 0) {
            appendStartupLog("Cleaned $cleanedProcessCount lingering server process(es).")
        }
    }
}


