package com.jm.sillydroid.feature.main.ui.home.webview

import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** 将兼容性提示对话框的 message View 交给统一长按防护策略。 */
internal fun hardenCompatibilityHintDialogMessage(dialog: AlertDialog) {
    hardenCompatibilityHintDialogMessage(dialog.findViewById(android.R.id.message))
}

/** 禁止只读提示文本进入选择、长按和拖拽路径；空 View 安全忽略。 */
internal fun hardenCompatibilityHintDialogMessage(messageView: TextView?) {
    if (messageView == null) {
        return
    }

    // 某些旧 WebView 设备长按兼容性提示弹窗文案时，Framework Editor 会继续走拖拽阴影路径，
    // 并因为异常尺寸直接抛 IllegalStateException；这些提示文案不需要选择/拖拽能力。
    messageView.setTextIsSelectable(false)
    // setOnLongClickListener 会自动把 longClickable 重新打开，必须在注册吞掉回调后最后关闭。
    messageView.setOnLongClickListener { true }
    messageView.isLongClickable = false
    messageView.isHapticFeedbackEnabled = false
}
