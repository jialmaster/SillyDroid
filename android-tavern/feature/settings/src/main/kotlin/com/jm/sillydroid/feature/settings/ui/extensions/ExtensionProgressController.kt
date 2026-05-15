package com.jm.sillydroid.feature.settings.ui.extensions

import androidx.appcompat.app.AppCompatActivity
import com.jm.sillydroid.core.model.extensions.ExtensionRuntimeProgress
import com.jm.sillydroid.feature.settings.R

/**
 * 负责扩展安装/删除/重装过程中的进度状态聚合与渲染。
 *
 * 从 `BootstrapSettingsExtensionsCoordinator` 中抽离，使得：
 * 1. 进度文案/百分比映射逻辑可独立演进；
 * 2. 主协调器只关心“何时通知进度”，无需关心“如何展示”。
 */
internal class ExtensionProgressController(
    private val activity: AppCompatActivity,
    private val progressHost: ExtensionInstallProgressHost
) {

    data class State(
        val actionLabel: String,
        val stageLabel: String,
        val percent: Int?,
        val indeterminate: Boolean
    )

    data class BatchDescriptor(
        val currentIndex: Int,
        val totalCount: Int,
        val itemLabel: String
    )

    private var current: State? = null

    fun publishRuntimeProgress(actionLabel: String, runtimeProgress: ExtensionRuntimeProgress) {
        val nextState = mapProgressState(actionLabel, runtimeProgress)
        activity.runOnUiThread {
            current = nextState
            render()
        }
    }

    fun publishBatchRuntimeProgress(
        actionLabel: String,
        descriptor: BatchDescriptor,
        runtimeProgress: ExtensionRuntimeProgress
    ) {
        val mapped = mapProgressState(actionLabel, runtimeProgress)
        publishBatchProgress(
            actionLabel = actionLabel,
            descriptor = descriptor,
            stageLabel = mapped.stageLabel,
            itemPercent = mapped.percent,
            indeterminate = mapped.indeterminate
        )
    }

    fun publishBatchProgress(
        actionLabel: String,
        descriptor: BatchDescriptor,
        stageLabel: String,
        itemPercent: Int?,
        indeterminate: Boolean
    ) {
        val overallPercent = itemPercent?.let { percent ->
            val safePercent = percent.coerceIn(0, 100)
            ((((descriptor.currentIndex - 1).toDouble() + (safePercent / 100.0)) / descriptor.totalCount.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        }
        val nextState = State(
            actionLabel = actionLabel,
            stageLabel = activity.getString(
                R.string.bootstrap_settings_extensions_batch_item_stage,
                descriptor.currentIndex,
                descriptor.totalCount,
                descriptor.itemLabel,
                stageLabel
            ),
            percent = overallPercent,
            indeterminate = indeterminate || overallPercent == null
        )
        activity.runOnUiThread {
            current = nextState
            render()
        }
    }

    fun setProgressState(actionLabel: String, stageLabel: String, percent: Int?, indeterminate: Boolean) {
        current = State(
            actionLabel = actionLabel,
            stageLabel = stageLabel,
            percent = percent,
            indeterminate = indeterminate
        )
        render()
    }

    fun clear() {
        current = null
        render()
    }

    /** 在外部刷新（如 busy 状态变化引发列表重绘）后强制重发进度视图。 */
    fun refresh() {
        render()
    }

    private fun mapProgressState(actionLabel: String, runtimeProgress: ExtensionRuntimeProgress): State {
        val stageLabel = when {
            !runtimeProgress.message.isNullOrBlank() -> runtimeProgress.message.orEmpty()
            runtimeProgress.step == "backup" -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_backup)
            runtimeProgress.step == "validate" -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_validating)
            runtimeProgress.step == "completed" -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_completed)
            runtimeProgress.phase?.contains("receiving objects", ignoreCase = true) == true -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_receiving)
            runtimeProgress.phase?.contains("resolving deltas", ignoreCase = true) == true -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_resolving)
            runtimeProgress.phase?.contains("updating workdir", ignoreCase = true) == true -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating)
            else -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_prepare)
        }

        val percent = when {
            runtimeProgress.step == "completed" -> 100
            runtimeProgress.phase?.contains("receiving objects", ignoreCase = true) == true -> scaleProgress(runtimeProgress.loaded, runtimeProgress.total, 6, 82)
            runtimeProgress.phase?.contains("resolving deltas", ignoreCase = true) == true -> scaleProgress(runtimeProgress.loaded, runtimeProgress.total, 82, 94)
            runtimeProgress.phase?.contains("updating workdir", ignoreCase = true) == true -> scaleProgress(runtimeProgress.loaded, runtimeProgress.total, 94, 99)
            else -> null
        }

        return State(
            actionLabel = actionLabel,
            stageLabel = stageLabel,
            percent = percent,
            indeterminate = runtimeProgress.indeterminate || percent == null
        )
    }

    private fun render() {
        val snapshot = current
        if (snapshot == null) {
            progressHost.hide()
            return
        }

        val message = if (!snapshot.indeterminate && snapshot.percent != null) {
            activity.getString(
                R.string.bootstrap_settings_extensions_progress_label,
                snapshot.actionLabel,
                snapshot.stageLabel,
                snapshot.percent
            )
        } else {
            activity.getString(
                R.string.bootstrap_settings_extensions_progress_indeterminate,
                snapshot.actionLabel,
                snapshot.stageLabel
            )
        }

        progressHost.show(
            message = message,
            percent = snapshot.percent,
            indeterminate = snapshot.indeterminate
        )
    }
}

internal fun scaleProgress(loaded: Int?, total: Int?, minPercent: Int, maxPercent: Int): Int? {
    val safeLoaded = loaded ?: return null
    val safeTotal = total ?: return null
    if (safeTotal <= 0) {
        return null
    }

    val boundedRatio = safeLoaded.coerceIn(0, safeTotal).toDouble() / safeTotal.toDouble()
    return (minPercent + ((maxPercent - minPercent) * boundedRatio).toInt()).coerceIn(minPercent, maxPercent)
}
