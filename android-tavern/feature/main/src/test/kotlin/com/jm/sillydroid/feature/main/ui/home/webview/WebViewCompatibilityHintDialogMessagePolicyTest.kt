package com.jm.sillydroid.feature.main.ui.home.webview

import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证兼容性提示文本不会在长按时弹出系统选择菜单。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewCompatibilityHintDialogMessagePolicyTest {

    /** 提示文本必须关闭选择、长按和触觉反馈。 */
    @Test
    fun `harden disables selection and long click for compatibility hint message`() {
        val messageView = TextView(RuntimeEnvironment.getApplication()).apply {
            text = "Chromium 90"
            setTextIsSelectable(true)
            isLongClickable = true
            isHapticFeedbackEnabled = true
        }

        hardenCompatibilityHintDialogMessage(messageView)

        assertFalse(messageView.isTextSelectable)
        assertFalse(messageView.isLongClickable)
        assertFalse(messageView.isHapticFeedbackEnabled)
    }

    /** 对话框缺失 message View 时策略应安全忽略。 */
    @Test
    fun `harden ignores missing dialog message view`() {
        hardenCompatibilityHintDialogMessage(messageView = null)
    }
}
