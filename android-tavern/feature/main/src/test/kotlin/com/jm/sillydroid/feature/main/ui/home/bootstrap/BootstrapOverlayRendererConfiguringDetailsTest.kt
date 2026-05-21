package com.jm.sillydroid.feature.main.ui.home.bootstrap

import android.widget.TextView
import com.jm.sillydroid.core.model.bootstrap.BootstrapDerivedUiFlags
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BootstrapOverlayRendererConfiguringDetailsTest {

    @Test
    fun `configuring state keeps paused details and appends runtime pause reason`() {
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
                lifecycle = BootstrapLifecycle.CONFIGURING,
                statusDetails = "port 8000 occupied",
                derivedUiFlags = BootstrapDerivedUiFlags(
                    showWebView = false,
                    showBootstrapOverlay = true,
                    canRetry = true
                )
            )
        )

        verify(statusView).text = "paused-message\npaused-details\nport 8000 occupied"
    }
}
