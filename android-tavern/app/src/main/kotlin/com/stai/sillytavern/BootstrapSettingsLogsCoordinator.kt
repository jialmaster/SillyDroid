package com.stai.sillytavern

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.TextView

internal class BootstrapSettingsLogsCoordinator(
    private val activity: AppCompatActivity,
    private val metaView: TextView,
    private val emptyView: TextView,
    private val contentView: TextView,
    private val exportButton: MaterialButton,
    private val reloadButton: MaterialButton,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val requestExport: (String) -> Unit
) {
    private var busy = false
    private var currentSnapshot: HostLogSnapshot? = null

    fun initialize() {
        exportButton.setOnClickListener {
            val snapshot = currentSnapshot ?: return@setOnClickListener
            requestExport(snapshot.fileName)
        }
        reloadButton.setOnClickListener {
            reloadLatestLog()
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
                        snapshot.sourceFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Failed to open export target.")
                }
            }
            setBusyState(false)

            result.onSuccess {
                showMessage(activity.getString(R.string.bootstrap_settings_logs_export_success, snapshot.fileName))
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
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_logs_load_failed))
            }
        }
    }

    private fun loadLatestLogSnapshot(): HostLogSnapshot? {
        return HostLogReader.readLatestSnapshot(activity)
    }

    private fun renderSnapshot() {
        exportButton.isEnabled = !busy && currentSnapshot != null
        reloadButton.isEnabled = !busy
        val snapshot = currentSnapshot
        emptyView.isVisible = snapshot == null
        metaView.isVisible = snapshot != null
        contentView.isVisible = snapshot != null

        if (snapshot == null) {
            metaView.text = ""
            contentView.text = ""
            return
        }

        metaView.text = activity.getString(R.string.bootstrap_settings_logs_meta, snapshot.fileName, snapshot.updatedAt)
        contentView.text = snapshot.content.ifBlank { activity.getString(R.string.bootstrap_settings_logs_empty_content) }
    }

    private fun setBusyState(value: Boolean) {
        busy = value
        setBusy(value)
        renderSnapshot()
    }
}