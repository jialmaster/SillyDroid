package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepResult
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepStatus
import com.jm.sillydroid.core.model.bootstrap.defaultBootstrapSteps
import com.jm.sillydroid.core.model.bootstrap.isHttpReadyTransitionSnapshot
import com.jm.sillydroid.core.model.bootstrap.withDerivedUiFlags
import com.jm.sillydroid.core.model.notification.HostNotificationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：约束前台通知 ready 态的文案，避免重新回到“等待 HTTP 就绪(100%)”。
 */
class StartupCoordinatorServiceNotificationTextTest {

    @Test
    fun `ready monitoring snapshot tells user to return to tavern`() {
        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.READY_MONITORING,
            statusMessage = "SillyTavern 已启动。"
        ).withDerivedUiFlags()

        assertEquals(
            "SillyTavern 已启动，点击返回酒馆",
            resolveForegroundNotificationContentText(snapshot)
        )
    }

    @Test
    fun `http ready transition snapshot already uses ready wording`() {
        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.WAIT_HTTP_READY,
            statusMessage = "正在等待 HTTP 服务就绪。",
            steps = defaultBootstrapSteps().map { step ->
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
        ).withDerivedUiFlags()

        assertEquals(
            "SillyTavern 已启动，点击返回酒馆",
            resolveForegroundNotificationContentText(snapshot)
        )
        assertTrue(snapshot.isHttpReadyTransitionSnapshot())
    }

    @Test
    fun `running progress snapshot keeps progress wording before ready`() {
        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.WAIT_HTTP_READY,
            progressPercent = 67,
            statusMessage = "正在等待 HTTP 服务就绪。",
            steps = defaultBootstrapSteps().map { step ->
                if (step.id == BootstrapStepId.WAIT_HTTP_READY) {
                    step.copy(
                        status = BootstrapStepStatus.RUNNING,
                        progressPercent = 67
                    )
                } else {
                    step
                }
            }
        ).withDerivedUiFlags()

        assertEquals(
            "正在等待 HTTP 服务就绪。 (67%)",
            resolveForegroundNotificationContentText(snapshot)
        )
    }

    @Test
    fun `ready transition no longer keeps progress bar`() {
        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            currentStepId = BootstrapStepId.WAIT_HTTP_READY,
            progressPercent = 100,
            statusMessage = "正在等待 HTTP 服务就绪。",
            steps = defaultBootstrapSteps().map { step ->
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
        ).withDerivedUiFlags()

        val progress = when {
            !snapshot.derivedUiFlags.showProgress || shouldShowForegroundReadyState(snapshot) -> HostNotificationProgress.None
            snapshot.progressPercent <= 0 -> HostNotificationProgress.Indeterminate
            else -> HostNotificationProgress.Determinate(snapshot.progressPercent.coerceIn(0, 100), 100)
        }

        assertEquals(HostNotificationProgress.None, progress)
    }
}
