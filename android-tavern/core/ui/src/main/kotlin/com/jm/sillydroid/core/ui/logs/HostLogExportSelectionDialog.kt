package com.jm.sillydroid.core.ui.logs

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jm.sillydroid.core.model.logs.HostLogExportOption

data class HostLogExportSelectionDialogText(
    val title: String,
    val message: String,
    val sensitiveSuffix: String,
    val confirmLabel: String
)

/**
 * 导出前统一弹出日志类型勾选框，确保“是否包含敏感酒馆日志”由用户显式确认，而不是静默全量导出。
 */
fun showHostLogExportSelectionDialog(
    activity: AppCompatActivity,
    options: List<HostLogExportOption>,
    text: HostLogExportSelectionDialogText,
    onConfirmed: (Set<String>) -> Unit
) {
    if (options.isEmpty()) {
        return
    }

    val labels = options.map { option ->
        if (option.containsSensitiveContent) {
            "${option.displayName}${text.sensitiveSuffix}"
        } else {
            option.displayName
        }
    }.toTypedArray()
    val checkedItems = BooleanArray(options.size) { index -> options[index].selectedByDefault }

    fun selectedRelativePaths(): Set<String> {
        return buildSet {
            options.forEachIndexed { index, option ->
                if (checkedItems[index]) {
                    addAll(option.relativePaths)
                }
            }
        }
    }

    // 选择项现在会稳定展示完整类型列表；确认按钮必须跟“当前勾选后实际命中的文件”一致，
    // 避免用户只勾到了暂时还没有生成文件的类型时还能继续导出。
    fun hasSelectedFiles(): Boolean = selectedRelativePaths().isNotEmpty()
    // 这个弹窗的核心内容是多选列表；把说明文案拼到标题区，避免 message/list 同时占用内容区后
    // 在不同 Material 主题下出现“说明出来了，但选择项不显示”的渲染问题。
    val dialogTitle = buildString {
        append(text.title)
        if (text.message.isNotBlank()) {
            append('\n')
            append(text.message)
        }
    }

    var positiveButton: android.widget.Button? = null

    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(dialogTitle)
        .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
            checkedItems[which] = isChecked
            positiveButton?.isEnabled = hasSelectedFiles()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(text.confirmLabel, null)
        .create()

    // 确认按钮必须在 Dialog 第一次 show 前挂接，否则默认按钮逻辑只会关闭弹窗，两个日志导出入口都收不到确认范围。
    dialog.setOnShowListener {
        positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.isEnabled = hasSelectedFiles()
        positiveButton.setOnClickListener {
            val selectedPaths = selectedRelativePaths()
            if (selectedPaths.isEmpty()) {
                positiveButton.isEnabled = false
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirmed(selectedPaths)
        }
    }
    dialog.show()
}
