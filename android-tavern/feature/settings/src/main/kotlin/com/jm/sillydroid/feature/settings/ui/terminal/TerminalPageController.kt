package com.jm.sillydroid.feature.settings.ui.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.SettingsTab
import com.termux.view.TerminalView
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 终端页页面层只管理页签 attach/detach、状态文案和按钮分发。
 * 输入链路、选择态和底部快捷条都在这里汇总，避免 Activity / TerminalViewClient /
 * 额外 strip 各自维护一套终端状态而再次走散。
 */
class TerminalPageController(
    private val activity: AppCompatActivity,
    private val terminalPanelView: View,
    private val terminalView: TerminalView,
    private val statusView: TextView,
    private val retryButton: MaterialButton,
    private val ctrlCButton: MaterialButton,
    private val clearButton: MaterialButton,
    private val resetButton: MaterialButton,
    private val selectButton: MaterialButton,
    private val extraKeysStripView: TerminalExtraKeysStripView,
    private val sessionStore: HostConsoleSessionStore
) : DefaultLifecycleObserver {
    companion object {
        private const val logTag = "SettingsTerminal"
        private const val terminalFontSizeDp = 14
        private const val terminalCursorBlinkRateMillis = 500
        private val normalShortcutActions = listOf(
            TerminalExtraKeyAction.ESC,
            TerminalExtraKeyAction.TAB,
            TerminalExtraKeyAction.CTRL,
            TerminalExtraKeyAction.ALT,
            TerminalExtraKeyAction.LEFT,
            TerminalExtraKeyAction.DOWN,
            TerminalExtraKeyAction.UP,
            TerminalExtraKeyAction.RIGHT
        )
        private val selectionShortcutActions = listOf(
            TerminalExtraKeyAction.LEFT,
            TerminalExtraKeyAction.DOWN,
            TerminalExtraKeyAction.UP,
            TerminalExtraKeyAction.RIGHT,
            TerminalExtraKeyAction.COPY
        )
    }

    private var selectedTab: SettingsTab = SettingsTab.DATA
    private var imeVisible = false
    private var selectionModeActive = false
    private var extraKeysState = TerminalExtraKeysState()
    private var currentSessionState = HostConsoleSessionState()
    private val textSelectionBridge = TermuxTextSelectionBridge(terminalView)
    private var longPressMenuAnchorView: View? = null
    private val terminalViewClient = SettingsTerminalViewClient(
        activity = activity,
        terminalView = terminalView,
        isTerminalTabSelected = { selectedTab == SettingsTab.TERMINAL },
        readModifierState = { extraKeysState },
        onModifierStateConsumed = ::clearArmedModifiers,
        onSelectionModeChanged = ::onSelectionModeChanged,
        onLongPressRequested = ::showLongPressMenu
    )
    private val terminalSessionClient by lazy {
        SettingsTerminalSessionClient(terminalView)
    }

    fun initialize() {
        // Termux 的 TerminalView 在设置 cursor blinker rate 时会立刻走 mClient 日志输出；
        // 因此这里必须先绑定 TerminalViewClient，再做任何可能访问 mClient 的初始化调用，
        // 否则设置页一打开就会因为空 client 直接崩溃。
        terminalViewClient.initialize()
        terminalView.setTextSize(terminalFontSizeDp)
        terminalView.setTerminalCursorBlinkerRate(terminalCursorBlinkRateMillis)

        retryButton.setOnClickListener {
            initializeOrReconnect(resetSession = false)
        }
        ctrlCButton.setOnClickListener {
            sessionStore.currentSessionOrNull()?.sendControlCharacter('c')
            clearArmedModifiers()
        }
        clearButton.setOnClickListener {
            sessionStore.currentSessionOrNull()?.clearScreen()
            clearArmedModifiers()
        }
        resetButton.setOnClickListener {
            stopSelectionModeIfActive()
            initializeOrReconnect(resetSession = true)
        }
        selectButton.setOnClickListener {
            toggleSelectionMode()
        }
        extraKeysStripView.setOnActionPressedListener(::handleExtraKeyAction)

        ViewCompat.setOnApplyWindowInsetsListener(terminalPanelView) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            renderExtraKeysStrip()
            insets
        }
        ViewCompat.requestApplyInsets(terminalPanelView)

        activity.lifecycle.addObserver(this)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionStore.state.collect { state ->
                    currentSessionState = state
                    renderState(state)
                }
            }
        }
    }

    fun onTabChanged(tab: SettingsTab) {
        selectedTab = tab
        if (tab == SettingsTab.TERMINAL) {
            initializeOrReconnect(resetSession = false)
        } else {
            stopSelectionModeIfActive()
            detachCurrentTerminalView()
            terminalViewClient.detachFromTerminalPage()
            clearArmedModifiers()
            terminalView.setTerminalCursorBlinkerState(false, true)
        }
        renderExtraKeysStrip()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopSelectionModeIfActive()
        detachCurrentTerminalView()
        terminalViewClient.detachFromTerminalPage()
        terminalView.setTerminalCursorBlinkerState(false, true)
    }

    private fun initializeOrReconnect(resetSession: Boolean) {
        if (selectedTab != SettingsTab.TERMINAL) {
            return
        }

        activity.lifecycleScope.launch {
            val result = runCatching {
                if (resetSession) {
                    sessionStore.recreateSession()
                } else {
                    sessionStore.ensureSession()
                }
            }

            result.onSuccess { session ->
                if (selectedTab != SettingsTab.TERMINAL) {
                    return@onSuccess
                }

                session.attach(terminalView, terminalSessionClient)
                terminalView.onScreenUpdated()
                terminalViewClient.attachToVisibleTerminal(showKeyboard = false)
                terminalView.setTerminalCursorBlinkerState(true, true)
                renderExtraKeysStrip()
            }.onFailure { exception ->
                Log.e(logTag, "Failed to initialize terminal session.", exception)
            }
        }
    }

    private fun detachCurrentTerminalView() {
        sessionStore.currentSessionOrNull()?.detach()
    }

    private fun renderState(state: HostConsoleSessionState) {
        if (!state.isRunning) {
            stopSelectionModeIfActive()
        }

        statusView.text = buildStatusText(state)
        retryButton.isVisible = state.phase == HostConsolePhase.FAILED || state.phase == HostConsolePhase.EXITED
        ctrlCButton.isEnabled = state.isRunning
        clearButton.isEnabled = state.isRunning
        resetButton.isEnabled = state.phase != HostConsolePhase.PREPARING
        selectButton.isEnabled = state.isRunning || selectionModeActive
        selectButton.text = activity.getString(
            if (selectionModeActive) {
                R.string.bootstrap_settings_terminal_select_stop
            } else {
                R.string.bootstrap_settings_terminal_select_start
            }
        )
        terminalView.isVisible = state.phase != HostConsolePhase.FAILED
        renderExtraKeysStrip()
    }

    private fun buildStatusText(state: HostConsoleSessionState): String {
        return state.progressPercent?.let { progress ->
            "${state.statusMessage} ${progress}%"
        } ?: state.statusMessage
    }

    private fun renderExtraKeysStrip() {
        extraKeysStripView.isVisible = TerminalInteractionPolicy.shouldShowExtraKeys(
            selectedTab = selectedTab,
            imeVisible = imeVisible,
            selectionModeActive = selectionModeActive
        )
        extraKeysStripView.render(
            state = extraKeysState,
            actions = if (selectionModeActive) selectionShortcutActions else normalShortcutActions
        )
    }

    private fun handleExtraKeyAction(action: TerminalExtraKeyAction) {
        when (action) {
            TerminalExtraKeyAction.CTRL -> updateExtraKeysState(extraKeysState.toggleCtrl())
            TerminalExtraKeyAction.ALT -> updateExtraKeysState(extraKeysState.toggleAlt())
            TerminalExtraKeyAction.ESC -> sendSpecialKey(KeyEvent.KEYCODE_ESCAPE)
            TerminalExtraKeyAction.TAB -> sendSpecialKey(KeyEvent.KEYCODE_TAB)
            TerminalExtraKeyAction.LEFT -> handleDirectionalAction(-1, 0, KeyEvent.KEYCODE_DPAD_LEFT)
            TerminalExtraKeyAction.DOWN -> handleDirectionalAction(0, 1, KeyEvent.KEYCODE_DPAD_DOWN)
            TerminalExtraKeyAction.UP -> handleDirectionalAction(0, -1, KeyEvent.KEYCODE_DPAD_UP)
            TerminalExtraKeyAction.RIGHT -> handleDirectionalAction(1, 0, KeyEvent.KEYCODE_DPAD_RIGHT)
            TerminalExtraKeyAction.COPY -> copyCurrentSelection()
        }
    }

    private fun handleDirectionalAction(deltaColumn: Int, deltaRow: Int, keyCode: Int) {
        if (selectionModeActive) {
            textSelectionBridge.moveSelectionEnd(deltaColumn = deltaColumn, deltaRow = deltaRow)
            return
        }

        sendSpecialKey(keyCode)
    }

    private fun sendSpecialKey(keyCode: Int) {
        val session = sessionStore.currentSessionOrNull() ?: return
        session.sendKeyCode(keyCode, extraKeysState.currentKeyMod())
        clearArmedModifiers()
        terminalViewClient.refreshInputConnection()
    }

    private fun copyCurrentSelection() {
        if (!selectionModeActive) {
            return
        }

        textSelectionBridge.copySelection()
        clearArmedModifiers()
        terminalViewClient.refreshInputConnection()
    }

    private fun toggleSelectionMode() {
        if (!currentSessionState.isRunning) {
            return
        }

        if (selectionModeActive) {
            stopSelectionModeIfActive()
        } else {
            startSelectionModeFromButton()
        }
    }

    private fun startSelectionModeFromButton() {
        if (!textSelectionBridge.startSelectionFromCursor()) {
            return
        }
        clearArmedModifiers()
        renderExtraKeysStrip()
    }

    private fun startTextSelectionFromLongPress(event: MotionEvent) {
        if (!textSelectionBridge.startSelectionFromLongPress(event)) {
            return
        }
        clearArmedModifiers()
        renderExtraKeysStrip()
    }

    /**
     * 终端长按菜单必须直接复用当前真实 TerminalSession 的能力：
     * “开始选择”进入 Termux 选区，“粘贴”走 session clipboard 输入链路，
     * 不能退回页面层伪输入框或把粘贴塞进其他不直观的位置。
     */
    private fun showLongPressMenu(event: MotionEvent) {
        val actions = TerminalLongPressMenuPolicy.buildActions(
            isSessionRunning = currentSessionState.isRunning,
            selectionModeActive = selectionModeActive
        )
        if (actions.isEmpty()) {
            return
        }

        val anchorView = ensureLongPressMenuAnchorView()
        positionLongPressMenuAnchor(anchorView, event)

        PopupMenu(activity, anchorView).apply {
            actions.forEachIndexed { index, action ->
                menu.add(0, index, index, menuLabelRes(action))
            }

            setOnMenuItemClickListener { item ->
                actions.getOrNull(item.itemId)?.let { action ->
                    handleLongPressMenuAction(action, event)
                }
                true
            }
            setOnDismissListener {
                terminalView.post {
                    terminalView.requestFocus()
                    terminalViewClient.refreshInputConnection()
                }
            }
            show()
        }
    }

    private fun handleLongPressMenuAction(action: TerminalLongPressMenuAction, event: MotionEvent) {
        when (action) {
            TerminalLongPressMenuAction.START_SELECTION -> startTextSelectionFromLongPress(event)
            TerminalLongPressMenuAction.PASTE -> pasteFromClipboard()
        }
    }

    private fun pasteFromClipboard() {
        val session = sessionStore.currentSessionOrNull() ?: return
        session.pasteFromClipboard()
        clearArmedModifiers()
        terminalViewClient.refreshInputConnection()
    }

    private fun menuLabelRes(action: TerminalLongPressMenuAction): Int {
        return when (action) {
            TerminalLongPressMenuAction.START_SELECTION -> R.string.bootstrap_settings_terminal_select_start
            TerminalLongPressMenuAction.PASTE -> R.string.bootstrap_settings_terminal_paste
        }
    }

    private fun ensureLongPressMenuAnchorView(): View {
        longPressMenuAnchorView?.let { existingAnchor ->
            return existingAnchor
        }

        val container = terminalView.parent as? FrameLayout ?: return terminalView
        return View(activity).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(1, 1)
            container.addView(this, layoutParams)
            longPressMenuAnchorView = this
        }
    }

    private fun positionLongPressMenuAnchor(anchorView: View, event: MotionEvent) {
        if (anchorView === terminalView) {
            return
        }

        val container = terminalView.parent as? FrameLayout ?: return
        val layoutParams = (anchorView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(1, 1)
        layoutParams.leftMargin = container.paddingLeft + event.x.roundToInt()
        layoutParams.topMargin = container.paddingTop + event.y.roundToInt()
        anchorView.layoutParams = layoutParams
    }

    private fun stopSelectionModeIfActive() {
        if (!selectionModeActive && !terminalView.isSelectingText) {
            return
        }

        textSelectionBridge.stopSelection()
        onSelectionModeChanged(false)
    }

    private fun onSelectionModeChanged(copyMode: Boolean) {
        selectionModeActive = copyMode
        selectButton.text = activity.getString(
            if (copyMode) {
                R.string.bootstrap_settings_terminal_select_stop
            } else {
                R.string.bootstrap_settings_terminal_select_start
            }
        )
        renderExtraKeysStrip()
    }

    private fun updateExtraKeysState(state: TerminalExtraKeysState) {
        extraKeysState = state
        renderExtraKeysStrip()
    }

    private fun clearArmedModifiers() {
        updateExtraKeysState(extraKeysState.clear())
    }
}
