package com.jm.sillydroid

import android.net.Uri
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BootstrapSettingsLogsCoordinator(
    private val activity: AppCompatActivity,
    private val metaView: TextView,
    private val emptyView: TextView,
    private val contentView: TextView,
    private val logsScrollView: NestedScrollView,
    private val scrollToBottomButton: ImageButton,
    private val selectButton: MaterialButton,
    private val exportButton: MaterialButton,
    private val reloadButton: MaterialButton,
    private val clearButton: MaterialButton,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val requestExport: () -> Unit
) {
    private var busy = false
    private var currentSnapshot: HostLogSnapshot? = null
    private var currentEntries: List<HostLogEntry> = emptyList()
    private var selectedLogPath: String? = null

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
        renderSnapshot()
    }

    fun exportLogBundle(targetUri: Uri) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    HostLogManager.exportToUri(activity, targetUri)
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
            val result = withContext(Dispatchers.IO) {
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
        currentEntries = HostLogManager.listEntries(activity)
        if (currentEntries.isEmpty()) {
            selectedLogPath = null
            return null
        }

        val selectedEntry = selectedLogPath?.let { path ->
            currentEntries.firstOrNull { entry -> entry.sourceFile.absolutePath == path }
        }
        if (selectedLogPath != null && selectedEntry == null) {
            selectedLogPath = null
        }

        return if (selectedEntry != null) {
            HostLogManager.readSnapshot(activity, selectedEntry.sourceFile, entry = selectedEntry)
        } else {
            HostLogManager.readPreferredSnapshot(
                context = activity,
                preferTavernServerLog = StartupRuntimeStore.state.value.shouldPreferTavernServerLog,
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    HostLogManager.listEntries(activity)
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
                val checkedItem = selectedLogPath?.let { path ->
                    entries.indexOfFirst { entry -> entry.sourceFile.absolutePath == path }
                        .takeIf { index -> index >= 0 }
                        ?.plus(1)
                } ?: 0
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.bootstrap_settings_logs_select_title)
                    .setSingleChoiceItems(optionLabels.toTypedArray(), checkedItem) { dialog, which ->
                        selectedLogPath = if (which == 0) {
                            null
                        } else {
                            entries[which - 1].sourceFile.absolutePath
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    HostLogManager.clearAllLogs(activity)
                }
            }
            setBusyState(false)

            result.onSuccess {
                selectedLogPath = null
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
