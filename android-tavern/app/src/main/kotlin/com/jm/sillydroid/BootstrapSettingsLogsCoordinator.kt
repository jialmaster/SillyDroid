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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val requestExport: (String) -> Unit
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
            val snapshot = currentSnapshot ?: return@setOnClickListener
            val zipName = snapshot.fileName.removeSuffix(".log") + "-log.zip"
            requestExport(zipName)
        }
        reloadButton.setOnClickListener {
            reloadLatestLog()
        }
        scrollToBottomButton.setOnClickListener {
            scrollToBottom()
        }
        logsScrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            updateScrollToBottomButtonVisibility()
        }
        renderSnapshot()
    }

    fun exportCurrentLog(targetUri: Uri) {
        val snapshot = currentSnapshot ?: run {
            showError(activity.getString(R.string.bootstrap_settings_logs_export_failed))
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(targetUri)?.use { output ->
                        ZipOutputStream(output).use { zipOut ->
                            zipOut.putNextEntry(ZipEntry(snapshot.fileName))
                            snapshot.sourceFile.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    } ?: throw IllegalStateException("Failed to open export target.")
                }
            }
            setBusyState(false)

            result.onSuccess {
                showMessage(activity.getString(R.string.bootstrap_settings_logs_export_success, snapshot.displayName))
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
        currentEntries = HostLogReader.listEntries(activity)
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
            HostLogReader.readSnapshot(activity, selectedEntry.sourceFile, entry = selectedEntry)
        } else {
            HostLogReader.readPreferredSnapshot(
                context = activity,
                preferTavernServerLog = StartupRuntimeStore.state.value.isReady,
                entries = currentEntries
            )
        }
    }

    private fun renderSnapshot() {
        selectButton.isEnabled = !busy && currentEntries.isNotEmpty()
        exportButton.isEnabled = !busy && currentSnapshot != null
        reloadButton.isEnabled = !busy
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
                    HostLogReader.listEntries(activity)
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

    private fun setBusyState(value: Boolean) {
        busy = value
        setBusy(value)
        renderSnapshot()
    }
}
