package com.jm.sillydroid.feature.settings.ui.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 终端输入链路直接按 Termux 的 TerminalViewClient 模式收敛：
 * 焦点、InputConnection 刷新和延迟唤起 IME 全放在这一层，页面层不再自己拼零散补丁。
 */
internal class SettingsTerminalViewClient(
    private val activity: AppCompatActivity,
    private val terminalView: TerminalView,
    private val isTerminalTabSelected: () -> Boolean,
    private val readModifierState: () -> TerminalExtraKeysState,
    private val onModifierStateConsumed: () -> Unit,
    private val onSelectionModeChanged: (Boolean) -> Unit,
    private val onLongPressRequested: (MotionEvent) -> Unit
) : TerminalViewClient {
    companion object {
        private const val terminalKeyboardShowDelayMillis = 300L
    }

    private var keyboardShowRequested = false
    private val showKeyboardRunnable = Runnable {
        showSoftKeyboardNow()
    }

    fun initialize() {
        terminalView.setTerminalViewClient(this)
        terminalView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!isTerminalTabSelected()) {
                return@OnFocusChangeListener
            }

            if (hasFocus) {
                refreshInputConnection()
                showKeyboardIfRequested()
            } else {
                hideSoftKeyboard()
            }
        }
    }

    /**
     * 终端重新附着或重新切回当前页签时必须刷新 InputConnection，
     * 否则很多中文输入法会继续持有旧连接，表现成“键盘弹了但字符进不去终端”。
     */
    fun attachToVisibleTerminal(showKeyboard: Boolean) {
        terminalView.post {
            keyboardShowRequested = showKeyboard
            terminalView.requestFocus()
            refreshInputConnection()
            if (showKeyboard) {
                showKeyboardIfRequested()
            }
        }
    }

    fun detachFromTerminalPage() {
        keyboardShowRequested = false
        terminalView.removeCallbacks(showKeyboardRunnable)
        hideSoftKeyboard()
    }

    fun refreshInputConnection() {
        inputMethodManager()?.restartInput(terminalView)
    }

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent) {
        attachToVisibleTerminal(showKeyboard = true)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = TerminalInteractionPolicy.shouldEnforceCharBasedInput()

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean {
        return TerminalInteractionPolicy.isTerminalViewSelected(
            isTerminalTabSelected = isTerminalTabSelected(),
            terminalHasFocus = terminalView.hasFocus()
        )
    }

    override fun copyModeChanged(copyMode: Boolean) {
        onSelectionModeChanged(copyMode)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        releaseOneShotModifiersIfNeeded()
        return false
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        if (!isTerminalViewSelected()) {
            return false
        }

        if (terminalView.isSelectingText) {
            return false
        }

        // 长按必须先走终端专用菜单，给“开始选择 / 粘贴”统一入口；
        // 不能再像之前那样直接切选择态，否则粘贴功能会完全消失。
        onLongPressRequested(event)
        return true
    }

    override fun readControlKey(): Boolean = readModifierState().ctrlArmed

    override fun readAltKey(): Boolean = readModifierState().altArmed

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        releaseOneShotModifiersIfNeeded()
        return false
    }

    override fun onEmulatorSet() {
        terminalView.setTerminalCursorBlinkerState(true, true)
    }

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

    private fun releaseOneShotModifiersIfNeeded() {
        if (readModifierState().hasArmedModifier) {
            onModifierStateConsumed()
        }
    }

    /**
     * IME 的唤起必须延迟到 focus/layout 周期之后，和 Termux 的处理保持一致，
     * 否则很多输入法会显示键盘但不给 TerminalView 建正确的 InputConnection。
     */
    private fun showKeyboardIfRequested() {
        if (!keyboardShowRequested) {
            return
        }

        terminalView.removeCallbacks(showKeyboardRunnable)
        terminalView.postDelayed(showKeyboardRunnable, terminalKeyboardShowDelayMillis)
    }

    private fun showSoftKeyboardNow() {
        val inputMethodManager = inputMethodManager() ?: return
        inputMethodManager.restartInput(terminalView)
        inputMethodManager.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSoftKeyboard() {
        val inputMethodManager = inputMethodManager() ?: return
        inputMethodManager.hideSoftInputFromWindow(terminalView.windowToken, 0)
    }

    private fun inputMethodManager(): InputMethodManager? {
        return ContextCompat.getSystemService(activity, InputMethodManager::class.java)
    }
}
