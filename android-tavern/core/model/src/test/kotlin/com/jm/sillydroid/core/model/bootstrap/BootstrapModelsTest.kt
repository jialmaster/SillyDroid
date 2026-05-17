package com.jm.sillydroid.core.model.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapModelsTest {

    @Test
    fun `defaultBootstrapSteps returns 9 steps in canonical order`() {
        val steps = defaultBootstrapSteps()
        assertEquals(9, steps.size)
        val expectedOrder = listOf(
            BootstrapStepId.DETECT_EXISTING_SERVER,
            BootstrapStepId.PREPARE_LOG_SESSION,
            BootstrapStepId.PREPARE_WORKDIRS,
            BootstrapStepId.PREPARE_ROOTFS_ASSETS,
            BootstrapStepId.PREPARE_SERVER_ASSETS,
            BootstrapStepId.VALIDATE_RUNTIME_LAYOUT,
            BootstrapStepId.ENSURE_ROOTFS_RUNTIME,
            BootstrapStepId.START_SERVER_PROCESS,
            BootstrapStepId.WAIT_HTTP_READY
        )
        assertEquals(expectedOrder, steps.map { it.id })
        assertTrue(steps.all { it.status == BootstrapStepStatus.PENDING })
    }

    @Test
    fun `defaultLogKind maps step to correct log kind`() {
        assertEquals(BootstrapLogKind.ROOTFS_RUNTIME, BootstrapStepId.ENSURE_ROOTFS_RUNTIME.defaultLogKind())
        assertEquals(BootstrapLogKind.TAVERN_SERVER, BootstrapStepId.START_SERVER_PROCESS.defaultLogKind())
        assertEquals(BootstrapLogKind.TAVERN_SERVER, BootstrapStepId.WAIT_HTTP_READY.defaultLogKind())
        assertEquals(BootstrapLogKind.STARTUP, BootstrapStepId.DETECT_EXISTING_SERVER.defaultLogKind())
        assertEquals(BootstrapLogKind.STARTUP, BootstrapStepId.PREPARE_ROOTFS_ASSETS.defaultLogKind())
    }

    @Test
    fun `snapshot isReady true only when READY_MONITORING`() {
        BootstrapLifecycle.values().forEach { lifecycle ->
            val snapshot = BootstrapSessionSnapshot(lifecycle = lifecycle)
            assertEquals(
                "lifecycle=$lifecycle",
                lifecycle == BootstrapLifecycle.READY_MONITORING,
                snapshot.isReady
            )
        }
    }

    @Test
    fun `snapshot canRetry true only when failed`() {
        BootstrapLifecycle.values().forEach { lifecycle ->
            val snapshot = BootstrapSessionSnapshot(lifecycle = lifecycle)
            val expected = lifecycle == BootstrapLifecycle.FAILED_BLOCKED ||
                lifecycle == BootstrapLifecycle.FAILED_ERROR
            assertEquals("lifecycle=$lifecycle", expected, snapshot.canRetry)
        }
    }

    @Test
    fun `findStep returns matching snapshot or null`() {
        val snapshot = BootstrapSessionSnapshot()
        val found = snapshot.findStep(BootstrapStepId.WAIT_HTTP_READY)
        assertNotNull(found)
        assertEquals(BootstrapStepId.WAIT_HTTP_READY, found?.id)

        // 替换步骤列表为只包含 PREPARE_LOG_SESSION 的版本，再查别的应得 null。
        val pruned = snapshot.copy(
            steps = listOf(BootstrapStepSnapshot(BootstrapStepId.PREPARE_LOG_SESSION, "x"))
        )
        assertNull(pruned.findStep(BootstrapStepId.WAIT_HTTP_READY))
    }

    @Test
    fun `currentStep null when currentStepId null`() {
        val snapshot = BootstrapSessionSnapshot()
        assertNull(snapshot.currentStep)
    }

    @Test
    fun `currentStep resolves via currentStepId`() {
        val snapshot = BootstrapSessionSnapshot(currentStepId = BootstrapStepId.START_SERVER_PROCESS)
        assertEquals(BootstrapStepId.START_SERVER_PROCESS, snapshot.currentStep?.id)
    }

    @Test
    fun `shouldReportCurrentStepElapsedSeconds gated on RUNNING + RUNNING step + specific ids`() {
        val baseSteps = defaultBootstrapSteps().map { step ->
            if (step.id == BootstrapStepId.WAIT_HTTP_READY) {
                step.copy(status = BootstrapStepStatus.RUNNING)
            } else step
        }
        val running = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.WAIT_HTTP_READY,
            steps = baseSteps
        )
        assertTrue(running.shouldReportCurrentStepElapsedSeconds())

        val notRunning = running.copy(lifecycle = BootstrapLifecycle.READY_MONITORING)
        assertFalse(notRunning.shouldReportCurrentStepElapsedSeconds())

        val wrongStep = running.copy(
            currentStepId = BootstrapStepId.PREPARE_LOG_SESSION,
            steps = defaultBootstrapSteps().map { step ->
                if (step.id == BootstrapStepId.PREPARE_LOG_SESSION) step.copy(status = BootstrapStepStatus.RUNNING) else step
            }
        )
        assertFalse(wrongStep.shouldReportCurrentStepElapsedSeconds())
    }

    @Test
    fun `shouldReportTavernStartupTail requires RUNNING + tavern log kind`() {
        val baseSteps = defaultBootstrapSteps().map { step ->
            if (step.id == BootstrapStepId.START_SERVER_PROCESS) step.copy(status = BootstrapStepStatus.RUNNING) else step
        }
        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.START_SERVER_PROCESS,
            steps = baseSteps,
            currentLogTargets = BootstrapCurrentLogTargets(currentStepKind = BootstrapLogKind.TAVERN_SERVER)
        )
        assertTrue(snapshot.shouldReportTavernStartupTail())

        assertFalse(snapshot.copy(lifecycle = BootstrapLifecycle.READY_MONITORING).shouldReportTavernStartupTail())
        assertFalse(
            snapshot.copy(currentLogTargets = BootstrapCurrentLogTargets(currentStepKind = BootstrapLogKind.STARTUP))
                .shouldReportTavernStartupTail()
        )
    }

    @Test
    fun `isHttpReadyTransitionSnapshot true only for running wait-http success transition`() {
        val completedWaitHttpSteps = defaultBootstrapSteps().map { step ->
            if (step.id == BootstrapStepId.WAIT_HTTP_READY) {
                step.copy(
                    status = BootstrapStepStatus.COMPLETED,
                    result = BootstrapStepResult.SUCCESS,
                    progressPercent = 100
                )
            } else {
                step
            }
        }
        val transition = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.WAIT_HTTP_READY,
            steps = completedWaitHttpSteps,
            statusMessage = "正在等待 HTTP 服务就绪。"
        )
        assertTrue(transition.isHttpReadyTransitionSnapshot())
        assertFalse(transition.copy(lifecycle = BootstrapLifecycle.READY_MONITORING).isHttpReadyTransitionSnapshot())
        assertFalse(transition.copy(currentStepId = BootstrapStepId.START_SERVER_PROCESS).isHttpReadyTransitionSnapshot())
        assertFalse(
            transition.copy(
                steps = defaultBootstrapSteps().map { step ->
                    if (step.id == BootstrapStepId.WAIT_HTTP_READY) {
                        step.copy(status = BootstrapStepStatus.RUNNING, result = BootstrapStepResult.NONE)
                    } else {
                        step
                    }
                }
            ).isHttpReadyTransitionSnapshot()
        )
    }

    @Test
    fun `shouldPreferTavernServerLog mirrors preferredKind`() {
        val tavern = BootstrapSessionSnapshot(
            currentLogTargets = BootstrapCurrentLogTargets(preferredKind = BootstrapLogKind.TAVERN_SERVER)
        )
        assertTrue(tavern.shouldPreferTavernServerLog())

        val startup = BootstrapSessionSnapshot()
        assertFalse(startup.shouldPreferTavernServerLog())
    }

    @Test
    fun `latestResolvedStep picks most recent finished step`() {
        val steps = listOf(
            BootstrapStepSnapshot(
                id = BootstrapStepId.PREPARE_WORKDIRS,
                title = "wd",
                status = BootstrapStepStatus.COMPLETED,
                finishedAtMillis = 100L
            ),
            BootstrapStepSnapshot(
                id = BootstrapStepId.PREPARE_LOG_SESSION,
                title = "log",
                status = BootstrapStepStatus.SKIPPED,
                finishedAtMillis = 200L
            ),
            BootstrapStepSnapshot(
                id = BootstrapStepId.START_SERVER_PROCESS,
                title = "srv",
                status = BootstrapStepStatus.RUNNING,
                startedAtMillis = 300L
            )
        )
        val snapshot = BootstrapSessionSnapshot(steps = steps)
        assertEquals(BootstrapStepId.PREPARE_LOG_SESSION, snapshot.latestResolvedStep()?.id)
    }

    @Test
    fun `latestResolvedStep returns null when nothing resolved`() {
        val snapshot = BootstrapSessionSnapshot(
            steps = listOf(
                BootstrapStepSnapshot(BootstrapStepId.PREPARE_WORKDIRS, "wd", status = BootstrapStepStatus.PENDING),
                BootstrapStepSnapshot(BootstrapStepId.PREPARE_LOG_SESSION, "log", status = BootstrapStepStatus.RUNNING)
            )
        )
        assertNull(snapshot.latestResolvedStep())
    }

    @Test
    fun `currentStepDisplayText falls back to none`() {
        assertEquals("无", BootstrapSessionSnapshot().currentStepDisplayText())
    }

    @Test
    fun `currentStepDisplayText shows title and status`() {
        val snapshot = BootstrapSessionSnapshot(
            currentStepId = BootstrapStepId.START_SERVER_PROCESS,
            steps = defaultBootstrapSteps().map { step ->
                if (step.id == BootstrapStepId.START_SERVER_PROCESS) step.copy(status = BootstrapStepStatus.RUNNING) else step
            }
        )
        assertEquals("启动 Tavern 进程 · 执行中", snapshot.currentStepDisplayText())
    }

    @Test
    fun `eventReasonDisplayText prefers running step details`() {
        val steps = defaultBootstrapSteps().map { step ->
            if (step.id == BootstrapStepId.START_SERVER_PROCESS) {
                step.copy(status = BootstrapStepStatus.RUNNING, details = "starting node")
            } else step
        }
        val snapshot = BootstrapSessionSnapshot(
            currentStepId = BootstrapStepId.START_SERVER_PROCESS,
            steps = steps,
            lastFailure = BootstrapFailureSnapshot(
                title = "x",
                details = "failure-detail",
                isBlocked = true
            ),
            lastEventSummary = "evt-summary",
            statusDetails = "status-detail",
            statusMessage = "status-msg"
        )
        assertEquals("starting node", snapshot.eventReasonDisplayText())
    }

    @Test
    fun `eventReasonDisplayText falls through to failure then summary then statusDetails then statusMessage`() {
        val base = BootstrapSessionSnapshot(statusMessage = "status-msg")

        // 默认仅有 statusMessage（默认值），返回该 message。
        assertEquals("正在准备 SillyDroid 宿主环境。", BootstrapSessionSnapshot().eventReasonDisplayText())

        val withStatusDetails = base.copy(statusDetails = "status-detail")
        assertEquals("status-detail", withStatusDetails.eventReasonDisplayText())

        val withSummary = withStatusDetails.copy(lastEventSummary = "evt-summary")
        assertEquals("evt-summary", withSummary.eventReasonDisplayText())

        val withFailure = withSummary.copy(
            lastFailure = BootstrapFailureSnapshot(title = "x", details = "failure-detail", isBlocked = false)
        )
        assertEquals("failure-detail", withFailure.eventReasonDisplayText())
    }

    @Test
    fun `withDerivedUiFlags showWebView true only in READY_MONITORING or RESTART_SCHEDULED`() {
        BootstrapLifecycle.values().forEach { lifecycle ->
            val snapshot = BootstrapSessionSnapshot(lifecycle = lifecycle).withDerivedUiFlags()
            val expectedShowWebView = lifecycle == BootstrapLifecycle.READY_MONITORING ||
                lifecycle == BootstrapLifecycle.RESTART_SCHEDULED
            assertEquals("lifecycle=$lifecycle", expectedShowWebView, snapshot.derivedUiFlags.showWebView)
            assertEquals(
                "overlay should be inverse of webview, lifecycle=$lifecycle",
                !expectedShowWebView,
                snapshot.derivedUiFlags.showBootstrapOverlay
            )
        }
    }

    @Test
    fun `withDerivedUiFlags showProgress for RUNNING RESTART_SCHEDULED PAUSING_FOR_SETTINGS`() {
        val showSet = setOf(
            BootstrapLifecycle.RUNNING,
            BootstrapLifecycle.RESTART_SCHEDULED,
            BootstrapLifecycle.PAUSING_FOR_SETTINGS
        )
        BootstrapLifecycle.values().forEach { lifecycle ->
            val flags = BootstrapSessionSnapshot(lifecycle = lifecycle).withDerivedUiFlags().derivedUiFlags
            assertEquals("lifecycle=$lifecycle", lifecycle in showSet, flags.showProgress)
        }
    }

    @Test
    fun `withDerivedUiFlags canOpenSettings unconditional in steady states`() {
        val unconditional = setOf(
            BootstrapLifecycle.READY_MONITORING,
            BootstrapLifecycle.PAUSING_FOR_SETTINGS,
            BootstrapLifecycle.CONFIGURING,
            BootstrapLifecycle.FAILED_BLOCKED,
            BootstrapLifecycle.FAILED_ERROR
        )
        unconditional.forEach { lifecycle ->
            val flags = BootstrapSessionSnapshot(lifecycle = lifecycle).withDerivedUiFlags().derivedUiFlags
            assertTrue("lifecycle=$lifecycle should allow settings", flags.canOpenSettings)
        }
    }

    @Test
    fun `withDerivedUiFlags canOpenSettings blocked while running asset steps even if previously completed`() {
        val flags = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.PREPARE_ROOTFS_ASSETS,
            bootstrapPreviouslyCompleted = true
        ).withDerivedUiFlags().derivedUiFlags
        assertFalse(flags.canOpenSettings)

        val flagsServerAssets = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.PREPARE_SERVER_ASSETS,
            bootstrapPreviouslyCompleted = true
        ).withDerivedUiFlags().derivedUiFlags
        assertFalse(flagsServerAssets.canOpenSettings)
    }

    @Test
    fun `withDerivedUiFlags canOpenSettings allowed while running other steps if previously completed`() {
        val flags = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.START_SERVER_PROCESS,
            bootstrapPreviouslyCompleted = true
        ).withDerivedUiFlags().derivedUiFlags
        assertTrue(flags.canOpenSettings)
    }

    @Test
    fun `withDerivedUiFlags canOpenSettings denied during RUNNING when never completed`() {
        val flags = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.START_SERVER_PROCESS,
            bootstrapPreviouslyCompleted = false
        ).withDerivedUiFlags().derivedUiFlags
        assertFalse(flags.canOpenSettings)
    }

    @Test
    fun `withDerivedUiFlags canRetry includes CONFIGURING beyond default canRetry`() {
        val configuring = BootstrapSessionSnapshot(lifecycle = BootstrapLifecycle.CONFIGURING)
            .withDerivedUiFlags().derivedUiFlags
        assertTrue(configuring.canRetry)

        val failed = BootstrapSessionSnapshot(lifecycle = BootstrapLifecycle.FAILED_ERROR)
            .withDerivedUiFlags().derivedUiFlags
        assertTrue(failed.canRetry)

        val ready = BootstrapSessionSnapshot(lifecycle = BootstrapLifecycle.READY_MONITORING)
            .withDerivedUiFlags().derivedUiFlags
        assertFalse(ready.canRetry)
    }

    @Test
    fun `displayLabel covers all lifecycle and status enum cases`() {
        BootstrapLifecycle.values().forEach { value ->
            assertTrue("missing label for $value", value.displayLabel().isNotBlank())
        }
        BootstrapStepStatus.values().forEach { value ->
            assertTrue("missing label for $value", value.displayLabel().isNotBlank())
        }
        BootstrapStepResult.values().forEach { value ->
            assertTrue("missing label for $value", value.displayLabel().isNotBlank())
        }
    }

    @Test
    fun `buildStatusSummaryText composes all 4 lines`() {
        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.READY_MONITORING,
            currentStepId = BootstrapStepId.WAIT_HTTP_READY,
            statusMessage = "hi"
        )
        val text = snapshot.buildStatusSummaryText()
        assertTrue(text.contains("当前状态："))
        assertTrue(text.contains("当前步骤："))
        assertTrue(text.contains("步骤结果："))
        assertTrue(text.contains("事件原因："))
        assertTrue(text.contains("就绪监控中"))
    }
}
