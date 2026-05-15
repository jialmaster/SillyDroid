package com.jm.sillydroid.core.model.bootstrap

enum class BootstrapLifecycle {
    IDLE,
    RUNNING,
    READY_MONITORING,
    PAUSING_FOR_SETTINGS,
    CONFIGURING,
    RESTART_SCHEDULED,
    FAILED_BLOCKED,
    FAILED_ERROR,
    STOPPED
}

enum class BootstrapStepId {
    DETECT_EXISTING_SERVER,
    PREPARE_LOG_SESSION,
    PREPARE_WORKDIRS,
    PREPARE_ROOTFS_ASSETS,
    PREPARE_SERVER_ASSETS,
    VALIDATE_RUNTIME_LAYOUT,
    ENSURE_ROOTFS_RUNTIME,
    START_SERVER_PROCESS,
    WAIT_HTTP_READY
}

enum class BootstrapStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED
}

enum class BootstrapStepDetection {
    NONE,
    REUSED_RUNNING_SERVER,
    EXISTING_LOG_SESSION,
    REQUIRED,
    UP_TO_DATE,
    MISSING,
    INCOMPLETE,
    OUTDATED
}

enum class BootstrapStepResult {
    NONE,
    SUCCESS,
    SKIPPED_REUSED,
    SKIPPED_UP_TO_DATE,
    FAILED_BLOCKED,
    FAILED_ERROR
}

enum class BootstrapLogKind {
    STARTUP,
    TAVERN_SERVER,
    ROOTFS_RUNTIME
}

data class BootstrapStepSnapshot(
    val id: BootstrapStepId,
    val title: String,
    val status: BootstrapStepStatus = BootstrapStepStatus.PENDING,
    val detection: BootstrapStepDetection = BootstrapStepDetection.NONE,
    val result: BootstrapStepResult = BootstrapStepResult.NONE,
    val progressPercent: Int = 0,
    val details: String = "",
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L,
    val logFileName: String? = null
)

data class BootstrapRestartBudgetSnapshot(
    val attemptCount: Int = 0,
    val attemptLimit: Int = 3,
    val windowStartedAtMillis: Long = 0L,
    val windowMillis: Long = 5 * 60_000L
)

data class BootstrapFailureSnapshot(
    val stepId: BootstrapStepId? = null,
    val title: String,
    val details: String,
    val isBlocked: Boolean,
    val throwableType: String? = null,
    /**
     * 结构化错误分类标签，对应 `BootstrapError` 子类的 simpleName
     * （例如 `ArchiveCorrupted`、`ServerNotReady`）。
     * 历史/未分类错误为 `null`，UI 应回退到 [title] / [details] 文案。
     */
    val errorKind: String? = null,
    val happenedAtMillis: Long = System.currentTimeMillis()
)

data class BootstrapCurrentLogTargets(
    val preferredKind: BootstrapLogKind = BootstrapLogKind.STARTUP,
    val currentStepKind: BootstrapLogKind? = null,
    val currentStepLogFileName: String? = null,
    val startupLogFileName: String? = null,
    val tavernServerLogFileName: String? = null,
    val rootfsRuntimeLogFileName: String? = null
)

data class BootstrapDerivedUiFlags(
    val showWebView: Boolean = false,
    val showBootstrapOverlay: Boolean = true,
    val showProgress: Boolean = false,
    val canRetry: Boolean = false,
    val canOpenSettings: Boolean = false,
    val preferTavernServerLog: Boolean = false,
    val showWaitingTimer: Boolean = false,
    val showTavernStartupTail: Boolean = false
)

data class BootstrapSessionSnapshot(
    val appSessionId: String = "",
    val attemptId: Int = 0,
    val lifecycle: BootstrapLifecycle = BootstrapLifecycle.IDLE,
    val currentStepId: BootstrapStepId? = null,
    val steps: List<BootstrapStepSnapshot> = defaultBootstrapSteps(),
    val localUrl: String = defaultBootstrapLocalServiceUrl,
    val progressPercent: Int = 0,
    val statusMessage: String = "正在准备 SillyDroid 宿主环境。",
    val statusDetails: String = "",
    val currentStepElapsedSeconds: Int? = null,
    val currentTavernServerLogLine: String = "",
    val restartBudget: BootstrapRestartBudgetSnapshot = BootstrapRestartBudgetSnapshot(),
    val currentLogTargets: BootstrapCurrentLogTargets = BootstrapCurrentLogTargets(),
    val derivedUiFlags: BootstrapDerivedUiFlags = BootstrapDerivedUiFlags(),
    val lastFailure: BootstrapFailureSnapshot? = null,
    val lastEventSummary: String = "",
    val lastEventAtMillis: Long = 0L,
    val bootstrapPreviouslyCompleted: Boolean = false
) {
    val isReady: Boolean
        get() = lifecycle == BootstrapLifecycle.READY_MONITORING

    val canRetry: Boolean
        get() = lifecycle == BootstrapLifecycle.FAILED_BLOCKED || lifecycle == BootstrapLifecycle.FAILED_ERROR

    val currentStep: BootstrapStepSnapshot?
        get() = currentStepId?.let(::findStep)

    val currentStepStartedAtMillis: Long
        get() = currentStep?.startedAtMillis ?: 0L

    fun findStep(stepId: BootstrapStepId): BootstrapStepSnapshot? {
        return steps.firstOrNull { step -> step.id == stepId }
    }
}

sealed interface BootstrapEvent {
    val appSessionId: String
    val attemptId: Int
    val happenedAtMillis: Long

    data class SessionStarted(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val forceRestart: Boolean
    ) : BootstrapEvent

    data class StepStarted(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val stepId: BootstrapStepId
    ) : BootstrapEvent

    data class StepHeartbeat(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val stepId: BootstrapStepId,
        val details: String,
        val progressPercent: Int
    ) : BootstrapEvent

    data class StepCompleted(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val stepId: BootstrapStepId
    ) : BootstrapEvent

    data class StepSkipped(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val stepId: BootstrapStepId,
        val result: BootstrapStepResult
    ) : BootstrapEvent

    data class StepFailed(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val stepId: BootstrapStepId?,
        val blocked: Boolean,
        val details: String
    ) : BootstrapEvent

    data class LifecycleChanged(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val lifecycle: BootstrapLifecycle
    ) : BootstrapEvent

    data class ProbeAttempted(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val stepId: BootstrapStepId,
        val attempt: Int,
        val totalAttempts: Int,
        val targetUrl: String
    ) : BootstrapEvent

    data class ProbeFailed(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val targetUrl: String,
        val consecutiveFailures: Int,
        val failureThreshold: Int
    ) : BootstrapEvent

    data class AutoRestartScheduled(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val nextAttemptId: Int,
        val reason: String
    ) : BootstrapEvent

    data class SettingsPauseRequested(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long
    ) : BootstrapEvent

    data class SessionFinished(
        override val appSessionId: String,
        override val attemptId: Int,
        override val happenedAtMillis: Long,
        val lifecycle: BootstrapLifecycle
    ) : BootstrapEvent
}

const val defaultBootstrapServicePort: Int = 8000
const val defaultBootstrapLocalServiceUrl: String = "http://127.0.0.1:8000"

fun BootstrapSessionSnapshot.shouldPreferTavernServerLog(): Boolean {
    return currentLogTargets.preferredKind == BootstrapLogKind.TAVERN_SERVER
}

fun BootstrapSessionSnapshot.shouldReportCurrentStepElapsedSeconds(): Boolean {
    return lifecycle == BootstrapLifecycle.RUNNING &&
        currentStep?.status == BootstrapStepStatus.RUNNING && (
        currentStepId == BootstrapStepId.ENSURE_ROOTFS_RUNTIME ||
            currentStepId == BootstrapStepId.START_SERVER_PROCESS ||
            currentStepId == BootstrapStepId.WAIT_HTTP_READY
        )
}

fun BootstrapSessionSnapshot.shouldReportTavernStartupTail(): Boolean {
    return lifecycle == BootstrapLifecycle.RUNNING &&
        currentStep?.status == BootstrapStepStatus.RUNNING &&
        currentLogTargets.currentStepKind == BootstrapLogKind.TAVERN_SERVER
}

fun BootstrapSessionSnapshot.latestResolvedStep(): BootstrapStepSnapshot? {
    return steps
        .filter { step ->
            step.status == BootstrapStepStatus.COMPLETED ||
                step.status == BootstrapStepStatus.SKIPPED ||
                step.status == BootstrapStepStatus.FAILED
        }
        .maxByOrNull { step ->
            maxOf(step.finishedAtMillis, step.startedAtMillis)
        }
}

fun BootstrapSessionSnapshot.currentStepDisplayText(): String {
    val step = currentStep ?: return "无"
    return "${step.title} · ${step.status.displayLabel()}"
}

fun BootstrapSessionSnapshot.latestResultDisplayText(): String {
    val step = latestResolvedStep() ?: return "无"
    return "${step.title} · ${step.result.displayLabel()}"
}

fun BootstrapSessionSnapshot.eventReasonDisplayText(): String {
    val runningStepDetails = currentStep
        ?.takeIf { step -> step.status == BootstrapStepStatus.RUNNING }
        ?.details
        .orEmpty()
        .trim()
    val failureDetails = lastFailure?.details.orEmpty().trim()
    return when {
        runningStepDetails.isNotBlank() -> runningStepDetails
        failureDetails.isNotBlank() -> failureDetails
        lastEventSummary.isNotBlank() -> lastEventSummary
        statusDetails.isNotBlank() -> statusDetails
        statusMessage.isNotBlank() -> statusMessage
        else -> "无"
    }
}

fun BootstrapSessionSnapshot.buildStatusSummaryText(): String {
    return buildString {
        append("当前状态：")
        append(lifecycle.displayLabel())
        append('\n')
        append("当前步骤：")
        append(currentStepDisplayText())
        append('\n')
        append("步骤结果：")
        append(latestResultDisplayText())
        append('\n')
        append("事件原因：")
        append(eventReasonDisplayText())
    }
}

fun BootstrapLifecycle.displayLabel(): String {
    return when (this) {
        BootstrapLifecycle.IDLE -> "空闲"
        BootstrapLifecycle.RUNNING -> "启动中"
        BootstrapLifecycle.READY_MONITORING -> "就绪监控中"
        BootstrapLifecycle.PAUSING_FOR_SETTINGS -> "正在暂停"
        BootstrapLifecycle.CONFIGURING -> "配置中"
        BootstrapLifecycle.RESTART_SCHEDULED -> "等待自动重启"
        BootstrapLifecycle.FAILED_BLOCKED -> "启动阻塞"
        BootstrapLifecycle.FAILED_ERROR -> "启动失败"
        BootstrapLifecycle.STOPPED -> "已停止"
    }
}

fun BootstrapStepStatus.displayLabel(): String {
    return when (this) {
        BootstrapStepStatus.PENDING -> "待执行"
        BootstrapStepStatus.RUNNING -> "执行中"
        BootstrapStepStatus.COMPLETED -> "已完成"
        BootstrapStepStatus.SKIPPED -> "已跳过"
        BootstrapStepStatus.FAILED -> "失败"
    }
}

fun BootstrapStepResult.displayLabel(): String {
    return when (this) {
        BootstrapStepResult.NONE -> "无结果"
        BootstrapStepResult.SUCCESS -> "成功"
        BootstrapStepResult.SKIPPED_REUSED -> "已复用现有服务"
        BootstrapStepResult.SKIPPED_UP_TO_DATE -> "已是最新"
        BootstrapStepResult.FAILED_BLOCKED -> "阻塞失败"
        BootstrapStepResult.FAILED_ERROR -> "异常失败"
    }
}

fun defaultBootstrapSteps(): List<BootstrapStepSnapshot> {
    return listOf(
        BootstrapStepSnapshot(BootstrapStepId.DETECT_EXISTING_SERVER, "检测现有本地服务"),
        BootstrapStepSnapshot(BootstrapStepId.PREPARE_LOG_SESSION, "准备日志会话"),
        BootstrapStepSnapshot(BootstrapStepId.PREPARE_WORKDIRS, "准备宿主工作目录"),
        BootstrapStepSnapshot(BootstrapStepId.PREPARE_ROOTFS_ASSETS, "准备 Termux 与 rootfs 资产"),
        BootstrapStepSnapshot(BootstrapStepId.PREPARE_SERVER_ASSETS, "准备 Tavern 资产"),
        BootstrapStepSnapshot(BootstrapStepId.VALIDATE_RUNTIME_LAYOUT, "校验运行时布局"),
        BootstrapStepSnapshot(BootstrapStepId.ENSURE_ROOTFS_RUNTIME, "初始化离线 Linux 运行时"),
        BootstrapStepSnapshot(BootstrapStepId.START_SERVER_PROCESS, "启动 Tavern 进程"),
        BootstrapStepSnapshot(BootstrapStepId.WAIT_HTTP_READY, "等待 HTTP 服务就绪")
    )
}

fun BootstrapStepId.defaultLogKind(): BootstrapLogKind {
    return when (this) {
        BootstrapStepId.ENSURE_ROOTFS_RUNTIME -> BootstrapLogKind.ROOTFS_RUNTIME
        BootstrapStepId.START_SERVER_PROCESS,
        BootstrapStepId.WAIT_HTTP_READY -> BootstrapLogKind.TAVERN_SERVER
        else -> BootstrapLogKind.STARTUP
    }
}

fun BootstrapSessionSnapshot.withDerivedUiFlags(): BootstrapSessionSnapshot {
    val showWebView = lifecycle == BootstrapLifecycle.READY_MONITORING ||
        lifecycle == BootstrapLifecycle.RESTART_SCHEDULED
    val currentStepId = currentStepId
    val showProgress = lifecycle == BootstrapLifecycle.RUNNING ||
        lifecycle == BootstrapLifecycle.RESTART_SCHEDULED ||
        lifecycle == BootstrapLifecycle.PAUSING_FOR_SETTINGS
    val canOpenSettings = when (lifecycle) {
        BootstrapLifecycle.READY_MONITORING,
        BootstrapLifecycle.PAUSING_FOR_SETTINGS,
        BootstrapLifecycle.CONFIGURING,
        BootstrapLifecycle.FAILED_BLOCKED,
        BootstrapLifecycle.FAILED_ERROR -> true
        BootstrapLifecycle.RUNNING,
        BootstrapLifecycle.RESTART_SCHEDULED -> bootstrapPreviouslyCompleted &&
            currentStepId != BootstrapStepId.PREPARE_ROOTFS_ASSETS &&
            currentStepId != BootstrapStepId.PREPARE_SERVER_ASSETS
        else -> false
    }

    return copy(
        derivedUiFlags = BootstrapDerivedUiFlags(
            showWebView = showWebView,
            showBootstrapOverlay = !showWebView,
            showProgress = showProgress,
            canRetry = canRetry || lifecycle == BootstrapLifecycle.CONFIGURING,
            canOpenSettings = canOpenSettings,
            preferTavernServerLog = shouldPreferTavernServerLog(),
            showWaitingTimer = shouldReportCurrentStepElapsedSeconds(),
            showTavernStartupTail = shouldReportTavernStartupTail()
        )
    )
}
