package com.jm.sillydroid.feature.main.ui.home.bootstrap

import android.widget.TextView
import com.jm.sillydroid.core.model.bootstrap.BootstrapDerivedUiFlags
import com.jm.sillydroid.core.model.bootstrap.BootstrapFailureDiagnosis
import com.jm.sillydroid.core.model.bootstrap.BootstrapFailureSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BootstrapOverlayRendererFailureDiagnosisTest {

    @Test
    fun `failed state appends stage reason solutions and nearest log`() {
        val statusView = mock<TextView>()
        whenever(statusView.text).thenReturn("")

        val renderer = BootstrapOverlayRenderer(
            views = BootstrapOverlayViews(
                overlay = mock(),
                status = statusView,
                retryButton = mock(),
                settingsButton = mock(),
                progress = mock(),
                progressLabel = mock(),
                webViewRefreshLayout = mock(),
                webView = { mock() }
            ),
            text = BootstrapOverlayText(
                pausedMessage = { "paused-message" },
                pausedDetails = { "paused-details" },
                resumeLabel = { "resume" },
                retryLabel = { "retry" },
                progressLabel = { "$it%" },
                progressIndeterminate = { "indeterminate" },
                startupElapsed = { "elapsed $it" },
                tavernLogTail = { "tail $it" }
            ),
            syncSettingsEntryState = {},
            showWebView = {},
            updateWebViewRefreshLayoutEnabled = {},
            setPullGestureRefreshing = {},
            onReadyMonitoring = {}
        )

        renderer.render(
            BootstrapSessionSnapshot(
                lifecycle = BootstrapLifecycle.FAILED_ERROR,
                statusMessage = "启动失败",
                statusDetails = "HTTP not ready",
                derivedUiFlags = BootstrapDerivedUiFlags(canRetry = true),
                lastFailure = BootstrapFailureSnapshot(
                    stepId = BootstrapStepId.WAIT_HTTP_READY,
                    title = "启动失败",
                    details = "HTTP not ready",
                    isBlocked = false,
                    diagnosis = BootstrapFailureDiagnosis(
                        stageTitle = "等待 HTTP 服务就绪",
                        logFileName = "sillydroid-server.log",
                        logExcerpt = "Blocked connection from 192.168.1.23",
                        suspectedReason = "SillyTavern IP 白名单拦截了当前访问来源。",
                        solutions = listOf("在 config.yaml 的 whitelist 中保留 127.0.0.1 和 ::1。")
                    )
                )
            )
        )

        verify(statusView).text = "启动失败\nHTTP not ready\n失败阶段：等待 HTTP 服务就绪\n可能原因：SillyTavern IP 白名单拦截了当前访问来源。\n解决方案：\n1. 在 config.yaml 的 whitelist 中保留 127.0.0.1 和 ::1。\n最近日志（sillydroid-server.log）：\nBlocked connection from 192.168.1.23"
    }
}
