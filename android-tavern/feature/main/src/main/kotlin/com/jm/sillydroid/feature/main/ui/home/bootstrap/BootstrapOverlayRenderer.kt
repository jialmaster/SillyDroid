package com.jm.sillydroid.feature.main.ui.home.bootstrap

import android.os.Build
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot

class BootstrapOverlayRenderer(
    private val views: BootstrapOverlayViews,
    private val text: BootstrapOverlayText,
    private val syncSettingsEntryState: (BootstrapSessionSnapshot) -> Unit,
    private val showWebView: (String) -> Unit,
    private val updateWebViewRefreshLayoutEnabled: () -> Unit,
    private val setPullGestureRefreshing: (Boolean) -> Unit,
    private val onReadyMonitoring: () -> Unit
) {
    fun render(snapshot: BootstrapSessionSnapshot) {
        renderStatusText(snapshot)

        views.retryButton.isVisible = snapshot.derivedUiFlags.canRetry
        views.retryButton.text = if (snapshot.lifecycle == BootstrapLifecycle.CONFIGURING) {
            text.resumeLabel()
        } else {
            text.retryLabel()
        }
        views.progress.isVisible = snapshot.derivedUiFlags.showProgress
        views.progressLabel.isVisible = views.progress.isVisible
        views.settingsButton.isVisible = !snapshot.derivedUiFlags.showWebView
        syncSettingsEntryState(snapshot)
        renderProgress(snapshot)

        if (snapshot.lifecycle == BootstrapLifecycle.READY_MONITORING) {
            showWebView(snapshot.localUrl)
            onReadyMonitoring()
        } else if (snapshot.derivedUiFlags.showWebView) {
            views.webViewRefreshLayout.isVisible = true
            views.webView.isVisible = true
            updateWebViewRefreshLayoutEnabled()
        } else {
            views.overlay.isVisible = true
            views.webViewRefreshLayout.isVisible = false
            views.webViewRefreshLayout.isEnabled = false
            views.webViewRefreshLayout.isRefreshing = false
            setPullGestureRefreshing(false)
            views.webView.isVisible = false
        }
    }

    private fun renderStatusText(snapshot: BootstrapSessionSnapshot) {
        val displayMessage = if (snapshot.lifecycle == BootstrapLifecycle.CONFIGURING) {
            text.pausedMessage()
        } else {
            snapshot.statusMessage
        }
        val baseDetails = if (snapshot.lifecycle == BootstrapLifecycle.CONFIGURING) {
            text.pausedDetails()
        } else {
            snapshot.statusDetails
        }
        val displayDetails = buildDisplayDetails(
            snapshot = snapshot,
            baseDetails = baseDetails
        )

        val renderedText = if (displayDetails.isBlank()) {
            displayMessage
        } else {
            buildString {
                append(displayMessage)
                append('\n')
                append(displayDetails)
            }
        }
        if (views.status.text?.toString() != renderedText) {
            views.status.text = renderedText
        }
    }

    private fun buildDisplayDetails(
        snapshot: BootstrapSessionSnapshot,
        baseDetails: String
    ): String {
        val sections = mutableListOf<String>()
        if (baseDetails.isNotBlank()) {
            sections += baseDetails
        }

        if (snapshot.derivedUiFlags.showWaitingTimer) {
            val elapsedSeconds = snapshot.currentStepElapsedSeconds
            if (elapsedSeconds != null) {
                sections += text.startupElapsed(elapsedSeconds)
            }
        }

        if (snapshot.derivedUiFlags.showTavernStartupTail) {
            val tavernLogLine = resolveDisplayedTavernLogLine(snapshot)
            if (tavernLogLine != null) {
                sections += text.tavernLogTail(tavernLogLine)
            }
        }

        return sections.joinToString(separator = "\n")
    }

    private fun renderProgress(snapshot: BootstrapSessionSnapshot) {
        views.progress.max = 100
        val progressPercent = snapshot.progressPercent.coerceIn(0, 100)
        views.progress.isIndeterminate = progressPercent <= 0
        if (!views.progress.isIndeterminate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                views.progress.setProgress(progressPercent, true)
            } else {
                views.progress.progress = progressPercent
            }
            views.progressLabel.text = text.progressLabel(progressPercent)
        } else {
            views.progressLabel.text = text.progressIndeterminate()
        }
    }

    private fun resolveDisplayedTavernLogLine(snapshot: BootstrapSessionSnapshot): String? {
        if (!snapshot.derivedUiFlags.showTavernStartupTail) {
            return null
        }

        return snapshot.currentTavernServerLogLine.takeIf { it.isNotBlank() }
    }
}

data class BootstrapOverlayViews(
    val overlay: View,
    val status: TextView,
    val retryButton: Button,
    val settingsButton: ImageButton,
    val progress: ProgressBar,
    val progressLabel: TextView,
    val webViewRefreshLayout: SwipeRefreshLayout,
    val webView: WebView
)

data class BootstrapOverlayText(
    val pausedMessage: () -> String,
    val pausedDetails: () -> String,
    val resumeLabel: () -> String,
    val retryLabel: () -> String,
    val progressLabel: (percent: Int) -> String,
    val progressIndeterminate: () -> String,
    val startupElapsed: (seconds: Int) -> String,
    val tavernLogTail: (line: String) -> String
)
