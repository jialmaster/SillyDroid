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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StartupCoordinatorService : Service() {
    companion object {
        private const val logTag = "StartupCoordinator"
        private const val ACTION_START = "com.jm.sillydroid.action.START"
        private const val ACTION_RETRY = "com.jm.sillydroid.action.RETRY"
        private const val ACTION_STOP_FOR_SETTINGS = "com.jm.sillydroid.action.STOP_FOR_SETTINGS"
        // 探针节奏放宽：5s × 6 = 30s 持续不响应才视为掉线，
        // 避免偶发的 GC pause / WebView 占用 CPU 造成假阳性重启。
        private const val readyWatchdogIntervalMillis = 5_000L
        private const val readyWatchdogFailureThreshold = 6
        private const val autoRestartDelayMillis = 1_500L
        // 5 分钟窗口内最多重启 3 次；与 watchdog 30s 阈值隔开，防止连环重启迫使用户遇到 ERROR 。
        private const val autoRestartWindowMillis = 5 * 60_000L
        private const val autoRestartAttemptLimit = 3

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
    private var readyWatchdogJob: Job? = null
    private var serverProcess: ManagedProcess? = null
    private var autoRestartWindowStartedAtMs = 0L
    private var autoRestartAttemptCount = 0
    private val startupLogSessionId = AtomicLong(0L)
    private val startupLogWriter by lazy(LazyThreadSafetyMode.NONE) {
        HostLogManager.AsyncWriter { startupLogFile }
    }
    private val startupLogFile: File
        get() = HostLogManager.currentStartupLogFile(applicationContext)
    private val serverLogFile: File
        get() = HostLogManager.currentServerLogFile(applicationContext)
    private var hasResetStartupLogForCurrentProcess = false

    override fun onCreate() {
        super.onCreate()
        HostLogManager.initializeForAppStart(applicationContext)
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
        return START_STICKY
    }

    override fun onDestroy() {
        bootstrapJob?.cancel()
        stopManagedProcesses()
        serviceScope.cancel()
        startupLogWriter.close()
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

        val previousJob = bootstrapJob
        bootstrapJob = serviceScope.launch {
            // 先 join 上一份 bootstrap 协程，避免上一次还在 ServerController.start() 中间怅太
            // 就被并发 stopManagedProcesses，造成 fork 出来的 proot 进程成为 orphan 占住端口。
            previousJob?.cancelAndJoin()
            if (forceRestart) {
                stopManagedProcesses()
            }
            runBootstrap()
        }
    }

    private suspend fun runBootstrap(resetLog: Boolean = true) {
        if (resetLog && !hasResetStartupLogForCurrentProcess) {
            resetStartupLog()
            hasResetStartupLogForCurrentProcess = true
        }
        stopReadyWatchdog()
        try {
            val paths = HostPaths.from(applicationContext)
            val initialLocalUrl = BootConfig.localServiceUrl(applicationContext)
            val initialReadinessUrl = BootConfig.readinessUrl(applicationContext)

            if (HealthProbe.isReady(initialReadinessUrl)) {
                appendStartupLog("Detected existing local Tavern server at $initialLocalUrl, reusing current instance.")
                resetAutoRestartBudget()
                updateState(
                    StartupState(
                        phase = StartupPhase.READY,
                        message = "已连接到现有本地 Tavern 服务，正在打开 WebView。",
                        localUrl = initialLocalUrl,
                        progressPercent = 100
                    )
                )
                startReadyWatchdog(initialReadinessUrl, initialLocalUrl)
                return
            }

            updateState(
                StartupState(
                    phase = StartupPhase.EXTRACTING,
                    message = "正在准备 Tavern bootstrap 资产。",
                    details = "首次启动时需要解包离线 rootfs、Node runtime 和 Tavern 资源。",
                    localUrl = initialLocalUrl,
                    progressPercent = 5
                )
            )
            AssetExtractor(applicationContext).extractBootstrap(paths) { message, details, progressPercent ->
                updateState(
                    StartupState(
                        phase = StartupPhase.EXTRACTING,
                        message = message,
                        details = details,
                        localUrl = initialLocalUrl,
                        progressPercent = progressPercent
                    )
                )
            }

            TavernConfigRepository(applicationContext).syncStoredPortFromFile()
            val servicePort = BootConfig.servicePort(applicationContext)
            val localUrl = BootConfig.localServiceUrl(applicationContext)
            val readinessUrl = BootConfig.readinessUrl(applicationContext)

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
            RootfsRuntimeProvisioner(launcher, paths).ensure(
                logFileName = HostLogManager.currentRootfsRuntimeLogFileName(applicationContext)
            ) { elapsedSeconds ->
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
            serverProcess = ServerController(
                launcher = launcher,
                paths = paths,
                servicePort = servicePort,
                logFileName = HostLogManager.currentServerLogFileName(applicationContext)
            ).start()

            updateStartupPhaseState(
                phase = StartupPhase.WAITING_READY,
                message = "正在等待本地 Tavern 服务就绪。",
                baseDetails = "正在等待本地服务启动完成。",
                localUrl = localUrl,
                progressPercent = 96
            )
            if (!HealthProbe.awaitReady(readinessUrl)) {
                throw BootstrapException("本地 Tavern 服务在等待窗口内未就绪。")
            }

            resetAutoRestartBudget()
            updateState(
                StartupState(
                    phase = StartupPhase.READY,
                    message = "本地 Tavern 服务已就绪，正在打开 WebView。",
                    localUrl = localUrl,
                    progressPercent = 100
                )
            )
            startServerMonitor()
            startReadyWatchdog(readinessUrl, localUrl)
        } catch (_: CancellationException) {
            appendStartupLog("Bootstrap cancelled.")
            stopReadyWatchdog()
        } catch (exception: BootstrapException) {
            appendStartupLog("BootstrapException: ${exception.message ?: exception.javaClass.simpleName}")
            appendStartupLog(formatThrowable(exception))
            stopReadyWatchdog()
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
            stopReadyWatchdog()
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
        // cancelAndJoin 避免 bootstrap 协程中间怅太时与 stopManagedProcesses 发生竞态，
        // 造成刚 fork 出、还未写入 serverProcess 字段的 proot 进程成为 orphan。
        bootstrapJob?.let { job ->
            job.cancel(CancellationException("Interrupted for bootstrap settings."))
            withContext(NonCancellable) {
                runCatching { job.join() }
            }
        }
        bootstrapJob = null
        stopReadyWatchdog()
        resetAutoRestartBudget()
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
        val sessionId = startupLogSessionId.incrementAndGet()
        startupLogWriter.reset(sessionId)
    }

    private fun appendStartupLog(message: String) {
        val sessionId = startupLogSessionId.get()
        startupLogWriter.append(
            sessionId = sessionId,
            line = "${formatStartupLogTimestamp(System.currentTimeMillis())} $message\n"
        )
    }

    private fun formatStartupLogTimestamp(epochMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(epochMillis))
    }

    private fun updateStartupPhaseState(
        phase: StartupPhase,
        message: String,
        baseDetails: String,
        localUrl: String,
        progressPercent: Int
    ) {
        updateState(
            StartupState(
                phase = phase,
                message = message,
                details = baseDetails,
                localUrl = localUrl,
                progressPercent = progressPercent
            )
        )
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
        val normalizedProgress = state.progressPercent.coerceIn(0, 100)
        val showProgress = !state.isReady && !state.canRetry && state.phase != StartupPhase.CONFIGURING
        val indeterminate = normalizedProgress <= 0
        val contentText = if (showProgress && !indeterminate) {
            "${state.message} (${normalizedProgress}%)"
        } else {
            state.message
        }

        return NotificationCompat.Builder(this, BootConfig.notificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.bootstrap_notification_title))
            .setContentText(contentText)
            .setOngoing(!state.isReady)
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
            scheduleAutoRestart(
                message = "本地 Tavern 服务已退出，正在自动重启。",
                details = details
            )
        }
    }

    private fun startReadyWatchdog(readinessUrl: String, localUrl: String) {
        readyWatchdogJob?.cancel()
        readyWatchdogJob = serviceScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(readyWatchdogIntervalMillis)
                if (HealthProbe.isReady(readinessUrl)) {
                    consecutiveFailures = 0
                    continue
                }

                consecutiveFailures += 1
                appendStartupLog(
                    "Ready watchdog probe failed ($consecutiveFailures/$readyWatchdogFailureThreshold): $readinessUrl"
                )
                if (consecutiveFailures < readyWatchdogFailureThreshold) {
                    continue
                }

                serverProcess = null
                scheduleAutoRestart(
                    message = "本地 Tavern 服务失去响应，正在自动重启。",
                    details = buildString {
                        append("连续 ")
                        append(readyWatchdogFailureThreshold)
                        append(" 次探针检测失败：")
                        append(localUrl)
                    },
                    localUrl = localUrl
                )
                return@launch
            }
        }
    }

    private fun stopReadyWatchdog() {
        readyWatchdogJob?.cancel()
        readyWatchdogJob = null
    }

    private fun scheduleAutoRestart(
        message: String,
        details: String,
        localUrl: String = BootConfig.localServiceUrl(applicationContext)
    ) {
        stopReadyWatchdog()
        // 原实现只停了 watchdog，但 serverMonitorJob 还能在后续检测到 server 退出后再调一次
        // scheduleAutoRestart，提前耗尽重启预算。在调度重启前同时取消。
        serverMonitorJob?.cancel()
        serverMonitorJob = null
        val attempt = recordAutoRestartAttempt() ?: run {
            updateState(
                StartupState(
                    phase = StartupPhase.ERROR,
                    message = "本地 Tavern 服务反复异常，已停止自动重启。",
                    details = details,
                    localUrl = localUrl,
                    progressPercent = 0
                )
            )
            return
        }

        // 不在这里马上 updateState(STARTING_SERVER)：
        // 过去在 delay(1.5s) 之前就把 phase 降级会让 MainActivity 立即隐藏 WebView，
        // 造成“凭空出现白屏”。现在 phase 保持不变（仍是 READY），
        // 只记入当前 app 启动对应的 startup 日志方便事后追查；真正进入重启后由 runBootstrap 自然推进 phase。
        appendStartupLog(
            "Auto-restart scheduled (attempt $attempt/$autoRestartAttemptLimit): $message | $details"
        )

        val previousJob = bootstrapJob
        bootstrapJob = serviceScope.launch {
            previousJob?.cancelAndJoin()
            delay(autoRestartDelayMillis)
            stopManagedProcesses()
            // resetLog=false 保留上一次服务退出 / watchdog 失败的诊断信息，
            // 避免被下一轮 bootstrap 的 resetStartupLog 抹除。
            runBootstrap(resetLog = false)
        }
    }

    private fun recordAutoRestartAttempt(nowMs: Long = System.currentTimeMillis()): Int? {
        if (autoRestartWindowStartedAtMs == 0L || nowMs - autoRestartWindowStartedAtMs > autoRestartWindowMillis) {
            autoRestartWindowStartedAtMs = nowMs
            autoRestartAttemptCount = 0
        }

        if (autoRestartAttemptCount >= autoRestartAttemptLimit) {
            return null
        }

        autoRestartAttemptCount += 1
        return autoRestartAttemptCount
    }

    private fun resetAutoRestartBudget() {
        autoRestartWindowStartedAtMs = 0L
        autoRestartAttemptCount = 0
    }

    private fun stopManagedProcesses() {
        serverMonitorJob?.cancel()
        serverMonitorJob = null
        stopReadyWatchdog()
        serverProcess?.stop()
        serverProcess = null
        val cleanedProcessCount = ServerProcessJanitor.cleanupLingeringServerProcesses()
        if (cleanedProcessCount > 0) {
            appendStartupLog("Cleaned $cleanedProcessCount lingering server process(es).")
        }
    }
}
