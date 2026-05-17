package com.jm.sillydroid.feature.settings.ui.terminal

import android.os.SystemClock
import android.view.ActionMode
import android.view.MotionEvent
import com.termux.view.TerminalView
import com.termux.view.textselection.TextSelectionCursorController
import com.termux.view.textselection.TextSelectionHandleView

/**
 * terminal-view 0.118.0 公开了“进入选择模式”的入口，但没有直接给页面层暴露
 * “复制当前选区”以及“按钮式移动选区句柄”的 API。
 * 这里集中桥接 Termux 自己的选择控制器，保证设置页的复制体验仍然落在真实终端选区上，
 * 而不是退回成伪输入框或假高亮。
 */
internal class TermuxTextSelectionBridge(
    private val terminalView: TerminalView
) {
    private val selectorsBuffer = IntArray(4)

    fun startSelectionFromCursor(): Boolean {
        val anchorEvent = buildSelectionAnchorEvent() ?: return false
        return try {
            terminalView.startTextSelectionMode(anchorEvent)
            dismissSelectionActionMode()
            terminalView.isSelectingText
        } finally {
            anchorEvent.recycle()
        }
    }

    fun startSelectionFromLongPress(event: MotionEvent): Boolean {
        val eventCopy = MotionEvent.obtain(event)
        return try {
            terminalView.startTextSelectionMode(eventCopy)
            dismissSelectionActionMode()
            terminalView.isSelectingText
        } finally {
            eventCopy.recycle()
        }
    }

    fun stopSelection() {
        if (terminalView.isSelectingText) {
            terminalView.stopTextSelectionMode()
        }
    }

    fun copySelection(): Boolean {
        val controller = getCursorController() ?: return false
        val session = terminalView.currentSession ?: return false
        val emulator = session.emulator ?: return false
        controller.getSelectors(selectorsBuffer)
        val selectedText = emulator.getSelectedText(
            selectorsBuffer[0],
            selectorsBuffer[1],
            selectorsBuffer[2],
            selectorsBuffer[3]
        ).takeIf { it.isNotEmpty() } ?: return false
        session.onCopyTextToClipboard(selectedText)
        stopSelection()
        return true
    }

    /**
     * 进入选择态后，方向键按钮只移动 end handle。
     * start handle 保持在最初锚点，用户就能像移动端编辑器一样快速扩展/收缩当前选区。
     */
    fun moveSelectionEnd(deltaColumn: Int = 0, deltaRow: Int = 0): Boolean {
        val controller = getCursorController() ?: return false
        val session = terminalView.currentSession ?: return false
        val emulator = session.emulator ?: return false
        val endHandle = getHandle(controller, "mEndHandle") ?: return false

        controller.getSelectors(selectorsBuffer)
        val targetColumn = (selectorsBuffer[3] + deltaColumn).coerceIn(0, (emulator.mColumns - 1).coerceAtLeast(0))
        val targetRow = selectorsBuffer[1] + deltaRow
        val targetX = terminalView.getPointX(targetColumn)
        val targetY = terminalView.getPointY(targetRow) + selectionHandleTouchVerticalOffsetPx
        controller.updatePosition(endHandle, targetX, targetY)
        terminalView.onScreenUpdated()
        return true
    }

    private fun buildSelectionAnchorEvent(): MotionEvent? {
        val session = terminalView.currentSession ?: return null
        val emulator = session.emulator
        val anchorX = emulator?.getCursorCol()?.let { column ->
            terminalView.getPointX(column).toFloat()
        } ?: (terminalView.width / 2f)
        val anchorY = emulator?.getCursorRow()?.let { row ->
            terminalView.getPointY(row).toFloat() + selectionHandleTouchVerticalOffsetPx
        } ?: (terminalView.height / 2f)
        val eventTime = SystemClock.uptimeMillis()
        return MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, anchorX, anchorY, 0)
    }

    private fun getCursorController(): TextSelectionCursorController? {
        val method = TerminalView::class.java.getDeclaredMethod("getTextSelectionCursorController")
        method.isAccessible = true
        return method.invoke(terminalView) as? TextSelectionCursorController
    }

    /**
     * 设置页自己的复制/方向键已经接管了终端选择态操作，
     * 因此要主动关闭 terminal-view 默认弹出的 ActionMode 菜单，避免顶部再挡一层 copy/paste/more。
     * 这里只摘掉菜单对象，不结束选区句柄，保证仍然是 Termux 的真实文本选区。
     */
    private fun dismissSelectionActionMode() {
        val controller = getCursorController() ?: return
        val actionModeField = TextSelectionCursorController::class.java.getDeclaredField("mActionMode")
        actionModeField.isAccessible = true
        val actionMode = actionModeField.get(controller) as? ActionMode ?: return
        actionMode.finish()
        actionModeField.set(controller, null)
    }

    private fun getHandle(
        controller: TextSelectionCursorController,
        fieldName: String
    ): TextSelectionHandleView? {
        val field = TextSelectionCursorController::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(controller) as? TextSelectionHandleView
    }

    private companion object {
        const val selectionHandleTouchVerticalOffsetPx = 40
    }
}
