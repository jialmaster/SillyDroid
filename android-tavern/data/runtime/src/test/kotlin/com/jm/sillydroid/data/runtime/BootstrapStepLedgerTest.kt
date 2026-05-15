package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.core.model.bootstrap.BootstrapStepDetection
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepResult
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepStatus
import com.jm.sillydroid.core.model.bootstrap.defaultBootstrapSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：[BootstrapStepLedger] 的状态迁移与进度计算。
 */
class BootstrapStepLedgerTest {

    private fun fresh() = defaultBootstrapSteps()

    private fun stepOf(steps: List<com.jm.sillydroid.core.model.bootstrap.BootstrapStepSnapshot>, id: BootstrapStepId) =
        steps.first { it.id == id }

    // ---------- weights & progress ----------

    @Test
    fun `step weights cover all 9 step ids exactly once`() {
        val ids = BootstrapStepId.values().toSet()
        assertEquals(ids, BootstrapStepLedger.stepWeights.keys)
        assertEquals(9, BootstrapStepLedger.stepWeights.size)
    }

    @Test
    fun `weights sum to 100 so total budget is comprehensible`() {
        // 不强制要求 == 100，但当前确实是 100；若调整需更新此断言以避免意外漂移。
        assertEquals(100, BootstrapStepLedger.stepWeights.values.sum())
    }

    @Test
    fun `calculateProgress on all-pending returns 0`() {
        assertEquals(0, BootstrapStepLedger.calculateProgress(fresh()))
    }

    @Test
    fun `calculateProgress on all-completed returns 100`() {
        val all = fresh().map { it.copy(status = BootstrapStepStatus.COMPLETED, progressPercent = 100) }
        assertEquals(100, BootstrapStepLedger.calculateProgress(all))
    }

    @Test
    fun `calculateProgress treats SKIPPED as full credit`() {
        val all = fresh().map { it.copy(status = BootstrapStepStatus.SKIPPED, progressPercent = 100) }
        assertEquals(100, BootstrapStepLedger.calculateProgress(all))
    }

    @Test
    fun `calculateProgress weights running step by its progressPercent`() {
        // 仅 PREPARE_ROOTFS_ASSETS（权重 28）跑到 50%，其余 PENDING → 期望 14%
        val steps = fresh().map { step ->
            if (step.id == BootstrapStepId.PREPARE_ROOTFS_ASSETS) {
                step.copy(status = BootstrapStepStatus.RUNNING, progressPercent = 50)
            } else {
                step
            }
        }
        assertEquals(14, BootstrapStepLedger.calculateProgress(steps))
    }

    @Test
    fun `calculateProgress clamps to 100`() {
        // 即使某步的 progressPercent 被外部传入 200，也应被截断
        val steps = fresh().map { it.copy(status = BootstrapStepStatus.COMPLETED, progressPercent = 200) }
        assertEquals(100, BootstrapStepLedger.calculateProgress(steps))
    }

    // ---------- startRunning ----------

    @Test
    fun `startRunning marks target step RUNNING and resets progress`() {
        val updated = BootstrapStepLedger.startRunning(
            steps = fresh(),
            stepId = BootstrapStepId.PREPARE_WORKDIRS,
            detection = BootstrapStepDetection.REQUIRED,
            statusDetails = "creating dirs",
            nowMillis = 5_000L
        )
        val target = stepOf(updated, BootstrapStepId.PREPARE_WORKDIRS)
        assertEquals(BootstrapStepStatus.RUNNING, target.status)
        assertEquals(BootstrapStepDetection.REQUIRED, target.detection)
        assertEquals(BootstrapStepResult.NONE, target.result)
        assertEquals(0, target.progressPercent)
        assertEquals("creating dirs", target.details)
        assertEquals(5_000L, target.startedAtMillis)
        assertEquals(0L, target.finishedAtMillis)
    }

    @Test
    fun `startRunning leaves other steps untouched`() {
        val before = fresh()
        val after = BootstrapStepLedger.startRunning(
            steps = before,
            stepId = BootstrapStepId.PREPARE_WORKDIRS,
            detection = BootstrapStepDetection.NONE,
            statusDetails = "x",
            nowMillis = 1L
        )
        for (id in BootstrapStepId.values()) {
            if (id == BootstrapStepId.PREPARE_WORKDIRS) continue
            assertEquals(stepOf(before, id), stepOf(after, id))
        }
    }

    // ---------- heartbeat ----------

    @Test
    fun `heartbeat updates only RUNNING target step`() {
        val running = BootstrapStepLedger.startRunning(
            steps = fresh(),
            stepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
            detection = BootstrapStepDetection.REQUIRED,
            statusDetails = "init",
            nowMillis = 0L
        )
        val beat = BootstrapStepLedger.heartbeat(
            steps = running,
            stepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
            details = "extracting...",
            progressPercent = 42
        )
        val s = stepOf(beat, BootstrapStepId.PREPARE_ROOTFS_ASSETS)
        assertEquals(42, s.progressPercent)
        assertEquals("extracting...", s.details)
    }

    @Test
    fun `heartbeat clamps progress to 0_99`() {
        val running = BootstrapStepLedger.startRunning(
            steps = fresh(),
            stepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
            detection = BootstrapStepDetection.REQUIRED,
            statusDetails = "",
            nowMillis = 0L
        )
        val high = BootstrapStepLedger.heartbeat(running, BootstrapStepId.PREPARE_ROOTFS_ASSETS, "x", 250)
        assertEquals(99, stepOf(high, BootstrapStepId.PREPARE_ROOTFS_ASSETS).progressPercent)
        val low = BootstrapStepLedger.heartbeat(running, BootstrapStepId.PREPARE_ROOTFS_ASSETS, "x", -10)
        assertEquals(0, stepOf(low, BootstrapStepId.PREPARE_ROOTFS_ASSETS).progressPercent)
    }

    @Test
    fun `heartbeat ignores non-RUNNING steps`() {
        // 默认全 PENDING，heartbeat 不应改任何东西
        val before = fresh()
        val after = BootstrapStepLedger.heartbeat(before, BootstrapStepId.PREPARE_ROOTFS_ASSETS, "x", 50)
        assertEquals(before, after)
    }

    // ---------- completed ----------

    @Test
    fun `completed sets COMPLETED and progress to 100 and finishedAt`() {
        val updated = BootstrapStepLedger.completed(
            steps = fresh(),
            stepId = BootstrapStepId.START_SERVER_PROCESS,
            detection = BootstrapStepDetection.REQUIRED,
            result = BootstrapStepResult.SUCCESS,
            details = "started",
            nowMillis = 9_999L
        )
        val s = stepOf(updated, BootstrapStepId.START_SERVER_PROCESS)
        assertEquals(BootstrapStepStatus.COMPLETED, s.status)
        assertEquals(100, s.progressPercent)
        assertEquals(BootstrapStepResult.SUCCESS, s.result)
        assertEquals(9_999L, s.finishedAtMillis)
    }

    // ---------- skipped ----------

    @Test
    fun `skipped sets SKIPPED with both startedAt and finishedAt to nowMillis`() {
        val updated = BootstrapStepLedger.skipped(
            steps = fresh(),
            stepId = BootstrapStepId.DETECT_EXISTING_SERVER,
            detection = BootstrapStepDetection.REUSED_RUNNING_SERVER,
            result = BootstrapStepResult.SKIPPED_REUSED,
            details = "server already running",
            nowMillis = 7L
        )
        val s = stepOf(updated, BootstrapStepId.DETECT_EXISTING_SERVER)
        assertEquals(BootstrapStepStatus.SKIPPED, s.status)
        assertEquals(100, s.progressPercent)
        assertEquals(7L, s.startedAtMillis)
        assertEquals(7L, s.finishedAtMillis)
    }

    // ---------- skipRemainingPending ----------

    @Test
    fun `skipRemainingPending only flips PENDING steps`() {
        // 让前两个完成，第三个 RUNNING，剩下 PENDING
        var steps = fresh()
        steps = BootstrapStepLedger.completed(steps, BootstrapStepId.DETECT_EXISTING_SERVER,
            BootstrapStepDetection.NONE, BootstrapStepResult.SUCCESS, "", 1L)
        steps = BootstrapStepLedger.completed(steps, BootstrapStepId.PREPARE_LOG_SESSION,
            BootstrapStepDetection.NONE, BootstrapStepResult.SUCCESS, "", 2L)
        steps = BootstrapStepLedger.startRunning(steps, BootstrapStepId.PREPARE_WORKDIRS,
            BootstrapStepDetection.NONE, "", 3L)

        val after = BootstrapStepLedger.skipRemainingPending(
            steps = steps,
            detection = BootstrapStepDetection.UP_TO_DATE,
            result = BootstrapStepResult.SKIPPED_UP_TO_DATE,
            details = "reused",
            nowMillis = 100L
        )
        // 完成的两步保持 COMPLETED
        assertEquals(BootstrapStepStatus.COMPLETED, stepOf(after, BootstrapStepId.DETECT_EXISTING_SERVER).status)
        assertEquals(BootstrapStepStatus.COMPLETED, stepOf(after, BootstrapStepId.PREPARE_LOG_SESSION).status)
        // RUNNING 的不被跳过
        assertEquals(BootstrapStepStatus.RUNNING, stepOf(after, BootstrapStepId.PREPARE_WORKDIRS).status)
        // 余下 PENDING 全部 → SKIPPED
        val skippedIds = setOf(
            BootstrapStepId.PREPARE_ROOTFS_ASSETS,
            BootstrapStepId.PREPARE_SERVER_ASSETS,
            BootstrapStepId.VALIDATE_RUNTIME_LAYOUT,
            BootstrapStepId.ENSURE_ROOTFS_RUNTIME,
            BootstrapStepId.START_SERVER_PROCESS,
            BootstrapStepId.WAIT_HTTP_READY
        )
        for (id in skippedIds) {
            val s = stepOf(after, id)
            assertEquals("step $id should be SKIPPED", BootstrapStepStatus.SKIPPED, s.status)
            assertEquals(BootstrapStepResult.SKIPPED_UP_TO_DATE, s.result)
            assertEquals(100, s.progressPercent)
            assertEquals(100L, s.startedAtMillis)
            assertEquals(100L, s.finishedAtMillis)
        }
    }

    // ---------- failed ----------

    @Test
    fun `failed marks FAILED with FAILED_BLOCKED when blocked is true`() {
        val running = BootstrapStepLedger.startRunning(
            steps = fresh(),
            stepId = BootstrapStepId.WAIT_HTTP_READY,
            detection = BootstrapStepDetection.REQUIRED,
            statusDetails = "",
            nowMillis = 0L
        )
        val updated = BootstrapStepLedger.failed(
            steps = running,
            stepId = BootstrapStepId.WAIT_HTTP_READY,
            blocked = true,
            details = "timeout",
            nowMillis = 33L
        )
        val s = stepOf(updated, BootstrapStepId.WAIT_HTTP_READY)
        assertEquals(BootstrapStepStatus.FAILED, s.status)
        assertEquals(BootstrapStepResult.FAILED_BLOCKED, s.result)
        assertEquals("timeout", s.details)
        assertEquals(33L, s.finishedAtMillis)
    }

    @Test
    fun `failed without blocked uses FAILED_ERROR`() {
        val updated = BootstrapStepLedger.failed(
            steps = fresh(),
            stepId = BootstrapStepId.START_SERVER_PROCESS,
            blocked = false,
            details = "boom",
            nowMillis = 1L
        )
        assertEquals(BootstrapStepResult.FAILED_ERROR,
            stepOf(updated, BootstrapStepId.START_SERVER_PROCESS).result)
    }

    @Test
    fun `failed clamps progressPercent to 0_99`() {
        // 制造一个 progressPercent 越界的 RUNNING step（防御性）
        val custom = fresh().map { step ->
            if (step.id == BootstrapStepId.PREPARE_ROOTFS_ASSETS) {
                step.copy(status = BootstrapStepStatus.RUNNING, progressPercent = 250)
            } else step
        }
        val updated = BootstrapStepLedger.failed(custom, BootstrapStepId.PREPARE_ROOTFS_ASSETS, false, "x", 0L)
        val s = stepOf(updated, BootstrapStepId.PREPARE_ROOTFS_ASSETS)
        assertTrue("progress must be in 0..99 after failed; got ${s.progressPercent}",
            s.progressPercent in 0..99)
        assertEquals(99, s.progressPercent)
    }

    @Test
    fun `failed with null stepId no-ops`() {
        val before = fresh()
        val after = BootstrapStepLedger.failed(before, stepId = null, blocked = false, details = "x", nowMillis = 1L)
        assertEquals(before, after)
    }
}
