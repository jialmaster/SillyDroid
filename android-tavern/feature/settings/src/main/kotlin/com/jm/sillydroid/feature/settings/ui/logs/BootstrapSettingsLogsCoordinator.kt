package com.jm.sillydroid.feature.settings.ui.logs

import android.net.Uri
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogSnapshot
import com.jm.sillydroid.domain.logs.HostLogRepository
import com.jm.sillydroid.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootstrapSettingsLogsCoordinator(
    private val activity: AppCompatActivity,
    private val dispatchers: DispatcherProvider,
    private val sessionSummaryView: TextView,
    private val metaView: TextView,
    private val emptyView: TextView,
    private val contentView: TextView,
    private val logsScrollView: NestedScrollView,
    private val scrollToBottomButton: ImageButton,
    private val selectButton: MaterialButton,
    private val exportButton: MaterialButton,
    private val reloadButton: MaterialButton,
    private val clearButton: MaterialButton,
    private val hostLogRepository: HostLogRepository,
    private val bootstrapSessionSummaryFlow: Flow<String>,
    private val currentBootstrapSessionSummary: () -> String,
    private val preferTavernServerLog: () -> Boolean,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val requestExport: () -> Unit
) {
    private var busy = false
    private var currentSnapshot: HostLogSnapshot? = null
    private var currentEntries: List<HostLogEntry> = emptyList()
    private var selectedLogFileName: String? = null
    private var bootstrapSessionSummaryText: String = currentBootstrapSessionSummary()

    fun initialize() {
        selectButton.setOnClickListener {
            showLogSelectionDialog()
        }
        exportButton.setOnClickListener {
            requestExport()
        }
        reloadButton.setOnClickListener {
            reloadLatestLog()
        }
        clearButton.setOnClickListener {
            confirmClearAllLogs()
        }
        scrollToBottomButton.setOnClickListener {
            scrollToBottom()
        }
        logsScrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            updateScrollToBottomButtonVisibility()
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                bootstrapSessionSummaryFlow.collect { summary ->
                    bootstrapSessionSummaryText = summary
                    renderSessionSummary()
                }
            }
        }
        renderSessionSummary()
        renderSnapshot()
    }

    fun exportLogBundle(targetUri: Uri) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    hostLogRepository.exportToUri(targetUri)
                }
            }
            setBusyState(false)

            result.onSuccess { export ->
                showMessage(activity.getString(R.string.bootstrap_settings_logs_export_success, export.bundleFileName))
            }.onFailure {
                showError(activity.getString(R.string.bootstrap_settings_logs_export_failed))
            }
        }
    }

    fun reloadLatestLog() {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    loadLatestLogSnapshot()
                }
            }
            setBusyState(false)

            result.onSuccess { snapshot ->
                currentSnapshot = snapshot
                renderSnapshot()
                scrollToBottom()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_logs_load_failed))
            }
        }
    }

    private fun loadLatestLogSnapshot(): HostLogSnapshot? {
        currentEntries = hostLogRepository.listEntries()
        if (currentEntries.isEmpty()) {
            selectedLogFileName = null
            return null
        }

        val selectedEntry = selectedLogFileName?.let { fileName ->
            currentEntries.firstOrNull { entry -> entry.fileName == fileName }
        }
        if (selectedLogFileName != null && selectedEntry == null) {
            selectedLogFileName = null
        }

        return if (selectedEntry != null) {
            hostLogRepository.readSnapshot(selectedEntry)
        } else {
            hostLogRepository.readPreferredSnapshot(
                preferTavernServerLog = preferTavernServerLog(),
                entries = currentEntries
            )
        }
    }

    private fun renderSnapshot() {
        selectButton.isEnabled = !busy && currentEntries.isNotEmpty()
        exportButton.isEnabled = !busy
        reloadButton.isEnabled = !busy
        clearButton.isEnabled = !busy
        val snapshot = currentSnapshot
        emptyView.isVisible = snapshot == null
        metaView.isVisible = snapshot != null
        contentView.isVisible = snapshot != null

        if (snapshot == null) {
            metaView.text = ""
            contentView.text = ""
            scrollToBottomButton.isVisible = false
            return
        }

        metaView.text = activity.getString(R.string.bootstrap_settings_logs_meta, snapshot.displayName, snapshot.updatedAt)
        contentView.text = snapshot.content.ifBlank { activity.getString(R.string.bootstrap_settings_logs_empty_content) }
        updateScrollToBottomButtonVisibility()
    }

    private fun renderSessionSummary() {
        sessionSummaryView.isVisible = bootstrapSessionSummaryText.isNotBlank()
        sessionSummaryView.text = bootstrapSessionSummaryText
    }

    private fun scrollToBottom() {
        contentView.post {
            logsScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            logsScrollView.post { updateScrollToBottomButtonVisibility() }
        }
    }

    private fun updateScrollToBottomButtonVisibility() {
        if (currentSnapshot == null) {
            scrollToBottomButton.isVisible = false
            return
        }
        val child = logsScrollView.getChildAt(0) ?: run {
            scrollToBottomButton.isVisible = false
            return
        }
        val tolerancePx = (8 * activity.resources.displayMetrics.density).toInt()
        val atBottom = child.bottom - (logsScrollView.height + logsScrollView.scrollY) <= tolerancePx
        scrollToBottomButton.isVisible = !atBottom
    }

    private fun showLogSelectionDialog() {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    hostLogRepository.listEntries()
                }
            }
            setBusyState(false)

            result.onSuccess { entries ->
                currentEntries = entries
                if (entries.isEmpty()) {
                    currentSnapshot = null
                    renderSnapshot()
                    showMessage(activity.getString(R.string.bootstrap_settings_logs_empty))
                    return@onSuccess
                }

                val optionLabels = buildList {
                    add(activity.getString(R.string.bootstrap_settings_logs_select_auto))
                    entries.forEach { entry ->
                        add(activity.getString(R.string.bootstrap_settings_logs_select_item, entry.displayName, entry.updatedAt))
                    }
                }
                val checkedItem = selectedLogFileName?.let { fileName ->
                    entries.indexOfFirst { entry -> entry.fileName == fileName }
                        .takeIf { index -> index >= 0 }
                        ?.plus(1)
                } ?: 0
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.bootstrap_settings_logs_select_title)
                    .setSingleChoiceItems(optionLabels.toTypedArray(), checkedItem) { dialog, which ->
                        selectedLogFileName = if (which == 0) {
                            null
                        } else {
                            entries[which - 1].fileName
                        }
                        dialog.dismiss()
                        reloadLatestLog()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_logs_load_failed))
            }
        }
    }

    private fun confirmClearAllLogs() {
        if (busy) {
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_logs_clear_confirm_title)
            .setMessage(R.string.bootstrap_settings_logs_clear_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_logs_clear) { _, _ ->
                clearAllLogs()
            }
            .show()
    }

    private fun clearAllLogs() {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    hostLogRepository.clearAllLogs()
                }
            }
            setBusyState(false)

            result.onSuccess {
                selectedLogFileName = null
                currentEntries = emptyList()
                currentSnapshot = null
                renderSnapshot()
                showMessage(activity.getString(R.string.bootstrap_settings_logs_clear_success))
                reloadLatestLog()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_logs_clear_failed))
            }
        }
    }

    private fun setBusyState(value: Boolean) {
        busy = value
        setBusy(value)
        renderSnapshot()
    }
}
