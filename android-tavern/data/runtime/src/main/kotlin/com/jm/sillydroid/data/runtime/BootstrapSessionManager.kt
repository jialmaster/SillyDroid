package com.jm.sillydroid.data.runtime
import android.content.Context
import android.util.Log
import com.jm.sillydroid.core.model.bootstrap.BootstrapCurrentLogTargets
import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapFailureSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapLogKind
import com.jm.sillydroid.core.model.bootstrap.BootstrapRestartBudgetSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepDetection
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepResult
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepStatus
import com.jm.sillydroid.core.model.bootstrap.defaultBootstrapSteps
import com.jm.sillydroid.core.model.bootstrap.defaultLogKind
import com.jm.sillydroid.core.model.bootstrap.displayLabel
import com.jm.sillydroid.core.model.bootstrap.shouldReportCurrentStepElapsedSeconds
import com.jm.sillydroid.core.model.bootstrap.shouldReportTavernStartupTail
import com.jm.sillydroid.core.model.bootstrap.withDerivedUiFlags
import com.jm.sillydroid.domain.runtime.RuntimeLogManager
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.domain.settings.SettingsConfigRepository
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootstrapSessionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val runtimeLogs: RuntimeLogManager,
    private val hostPreferences: HostPreferencesRepository,
    private val settingsConfig: SettingsConfigRepository,
    private val onSnapshotChanged: (BootstrapSessionSnapshot) -> Unit = {}
) {
    companion object {
        private const val logTag = "BootstrapSession"
        private const val readyWatchdogIntervalMillis = 5_000L
        private const val readyWatchdogFailureThreshold = 6
        private const val readyWatchdogSuccessLogEvery = 12
        private const val autoRestartDelayMillis = 1_500L
        private const val autoRestartWindowMillis = 5 * 60_000L
        private const val autoRestartAttemptLimit = 3
    }

    private val appContext = context.applicationContext
    private val startupLogWriter by lazy(LazyThreadSafetyMode.NONE) {
        runtimeLogs.openStartupAsyncWriter()
    }
    private fun localServiceUrl(): String = "http://127.0.0.1:${hostPreferences.servicePort}"
    private fun readinessUrl(): String = "${localServiceUrl()}/"
    private val startupLogSessionId = AtomicLong(0L)
    private var hasResetStartupLogForCurrentProcess = false
    private var bootstrapJob: Job? = null
    private var currentStepElapsedRefreshJob: Job? = null
    private var currentStepElapsedAnchorMillis = 0L
    private var serverMonitorJob: Job? = null
    private var tavernServerTailJob: Job? = null
    private var observedTavernServerTailLogFileName: String? = null
    private var readyWatchdogJob: Job? = null
    private var serverProcess: ManagedProcess? = null
    private var autoRestartBudget = BootstrapAutoRestartBudget(
        attemptLimit = autoRestartAttemptLimit,
        windowMillis = autoRestartWindowMillis
    )
    private var currentSnapshot = BootstrapSessionRuntimeStore.snapshot.value
    private var rootfsAssetsRefreshed = false

    fun start(forceRestart: Boolean) {
        if (!forceRestart) {
            if (bootstrapJob?.isActive == true) {
                return
            }

            val current = currentSnapshot
            if (current.isReady && HealthProbe.isReady(readinessUrl())) {
                return
            }

            if (current.canRetry) {
                return
            }
        }

        val previousJob = bootstrapJob
        bootstrapJob = scope.launch {
            previousJob?.cancelAndJoin()
            if (forceRestart) {
                stopManagedProcesses()
            }
            runBootstrapAttempt(forceRestart)
        }
    }

    suspend fun stopForSettings() {
        emitEvent { snapshot ->
            BootstrapEvent.SettingsPauseRequested(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = System.currentTimeMillis()
            )
        }
        bootstrapJob?.let { job ->
            job.cancel(CancellationException("Interrupted for bootstrap settings."))
            withContext(NonCancellable) {
                runCatching { job.join() }
            }
        }
        bootstrapJob = null
        stopReadyWatchdog(reason = "entering bootstrap settings")
        resetAutoRestartBudget()
        publishSnapshot(
            currentSnapshot.copy(
                lifecycle = BootstrapLifecycle.PAUSING_FOR_SETTINGS,
                currentStepId = null,
                statusMessage = "正在暂停本地 Tavern 服务。",
                statusDetails = "正在停止本地服务进程，完成后会进入设置状态。"
            ),
            logLine = "lifecycle=${BootstrapLifecycle.PAUSING_FOR_SETTINGS} details=正在停止本地服务进程，完成后会进入设置状态。"
        )
        stopManagedProcesses()
        publishSnapshot(
            currentSnapshot.copy(
                lifecycle = BootstrapLifecycle.CONFIGURING,
                currentStepId = null,
                statusMessage = "已暂停启动，请调整 Tavern 配置。",
                statusDetails = ""
            ),
            logLine = "lifecycle=${BootstrapLifecycle.CONFIGURING} message=已暂停启动，请调整 Tavern 配置。"
        )
        emitEvent { snapshot ->
            BootstrapEvent.SessionFinished(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = System.currentTimeMillis(),
                lifecycle = snapshot.lifecycle
            )
        }
    }

    fun close() {
        bootstrapJob?.cancel()
        stopRuntimeStatusReporting()
        // Fire-and-forget process teardown to avoid blocking the caller (typically Activity.onDestroy on the main thread).
        scope.launch { runCatching { stopManagedProcesses() } }
        startupLogWriter.close()
    }

    private suspend fun runBootstrapAttempt(forceRestart: Boolean) {
        stopReadyWatchdog(reason = "starting new bootstrap attempt")
        stopRuntimeStatusReporting()
        rootfsAssetsRefreshed = false

        runtimeLogs.initializeForAppStart()
        if (!hasResetStartupLogForCurrentProcess) {
            resetStartupLog()
            hasResetStartupLogForCurrentProcess = true
        }
        val appSessionId = runtimeLogs.currentAppSessionId()
        val attemptId = (currentSnapshot.attemptId + 1).coerceAtLeast(1)
        val localUrl = localServiceUrl()
        val logTargets = buildLogTargets()
        val baseSnapshot = BootstrapSessionSnapshot(
            appSessionId = appSessionId,
            attemptId = attemptId,
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = null,
            steps = defaultBootstrapSteps().map { step ->
                step.copy(logFileName = logFileNameForKind(logTargets, step.id.defaultLogKind()))
            },
            localUrl = localUrl,
            statusMessage = "正在准备 SillyDroid 宿主环境。",
            statusDetails = "",
            restartBudget = buildRestartBudgetSnapshot(),
            currentLogTargets = logTargets,
            bootstrapPreviouslyCompleted = hasBootstrapManifest()
        )
        publishSnapshot(baseSnapshot, logLine = "session_started attempt=$attemptId forceRestart=$forceRestart")
        emitEvent { snapshot ->
            BootstrapEvent.SessionStarted(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = System.currentTimeMillis(),
                forceRestart = forceRestart
            )
        }

        try {
            val paths = HostPaths.from(appContext)
            val extractor = AssetExtractor(appContext)
            val readinessUrl = readinessUrl()

            startStep(
                stepId = BootstrapStepId.DETECT_EXISTING_SERVER,
                detection = BootstrapStepDetection.REQUIRED,
                statusMessage = "正在检测现有本地 Tavern 服务。",
                statusDetails = "正在探测 127.0.0.1 本地 HTTP 服务。"
            )
            if (HealthProbe.isReady(readinessUrl)) {
                completeStep(
                    stepId = BootstrapStepId.DETECT_EXISTING_SERVER,
                    detection = BootstrapStepDetection.REUSED_RUNNING_SERVER,
                    result = BootstrapStepResult.SUCCESS,
                    details = "检测到现有本地 Tavern 服务，当前启动会话将直接复用。"
                )
                skipRemainingPendingSteps(
                    details = "已复用现有本地 Tavern 服务实例。",
                    result = BootstrapStepResult.SKIPPED_REUSED,
                    detection = BootstrapStepDetection.REUSED_RUNNING_SERVER
                )
                resetAutoRestartBudget()
                publishSnapshot(
                    currentSnapshot.copy(
                        lifecycle = BootstrapLifecycle.READY_MONITORING,
                        currentStepId = null,
                        statusMessage = "已连接到现有本地 Tavern 服务。",
                        statusDetails = "本地 HTTP 服务已可访问，当前会话正在复用现有服务实例。",
                        bootstrapPreviouslyCompleted = true,
                        restartBudget = buildRestartBudgetSnapshot()
                    ),
                    logLine = "lifecycle=${BootstrapLifecycle.READY_MONITORING} message=已连接到现有本地 Tavern 服务。 details=本地 HTTP 服务已可访问，当前会话正在复用现有服务实例。"
                )
                startReadyWatchdog(readinessUrl, localUrl)
                return
            }
            completeStep(
                stepId = BootstrapStepId.DETECT_EXISTING_SERVER,
                detection = BootstrapStepDetection.REQUIRED,
                result = BootstrapStepResult.SUCCESS,
                details = "未检测到可复用的本地服务，本次会话将执行完整启动流程。"
            )

            startStep(
                stepId = BootstrapStepId.PREPARE_LOG_SESSION,
                detection = BootstrapStepDetection.REQUIRED,
                statusMessage = "正在准备日志会话。",
                statusDetails = "会保留最近 5 次 app 启动对应的整套日志。"
            )
            runtimeLogs.initializeForAppStart()
            publishSnapshot(
                currentSnapshot.copy(
                    appSessionId = runtimeLogs.currentAppSessionId(),
                    currentLogTargets = buildLogTargets()
                ),
                logLine = "log_session appSessionId=${runtimeLogs.currentAppSessionId()} retainedLimit=${runtimeLogs.retainedAppSessionLimit()}"
            )
            completeStep(
                stepId = BootstrapStepId.PREPARE_LOG_SESSION,
                detection = BootstrapStepDetection.EXISTING_LOG_SESSION,
                result = BootstrapStepResult.SUCCESS,
                details = "当前 app 启动日志会话已就绪，保留策略为最近 ${runtimeLogs.retainedAppSessionLimit()} 次启动。"
            )

            startStep(
                stepId = BootstrapStepId.PREPARE_WORKDIRS,
                detection = BootstrapStepDetection.REQUIRED,
                statusMessage = "正在准备宿主工作目录。",
                statusDetails = "正在创建 bootstrap、data 和日志目录。"
            )
            extractor.prepareWorkDirectories(paths)
            completeStep(
                stepId = BootstrapStepId.PREPARE_WORKDIRS,
                detection = BootstrapStepDetection.REQUIRED,
                result = BootstrapStepResult.SUCCESS,
                details = "bootstrap、data 与日志目录已准备完成。"
            )

            val rootfsInspection = extractor.inspectRootfsAssets(paths)
            if (rootfsInspection.detection == BootstrapStepDetection.UP_TO_DATE) {
                skipStep(
                    stepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
                    detection = rootfsInspection.detection,
                    result = BootstrapStepResult.SKIPPED_UP_TO_DATE,
                    statusMessage = "Termux 与 rootfs 资产已是最新。",
                    details = rootfsInspection.details
                )
            } else {
                startStep(
                    stepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
                    detection = rootfsInspection.detection,
                    statusMessage = "正在准备 Termux 与 rootfs 资产。",
                    statusDetails = rootfsInspection.details
                )
                rootfsAssetsRefreshed = extractor.prepareRootfsAssets(paths) { details, progressPercent ->
                    heartbeatStep(
                        stepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
                        details = details,
                        progressPercent = progressPercent
                    )
                }
                completeStep(
                    stepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
                    detection = rootfsInspection.detection,
                    result = BootstrapStepResult.SUCCESS,
                    details = "Termux 与 rootfs 资产已同步完成。"
                )
            }

            val serverInspection = extractor.inspectServerAssets(paths)
            if (serverInspection.detection == BootstrapStepDetection.UP_TO_DATE) {
                skipStep(
                    stepId = BootstrapStepId.PREPARE_SERVER_ASSETS,
                    detection = serverInspection.detection,
                    result = BootstrapStepResult.SKIPPED_UP_TO_DATE,
                    statusMessage = "Tavern 资产已是最新。",
                    details = serverInspection.details
                )
            } else {
                startStep(
                    stepId = BootstrapStepId.PREPARE_SERVER_ASSETS,
                    detection = serverInspection.detection,
                    statusMessage = "正在准备 Tavern 资产。",
                    statusDetails = serverInspection.details
                )
                extractor.prepareServerAssets(paths, rootfsAssetsRefreshed) { details, progressPercent ->
                    heartbeatStep(
                        stepId = BootstrapStepId.PREPARE_SERVER_ASSETS,
                        details = details,
                        progressPercent = progressPercent
                    )
                }
                completeStep(
                    stepId = BootstrapStepId.PREPARE_SERVER_ASSETS,
                    detection = serverInspection.detection,
                    result = BootstrapStepResult.SUCCESS,
                    details = "Tavern payload、脚本与内置扩展已同步完成。"
                )
            }

            settingsConfig.syncStoredPortFromFile()
            val servicePort = hostPreferences.servicePort
            val resolvedLocalUrl = localServiceUrl()
            val resolvedReadinessUrl = readinessUrl()
            publishSnapshot(currentSnapshot.copy(localUrl = resolvedLocalUrl))

            startStep(
                stepId = BootstrapStepId.VALIDATE_RUNTIME_LAYOUT,
                detection = BootstrapStepDetection.REQUIRED,
                statusMessage = "正在校验运行时布局。",
                statusDetails = "正在检查 bootstrap 目录结构与 DNS 配置。"
            )
            BootstrapLayoutVerifier(paths).verify()
            AndroidDnsConfigWriter(appContext).write(paths)
            completeStep(
                stepId = BootstrapStepId.VALIDATE_RUNTIME_LAYOUT,
                detection = BootstrapStepDetection.REQUIRED,
                result = BootstrapStepResult.SUCCESS,
                details = "bootstrap 目录结构与 Android DNS 配置校验通过。"
            )

            val launcher = LinuxRuntimeLauncher(paths)
            startStep(
                stepId = BootstrapStepId.ENSURE_ROOTFS_RUNTIME,
                detection = BootstrapStepDetection.REQUIRED,
                statusMessage = "正在初始化离线 Linux 运行时。",
                statusDetails = "首次启动时这里可能需要几十秒，请稍等。"
            )
            RootfsRuntimeProvisioner(launcher, paths).ensure(
                logFileName = runtimeLogs.currentRootfsRuntimeLogFileName()
            ) { elapsedSeconds ->
                val progressPercent = (15 + elapsedSeconds * 8).coerceAtMost(95)
                heartbeatStep(
                    stepId = BootstrapStepId.ENSURE_ROOTFS_RUNTIME,
                    details = "正在执行 rootfs 校验脚本，已耗时 ${elapsedSeconds} 秒。",
                    progressPercent = progressPercent
                )
            }
            completeStep(
                stepId = BootstrapStepId.ENSURE_ROOTFS_RUNTIME,
                detection = BootstrapStepDetection.REQUIRED,
                result = BootstrapStepResult.SUCCESS,
                details = "离线 Linux 运行时校验完成。"
            )

            startStep(
                stepId = BootstrapStepId.START_SERVER_PROCESS,
                detection = BootstrapStepDetection.REQUIRED,
                statusMessage = "正在启动 Tavern 进程。",
                statusDetails = "正在启动本地 Node 服务进程。"
            )
            stopManagedProcesses()
            serverProcess = ServerController(
                launcher = launcher,
                paths = paths,
                servicePort = servicePort,
                logFileName = runtimeLogs.currentServerLogFileName()
            ).start()
            completeStep(
                stepId = BootstrapStepId.START_SERVER_PROCESS,
                detection = BootstrapStepDetection.REQUIRED,
                result = BootstrapStepResult.SUCCESS,
                details = "Tavern 进程已拉起，开始等待 HTTP 服务就绪。"
            )
            startServerMonitor()

            startStep(
                stepId = BootstrapStepId.WAIT_HTTP_READY,
                detection = BootstrapStepDetection.REQUIRED,
                statusMessage = "正在等待 HTTP 服务就绪。",
                statusDetails = "正在等待 127.0.0.1 本地服务完成启动。"
            )
            if (!HealthProbe.awaitReady(resolvedReadinessUrl) { attempt, totalAttempts ->
                    emitEvent { snapshot ->
                        BootstrapEvent.ProbeAttempted(
                            appSessionId = snapshot.appSessionId,
                            attemptId = snapshot.attemptId,
                            happenedAtMillis = System.currentTimeMillis(),
                            stepId = BootstrapStepId.WAIT_HTTP_READY,
                            attempt = attempt,
                            totalAttempts = totalAttempts,
                            targetUrl = resolvedReadinessUrl
                        )
                    }
                    heartbeatStep(
                        stepId = BootstrapStepId.WAIT_HTTP_READY,
                        details = "第 $attempt/$totalAttempts 次 HTTP 探针检测：$resolvedReadinessUrl",
                        progressPercent = ((attempt.toDouble() / totalAttempts.toDouble()) * 100.0).toInt().coerceIn(1, 99)
                    )
                }) {
                throw BootstrapException(BootstrapError.ServerNotReady("本地 Tavern 服务在等待窗口内未就绪。"))
            }
            completeStep(
                stepId = BootstrapStepId.WAIT_HTTP_READY,
                detection = BootstrapStepDetection.REQUIRED,
                result = BootstrapStepResult.SUCCESS,
                details = "本地 HTTP 服务已可访问。"
            )

            resetAutoRestartBudget()
            publishSnapshot(
                currentSnapshot.copy(
                    lifecycle = BootstrapLifecycle.READY_MONITORING,
                    currentStepId = null,
                    statusMessage = "本地 Tavern 服务已就绪。",
                    statusDetails = "本地 HTTP 服务已可访问，已进入就绪监控。",
                    bootstrapPreviouslyCompleted = true,
                    restartBudget = buildRestartBudgetSnapshot()
                ),
                logLine = "lifecycle=${BootstrapLifecycle.READY_MONITORING} message=本地 Tavern 服务已就绪。 details=本地 HTTP 服务已可访问，已进入就绪监控。"
            )
            startReadyWatchdog(resolvedReadinessUrl, resolvedLocalUrl)
        } catch (_: CancellationException) {
            appendStartupLog("Bootstrap cancelled.")
            stopReadyWatchdog(reason = "bootstrap attempt cancelled")
        } catch (exception: BootstrapException) {
            appendStartupLog("BootstrapException: ${exception.message ?: exception.javaClass.simpleName}")
            appendStartupLog(formatThrowable(exception))
            stopReadyWatchdog(reason = "bootstrap attempt failed with blocked error")
            failCurrentStep(
                blocked = true,
                title = "Tavern bootstrap 资产还不完整。",
                details = exception.message.orEmpty(),
                throwableType = exception.javaClass.simpleName
            )
        } catch (exception: Exception) {
            appendStartupLog("Exception: ${exception.message ?: exception.javaClass.simpleName}")
            appendStartupLog(formatThrowable(exception))
            stopReadyWatchdog(reason = "bootstrap attempt failed with unexpected error")
            failCurrentStep(
                blocked = false,
                title = "本地 Tavern 服务启动失败。",
                details = exception.message ?: exception.javaClass.simpleName,
                throwableType = exception.javaClass.simpleName
            )
        }
    }

    private fun startStep(
        stepId: BootstrapStepId,
        detection: BootstrapStepDetection,
        statusMessage: String,
        statusDetails: String
    ) {
        val nowMillis = System.currentTimeMillis()
        val updatedSteps = BootstrapStepLedger.startRunning(
            steps = currentSnapshot.steps,
            stepId = stepId,
            detection = detection,
            statusDetails = statusDetails,
            nowMillis = nowMillis
        )
        publishSnapshot(
            currentSnapshot.copy(
                lifecycle = BootstrapLifecycle.RUNNING,
                currentStepId = stepId,
                steps = updatedSteps,
                statusMessage = statusMessage,
                statusDetails = statusDetails,
                currentStepElapsedSeconds = 0,
                currentTavernServerLogLine = ""
            ),
            logLine = "step_started id=$stepId detection=$detection details=$statusDetails"
        )
        emitEvent { snapshot ->
            BootstrapEvent.StepStarted(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = nowMillis,
                stepId = stepId
            )
        }
    }

    private fun heartbeatStep(
        stepId: BootstrapStepId,
        details: String,
        progressPercent: Int
    ) {
        val normalizedProgress = progressPercent.coerceIn(0, 99)
        val updatedSteps = BootstrapStepLedger.heartbeat(
            steps = currentSnapshot.steps,
            stepId = stepId,
            details = details,
            progressPercent = normalizedProgress
        )
        publishSnapshot(
            currentSnapshot.copy(
                steps = updatedSteps,
                currentStepId = stepId,
                statusDetails = details
            ),
            logLine = "step_heartbeat id=$stepId progress=$normalizedProgress details=$details"
        )
        emitEvent { snapshot ->
            BootstrapEvent.StepHeartbeat(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = System.currentTimeMillis(),
                stepId = stepId,
                details = details,
                progressPercent = normalizedProgress
            )
        }
    }

    private fun completeStep(
        stepId: BootstrapStepId,
        detection: BootstrapStepDetection,
        result: BootstrapStepResult,
        details: String
    ) {
        val nowMillis = System.currentTimeMillis()
        val updatedSteps = BootstrapStepLedger.completed(
            steps = currentSnapshot.steps,
            stepId = stepId,
            detection = detection,
            result = result,
            details = details,
            nowMillis = nowMillis
        )
        publishSnapshot(
            currentSnapshot.copy(
                steps = updatedSteps,
                statusDetails = details,
                bootstrapPreviouslyCompleted = currentSnapshot.bootstrapPreviouslyCompleted || hasBootstrapManifest()
            ),
            logLine = "step_completed id=$stepId result=$result details=$details"
        )
        emitEvent { snapshot ->
            BootstrapEvent.StepCompleted(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = nowMillis,
                stepId = stepId
            )
        }
    }

    private fun skipStep(
        stepId: BootstrapStepId,
        detection: BootstrapStepDetection,
        result: BootstrapStepResult,
        statusMessage: String,
        details: String
    ) {
        val nowMillis = System.currentTimeMillis()
        val updatedSteps = BootstrapStepLedger.skipped(
            steps = currentSnapshot.steps,
            stepId = stepId,
            detection = detection,
            result = result,
            details = details,
            nowMillis = nowMillis
        )
        publishSnapshot(
            currentSnapshot.copy(
                steps = updatedSteps,
                currentStepId = null,
                statusMessage = statusMessage,
                statusDetails = details
            ),
            logLine = "step_skipped id=$stepId result=$result details=$details"
        )
        emitEvent { snapshot ->
            BootstrapEvent.StepSkipped(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = nowMillis,
                stepId = stepId,
                result = result
            )
        }
    }

    private fun skipRemainingPendingSteps(
        details: String,
        result: BootstrapStepResult,
        detection: BootstrapStepDetection
    ) {
        val nowMillis = System.currentTimeMillis()
        val updatedSteps = BootstrapStepLedger.skipRemainingPending(
            steps = currentSnapshot.steps,
            detection = detection,
            result = result,
            details = details,
            nowMillis = nowMillis
        )
        publishSnapshot(currentSnapshot.copy(steps = updatedSteps), logLine = "step_skip_remaining result=$result details=$details")
    }

    private fun failCurrentStep(
        blocked: Boolean,
        title: String,
        details: String,
        throwableType: String?
    ) {
        val failedStepId = currentSnapshot.currentStepId
        val nowMillis = System.currentTimeMillis()
        val updatedSteps = BootstrapStepLedger.failed(
            steps = currentSnapshot.steps,
            stepId = failedStepId,
            blocked = blocked,
            details = details,
            nowMillis = nowMillis
        )
        publishSnapshot(
            currentSnapshot.copy(
                lifecycle = if (blocked) BootstrapLifecycle.FAILED_BLOCKED else BootstrapLifecycle.FAILED_ERROR,
                steps = updatedSteps,
                statusMessage = title,
                statusDetails = details,
                lastFailure = BootstrapFailureSnapshot(
                    stepId = failedStepId,
                    title = title,
                    details = details,
                    isBlocked = blocked,
                    throwableType = throwableType,
                    happenedAtMillis = nowMillis
                )
            ),
            logLine = "step_failed id=$failedStepId blocked=$blocked details=$details"
        )
        emitEvent { snapshot ->
            BootstrapEvent.StepFailed(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = nowMillis,
                stepId = failedStepId,
                blocked = blocked,
                details = details
            )
        }
        emitEvent { snapshot ->
            BootstrapEvent.SessionFinished(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = nowMillis,
                lifecycle = snapshot.lifecycle
            )
        }
    }

    private fun startServerMonitor() {
        val currentServerProcess = serverProcess ?: return
        serverMonitorJob?.cancel()
        appendStartupLog("Server exit monitor started for the current Tavern process.")
        serverMonitorJob = scope.launch {
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
        appendStartupLog(
            "Ready watchdog started. interval=${readyWatchdogIntervalMillis}ms threshold=$readyWatchdogFailureThreshold target=$readinessUrl"
        )
        readyWatchdogJob = scope.launch {
            var consecutiveFailures = 0
            var successfulProbeCount = 0
            while (isActive) {
                delay(readyWatchdogIntervalMillis)
                if (HealthProbe.isReady(readinessUrl)) {
                    successfulProbeCount += 1
                    if (consecutiveFailures > 0) {
                        appendStartupLog(
                            "Ready watchdog probe recovered after $consecutiveFailures failure(s): $readinessUrl"
                        )
                    } else if (successfulProbeCount == 1 || successfulProbeCount % readyWatchdogSuccessLogEvery == 0) {
                        appendStartupLog("Ready watchdog probe ok (#$successfulProbeCount): $readinessUrl")
                    }
                    consecutiveFailures = 0
                    continue
                }

                consecutiveFailures += 1
                appendStartupLog(
                    "Ready watchdog probe failed ($consecutiveFailures/$readyWatchdogFailureThreshold): $readinessUrl"
                )
                emitEvent { snapshot ->
                    BootstrapEvent.ProbeFailed(
                        appSessionId = snapshot.appSessionId,
                        attemptId = snapshot.attemptId,
                        happenedAtMillis = System.currentTimeMillis(),
                        targetUrl = readinessUrl,
                        consecutiveFailures = consecutiveFailures,
                        failureThreshold = readyWatchdogFailureThreshold
                    )
                }
                if (consecutiveFailures < readyWatchdogFailureThreshold) {
                    continue
                }

                serverProcess = null
                scheduleAutoRestart(
                    message = "本地 Tavern 服务失去响应，正在自动重启。",
                    details = "连续 $readyWatchdogFailureThreshold 次探针检测失败：$localUrl",
                    localUrl = localUrl
                )
                return@launch
            }
        }
    }

    private fun stopReadyWatchdog(reason: String? = null) {
        if (readyWatchdogJob != null && !reason.isNullOrBlank()) {
            appendStartupLog("Ready watchdog stopped: $reason")
        }
        readyWatchdogJob?.cancel()
        readyWatchdogJob = null
    }

    private fun scheduleAutoRestart(
        message: String,
        details: String,
        localUrl: String = localServiceUrl()
    ) {
        stopReadyWatchdog(reason = "scheduling auto-restart")
        serverMonitorJob?.cancel()
        serverMonitorJob = null
        val attempt = autoRestartBudget.recordAttempt(System.currentTimeMillis()) ?: run {
            publishSnapshot(
                currentSnapshot.copy(
                    lifecycle = BootstrapLifecycle.FAILED_ERROR,
                    currentStepId = null,
                    statusMessage = "本地 Tavern 服务反复异常，已停止自动重启。",
                    statusDetails = details,
                    lastFailure = BootstrapFailureSnapshot(
                        stepId = currentSnapshot.lastFailure?.stepId,
                        title = "本地 Tavern 服务反复异常，已停止自动重启。",
                        details = details,
                        isBlocked = false,
                        throwableType = "AutoRestartBudgetExhausted"
                    ),
                    localUrl = localUrl,
                    restartBudget = buildRestartBudgetSnapshot()
                ),
                logLine = "lifecycle=${BootstrapLifecycle.FAILED_ERROR} details=$details"
            )
            emitEvent { snapshot ->
                BootstrapEvent.SessionFinished(
                    appSessionId = snapshot.appSessionId,
                    attemptId = snapshot.attemptId,
                    happenedAtMillis = System.currentTimeMillis(),
                    lifecycle = snapshot.lifecycle
                )
            }
            return
        }

        appendStartupLog(
            "Auto-restart scheduled (attempt $attempt/$autoRestartAttemptLimit): $message | $details"
        )
        publishSnapshot(
            currentSnapshot.copy(
                lifecycle = BootstrapLifecycle.RESTART_SCHEDULED,
                currentStepId = null,
                statusMessage = message,
                statusDetails = details,
                localUrl = localUrl,
                restartBudget = buildRestartBudgetSnapshot()
            ),
            logLine = "lifecycle=${BootstrapLifecycle.RESTART_SCHEDULED} message=$message details=$details"
        )
        emitEvent { snapshot ->
            BootstrapEvent.AutoRestartScheduled(
                appSessionId = snapshot.appSessionId,
                attemptId = snapshot.attemptId,
                happenedAtMillis = System.currentTimeMillis(),
                nextAttemptId = snapshot.attemptId + 1,
                reason = details
            )
        }

        val previousJob = bootstrapJob
        bootstrapJob = scope.launch {
            previousJob?.cancelAndJoin()
            delay(autoRestartDelayMillis)
            stopManagedProcesses()
            runBootstrapAttempt(forceRestart = true)
        }
    }

    private fun recordAutoRestartAttempt(nowMs: Long = System.currentTimeMillis()): Int? =
        autoRestartBudget.recordAttempt(nowMs)

    private fun resetAutoRestartBudget() {
        autoRestartBudget.reset()
        publishSnapshot(currentSnapshot.copy(restartBudget = buildRestartBudgetSnapshot()))
    }

    private suspend fun stopManagedProcesses() {
        serverMonitorJob?.cancel()
        serverMonitorJob = null
        stopReadyWatchdog(reason = "stopping managed Tavern processes")
        serverProcess?.stop()
        serverProcess = null
        val cleanedProcessCount = ServerProcessJanitor.cleanupLingeringServerProcesses()
        if (cleanedProcessCount > 0) {
            appendStartupLog("Cleaned $cleanedProcessCount lingering server process(es).")
        }
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

    private fun readServerLogExcerpt(maxLines: Int = 24, maxChars: Int = 1800): String {
        val serverLogFile = runtimeLogs.currentServerLogFile()
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

    private fun recordServerExitDiagnostics(details: String) {
        appendStartupLog("Server diagnostics: ${details.replace('\n', ' ').trim()}")
        Log.e(logTag, details)
    }

    private fun formatThrowable(throwable: Throwable): String {
        val stringWriter = StringWriter()
        PrintWriter(stringWriter).use { writer ->
            throwable.printStackTrace(writer)
        }
        return stringWriter.toString().trimEnd()
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

    private fun publishSnapshot(
        snapshot: BootstrapSessionSnapshot,
        logLine: String? = null
    ) {
        val previous = currentSnapshot
        val normalized = normalizeSnapshot(snapshot)
        currentSnapshot = normalized
        if (!logLine.isNullOrBlank()) {
            appendStartupLog(logLine)
        }
        BootstrapSessionRuntimeStore.update(normalized)
        onSnapshotChanged(normalized)
        syncRuntimeStatusReporting(normalized)
        if (previous.lifecycle != normalized.lifecycle) {
            emitEvent { current ->
                BootstrapEvent.LifecycleChanged(
                    appSessionId = current.appSessionId,
                    attemptId = current.attemptId,
                    happenedAtMillis = System.currentTimeMillis(),
                    lifecycle = current.lifecycle
                )
            }
        }
    }

    private fun normalizeSnapshot(snapshot: BootstrapSessionSnapshot): BootstrapSessionSnapshot {
        val currentStepKind = snapshot.currentStepId?.defaultLogKind()
        val failureStepId = snapshot.lastFailure?.stepId
        val preferredKind = when {
            snapshot.lifecycle == BootstrapLifecycle.READY_MONITORING ||
                snapshot.lifecycle == BootstrapLifecycle.RESTART_SCHEDULED -> BootstrapLogKind.TAVERN_SERVER
            currentStepKind != null -> currentStepKind
            failureStepId != null -> failureStepId.defaultLogKind()
            else -> BootstrapLogKind.STARTUP
        }
        val logTargets = snapshot.currentLogTargets.copy(
            preferredKind = preferredKind,
            currentStepKind = currentStepKind,
            currentStepLogFileName = logFileNameForKind(snapshot.currentLogTargets, currentStepKind)
        )
        val shouldReportElapsedSeconds = snapshot.shouldReportCurrentStepElapsedSeconds()
        val shouldReportTavernTail = snapshot.shouldReportTavernStartupTail()
        return snapshot.copy(
            progressPercent = calculateProgress(snapshot.steps),
            currentLogTargets = logTargets,
            currentStepElapsedSeconds = if (shouldReportElapsedSeconds) {
                snapshot.currentStepElapsedSeconds ?: 0
            } else {
                null
            },
            currentTavernServerLogLine = if (shouldReportTavernTail) {
                snapshot.currentTavernServerLogLine
            } else {
                ""
            }
        ).withDerivedUiFlags()
    }

    private fun syncRuntimeStatusReporting(snapshot: BootstrapSessionSnapshot) {
        syncCurrentStepElapsedReporting(snapshot)
        syncTavernServerTailReporting(snapshot)
    }

    private fun syncCurrentStepElapsedReporting(snapshot: BootstrapSessionSnapshot) {
        val anchorMillis = snapshot.currentStepStartedAtMillis.takeIf {
            snapshot.shouldReportCurrentStepElapsedSeconds()
        }
        if (anchorMillis == null) {
            stopCurrentStepElapsedReporting()
            return
        }
        if (currentStepElapsedRefreshJob?.isActive == true && currentStepElapsedAnchorMillis == anchorMillis) {
            publishCurrentStepElapsedSecondsIfNeeded(anchorMillis)
            return
        }

        stopCurrentStepElapsedReporting()
        currentStepElapsedAnchorMillis = anchorMillis
        publishCurrentStepElapsedSecondsIfNeeded(anchorMillis)
        currentStepElapsedRefreshJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                publishCurrentStepElapsedSecondsIfNeeded(anchorMillis)
            }
        }
    }

    private fun stopCurrentStepElapsedReporting() {
        currentStepElapsedRefreshJob?.cancel()
        currentStepElapsedRefreshJob = null
        currentStepElapsedAnchorMillis = 0L
    }

    private fun publishCurrentStepElapsedSecondsIfNeeded(anchorMillis: Long) {
        val snapshot = currentSnapshot
        if (!snapshot.shouldReportCurrentStepElapsedSeconds() || snapshot.currentStepStartedAtMillis != anchorMillis) {
            return
        }

        val elapsedSeconds = ((System.currentTimeMillis() - anchorMillis).coerceAtLeast(0L) / 1_000L).toInt()
        if (snapshot.currentStepElapsedSeconds == elapsedSeconds) {
            return
        }

        publishSnapshot(snapshot.copy(currentStepElapsedSeconds = elapsedSeconds))
    }

    private fun syncTavernServerTailReporting(snapshot: BootstrapSessionSnapshot) {
        val logFileName = snapshot.currentLogTargets.tavernServerLogFileName?.takeIf {
            snapshot.shouldReportTavernStartupTail()
        }
        if (logFileName == null) {
            stopTavernServerTailReporting()
            return
        }
        if (tavernServerTailJob?.isActive == true && observedTavernServerTailLogFileName == logFileName) {
            return
        }

        stopTavernServerTailReporting()
        observedTavernServerTailLogFileName = logFileName
        runtimeLogs.startCurrentServerTail()
        tavernServerTailJob = scope.launch {
            runtimeLogs.latestTavernServerLine.collect { line ->
                val current = currentSnapshot
                if (!current.shouldReportTavernStartupTail()) {
                    return@collect
                }
                if (current.currentLogTargets.tavernServerLogFileName != logFileName) {
                    return@collect
                }
                if (current.currentTavernServerLogLine == line) {
                    return@collect
                }

                publishSnapshot(current.copy(currentTavernServerLogLine = line))
            }
        }
    }

    private fun stopTavernServerTailReporting() {
        if (tavernServerTailJob == null && observedTavernServerTailLogFileName == null) {
            return
        }
        tavernServerTailJob?.cancel()
        tavernServerTailJob = null
        observedTavernServerTailLogFileName = null
        runtimeLogs.stopCurrentServerTail()
    }

    private fun stopRuntimeStatusReporting() {
        stopCurrentStepElapsedReporting()
        stopTavernServerTailReporting()
    }

    private fun calculateProgress(steps: List<BootstrapStepSnapshot>): Int =
        BootstrapStepLedger.calculateProgress(steps)

    private fun buildLogTargets(): BootstrapCurrentLogTargets {
        val startup = runtimeLogs.currentStartupLogFileName()
        val server = runtimeLogs.currentServerLogFileName()
        val rootfs = runtimeLogs.currentRootfsRuntimeLogFileName()
        return BootstrapCurrentLogTargets(
            startupLogFileName = startup,
            tavernServerLogFileName = server,
            rootfsRuntimeLogFileName = rootfs
        )
    }

    private fun logFileNameForKind(
        targets: BootstrapCurrentLogTargets,
        kind: BootstrapLogKind?
    ): String? {
        return when (kind) {
            BootstrapLogKind.STARTUP -> targets.startupLogFileName
            BootstrapLogKind.TAVERN_SERVER -> targets.tavernServerLogFileName
            BootstrapLogKind.ROOTFS_RUNTIME -> targets.rootfsRuntimeLogFileName
            null -> null
        }
    }

    private fun buildRestartBudgetSnapshot(): BootstrapRestartBudgetSnapshot =
        autoRestartBudget.snapshot()

    private fun hasBootstrapManifest(): Boolean {
        return File(HostPaths.from(appContext).serverDir, "bootstrap-manifest.json").isFile
    }

    private inline fun emitEvent(factory: (BootstrapSessionSnapshot) -> BootstrapEvent) {
        val event = factory(currentSnapshot)
        recordEventSummary(event)
        BootstrapSessionRuntimeStore.tryEmit(event)
    }

    private fun recordEventSummary(event: BootstrapEvent) {
        val summary = resolveEventSummary(event) ?: return
        if (currentSnapshot.lastEventSummary == summary && currentSnapshot.lastEventAtMillis == event.happenedAtMillis) {
            return
        }

        val normalized = normalizeSnapshot(
            currentSnapshot.copy(
                lastEventSummary = summary,
                lastEventAtMillis = event.happenedAtMillis
            )
        )
        currentSnapshot = normalized
        BootstrapSessionRuntimeStore.update(normalized)
        onSnapshotChanged(normalized)
    }

    private fun resolveEventSummary(event: BootstrapEvent): String? {
        return when (event) {
            is BootstrapEvent.SessionStarted -> if (event.forceRestart) {
                "开始第 ${event.attemptId} 次启动尝试，来源：强制重启。"
            } else {
                "开始第 ${event.attemptId} 次启动尝试。"
            }

            is BootstrapEvent.StepStarted -> "开始执行：${resolveStepTitle(event.stepId)}。"
            is BootstrapEvent.StepCompleted -> currentSnapshot.findStep(event.stepId)
                ?.details
                ?.takeIf { details -> details.isNotBlank() }
                ?: "${resolveStepTitle(event.stepId)}已完成。"

            is BootstrapEvent.StepSkipped -> currentSnapshot.findStep(event.stepId)
                ?.details
                ?.takeIf { details -> details.isNotBlank() }
                ?: "已跳过：${resolveStepTitle(event.stepId)}（${event.result.displayLabel()}）。"

            is BootstrapEvent.StepFailed -> event.details.ifBlank {
                event.stepId?.let(::resolveStepTitle)?.let { stepTitle ->
                    "步骤失败：$stepTitle。"
                } ?: "本地 Tavern 服务启动失败。"
            }

            is BootstrapEvent.LifecycleChanged -> when (event.lifecycle) {
                BootstrapLifecycle.READY_MONITORING,
                BootstrapLifecycle.PAUSING_FOR_SETTINGS,
                BootstrapLifecycle.CONFIGURING,
                BootstrapLifecycle.RESTART_SCHEDULED,
                BootstrapLifecycle.FAILED_BLOCKED,
                BootstrapLifecycle.FAILED_ERROR,
                BootstrapLifecycle.STOPPED -> {
                    currentSnapshot.statusDetails.ifBlank { currentSnapshot.statusMessage }
                        .ifBlank { event.lifecycle.displayLabel() }
                }

                BootstrapLifecycle.IDLE,
                BootstrapLifecycle.RUNNING -> null
            }

            is BootstrapEvent.ProbeAttempted -> null
            is BootstrapEvent.StepHeartbeat -> null
            is BootstrapEvent.ProbeFailed -> {
                "就绪探针失败 ${event.consecutiveFailures}/${event.failureThreshold}：${event.targetUrl}"
            }

            is BootstrapEvent.AutoRestartScheduled -> event.reason.ifBlank {
                "本地 Tavern 服务正在准备自动重启。"
            }

            is BootstrapEvent.SettingsPauseRequested -> "已请求暂停本地 Tavern 服务，准备进入设置。"
            is BootstrapEvent.SessionFinished -> null
        }
    }

    private fun resolveStepTitle(stepId: BootstrapStepId): String {
        return currentSnapshot.findStep(stepId)?.title
            ?: defaultBootstrapSteps().firstOrNull { step -> step.id == stepId }?.title
            ?: stepId.name
    }
}
