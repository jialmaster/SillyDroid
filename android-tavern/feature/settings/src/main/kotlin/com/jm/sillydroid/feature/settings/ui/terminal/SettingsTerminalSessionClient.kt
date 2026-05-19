package com.jm.sillydroid.feature.settings.ui.terminal

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * 终端页自己的 session client 只负责把 TerminalSession 的屏幕、颜色和光标更新投递回当前 TerminalView。
 * 它不管理全局 shell 生命周期，避免页面销毁后把进程级会话一起带走。
 */
internal class SettingsTerminalSessionClient(
    private val terminalView: TerminalView,
    private val isCursorBlinkEnabled: () -> Boolean
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        postToTerminalView {
            terminalView.onScreenUpdated()
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        postToTerminalView {
            terminalView.onScreenUpdated()
        }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        postToTerminalView {
            terminalView.onScreenUpdated()
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
    }

    override fun onBell(session: TerminalSession) {
    }

    override fun onColorsChanged(session: TerminalSession) {
        postToTerminalView {
            terminalView.invalidate()
        }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        postToTerminalView {
            terminalView.setTerminalCursorBlinkerState(
                if (isCursorBlinkEnabled()) state else true,
                true
            )
        }
    }

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, e.message, e)
    }

    private fun postToTerminalView(block: () -> Unit) {
        terminalView.post {
            block()
        }
    }
}
