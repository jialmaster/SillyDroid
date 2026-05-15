package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.core.model.bootstrap.BootstrapStepDetection
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepResult
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepStatus

/**
 * 纯函数式的 bootstrap 步骤状态机。原本散落在 [BootstrapSessionManager] 内的
 * `steps.map { ... }` 变换被搬到这里，使得：
 *   - 每种状态迁移可以独立做 JVM 单测；
 *   - 上层 manager 只剩下 "拼装 snapshot + publish + emit event" 的薄壳；
 *   - 步骤权重 / 进度计算逻辑集中在一处。
 *
 * 所有方法都接收 immutable `List<BootstrapStepSnapshot>` 并返回新的 list；
 * 不持有任何可变状态、不依赖 Android / coroutines。
 */
internal object BootstrapStepLedger {

    /** 各步骤在整体进度条中的权重；总和决定 100% 进度的归一化分母。 */
    val stepWeights: Map<BootstrapStepId, Int> = linkedMapOf(
        BootstrapStepId.DETECT_EXISTING_SERVER to 2,
        BootstrapStepId.PREPARE_LOG_SESSION to 3,
        BootstrapStepId.PREPARE_WORKDIRS to 3,
        BootstrapStepId.PREPARE_ROOTFS_ASSETS to 28,
        BootstrapStepId.PREPARE_SERVER_ASSETS to 32,
        BootstrapStepId.VALIDATE_RUNTIME_LAYOUT to 6,
        BootstrapStepId.ENSURE_ROOTFS_RUNTIME to 10,
        BootstrapStepId.START_SERVER_PROCESS to 6,
        BootstrapStepId.WAIT_HTTP_READY to 10
    )

    fun startRunning(
        steps: List<BootstrapStepSnapshot>,
        stepId: BootstrapStepId,
        detection: BootstrapStepDetection,
        statusDetails: String,
        nowMillis: Long
    ): List<BootstrapStepSnapshot> = steps.map { step ->
        if (step.id == stepId) {
            step.copy(
                status = BootstrapStepStatus.RUNNING,
                detection = detection,
                result = BootstrapStepResult.NONE,
                progressPercent = 0,
                details = statusDetails,
                startedAtMillis = nowMillis,
                finishedAtMillis = 0L
            )
        } else {
            step
        }
    }

    fun heartbeat(
        steps: List<BootstrapStepSnapshot>,
        stepId: BootstrapStepId,
        details: String,
        progressPercent: Int
    ): List<BootstrapStepSnapshot> {
        val normalizedProgress = progressPercent.coerceIn(0, 99)
        return steps.map { step ->
            if (step.id == stepId && step.status == BootstrapStepStatus.RUNNING) {
                step.copy(progressPercent = normalizedProgress, details = details)
            } else {
                step
            }
        }
    }

    fun completed(
        steps: List<BootstrapStepSnapshot>,
        stepId: BootstrapStepId,
        detection: BootstrapStepDetection,
        result: BootstrapStepResult,
        details: String,
        nowMillis: Long
    ): List<BootstrapStepSnapshot> = steps.map { step ->
        if (step.id == stepId) {
            step.copy(
                status = BootstrapStepStatus.COMPLETED,
                detection = detection,
                result = result,
                progressPercent = 100,
                details = details,
                finishedAtMillis = nowMillis
            )
        } else {
            step
        }
    }

    fun skipped(
        steps: List<BootstrapStepSnapshot>,
        stepId: BootstrapStepId,
        detection: BootstrapStepDetection,
        result: BootstrapStepResult,
        details: String,
        nowMillis: Long
    ): List<BootstrapStepSnapshot> = steps.map { step ->
        if (step.id == stepId) {
            step.copy(
                status = BootstrapStepStatus.SKIPPED,
                detection = detection,
                result = result,
                progressPercent = 100,
                details = details,
                startedAtMillis = nowMillis,
                finishedAtMillis = nowMillis
            )
        } else {
            step
        }
    }

    fun skipRemainingPending(
        steps: List<BootstrapStepSnapshot>,
        detection: BootstrapStepDetection,
        result: BootstrapStepResult,
        details: String,
        nowMillis: Long
    ): List<BootstrapStepSnapshot> = steps.map { step ->
        if (step.status == BootstrapStepStatus.PENDING) {
            step.copy(
                status = BootstrapStepStatus.SKIPPED,
                detection = detection,
                result = result,
                progressPercent = 100,
                details = details,
                startedAtMillis = nowMillis,
                finishedAtMillis = nowMillis
            )
        } else {
            step
        }
    }

    fun failed(
        steps: List<BootstrapStepSnapshot>,
        stepId: BootstrapStepId?,
        blocked: Boolean,
        details: String,
        nowMillis: Long
    ): List<BootstrapStepSnapshot> = steps.map { step ->
        if (step.id == stepId) {
            step.copy(
                status = BootstrapStepStatus.FAILED,
                result = if (blocked) BootstrapStepResult.FAILED_BLOCKED else BootstrapStepResult.FAILED_ERROR,
                progressPercent = step.progressPercent.coerceIn(0, 99),
                details = details,
                finishedAtMillis = nowMillis
            )
        } else {
            step
        }
    }

    fun calculateProgress(steps: List<BootstrapStepSnapshot>): Int {
        val totalWeight = stepWeights.values.sum().coerceAtLeast(1)
        var accumulated = 0.0
        for (step in steps) {
            val weight = stepWeights.getValue(step.id)
            accumulated += when (step.status) {
                BootstrapStepStatus.COMPLETED,
                BootstrapStepStatus.SKIPPED -> weight.toDouble()
                BootstrapStepStatus.RUNNING,
                BootstrapStepStatus.FAILED -> weight * (step.progressPercent.coerceIn(0, 100) / 100.0)
                BootstrapStepStatus.PENDING -> 0.0
            }
        }
        return ((accumulated / totalWeight.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }
}
