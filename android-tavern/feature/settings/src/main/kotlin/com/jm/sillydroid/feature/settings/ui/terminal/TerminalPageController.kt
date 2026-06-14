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
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.SettingsTab
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
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
    private val settingsButton: MaterialButton,
    private val extraKeysStripView: TerminalExtraKeysStripView,
    private val hostPreferencesRepository: HostPreferencesRepository,
    private val sessionStore: HostConsoleSessionStore
) : DefaultLifecycleObserver {
    companion object {
        private const val logTag = "SettingsTerminal"
        private const val terminalCursorBlinkRateMillis = 500
        private const val terminalSizeSyncSecondPassDelayMillis = 48L
        private const val terminalSizeSyncFinalPassDelayMillis = 220L
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
    private val terminalLayoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
        if (selectedTab == SettingsTab.TERMINAL && sizeChanged) {
            scheduleTerminalSizeSync()
        }
    }
    private val terminalSettingsDialogController = TerminalSettingsDialogController(
        activity = activity,
        hostPreferencesRepository = hostPreferencesRepository,
        onTerminalFontSizeChanged = ::applyTerminalFontSize,
        onTerminalCursorBlinkChanged = ::applyTerminalCursorBlinkSettings,
        onTerminalExtraKeysChanged = { renderExtraKeysStrip() }
    )
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
        SettingsTerminalSessionClient(
            terminalView = terminalView,
            isCursorBlinkEnabled = { hostPreferencesRepository.terminalCursorBlinkEnabled }
        )
    }

    fun initialize() {
        // Termux 的 TerminalView 在设置 cursor blinker rate 时会立刻走 mClient 日志输出；
        // 因此这里必须先绑定 TerminalViewClient，再做任何可能访问 mClient 的初始化调用，
        // 否则设置页一打开就会因为空 client 直接崩溃。
        terminalViewClient.initialize()
        applyTerminalThemeColors()
        applyTerminalFontSize(hostPreferencesRepository.terminalFontSizePx)
        applyTerminalCursorBlinkSettings(hostPreferencesRepository.terminalCursorBlinkEnabled)

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
        settingsButton.setOnClickListener {
            terminalSettingsDialogController.show()
        }
        extraKeysStripView.setOnActionPressedListener(::handleExtraKeyAction)
        terminalView.addOnLayoutChangeListener(terminalLayoutChangeListener)
        terminalPanelView.addOnLayoutChangeListener(terminalLayoutChangeListener)

        ViewCompat.setOnApplyWindowInsetsListener(terminalPanelView) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            renderExtraKeysStrip()
            if (selectedTab == SettingsTab.TERMINAL) {
                scheduleTerminalSizeSync()
            }
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
        terminalView.removeOnLayoutChangeListener(terminalLayoutChangeListener)
        terminalPanelView.removeOnLayoutChangeListener(terminalLayoutChangeListener)
        terminalView.setTerminalCursorBlinkerState(false, true)
    }

    private fun initializeOrReconnect(resetSession: Boolean) {
        if (selectedTab != SettingsTab.TERMINAL) {
            return
        }

        activity.lifecycleScope.launch {
            awaitTerminalSizeReady()
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
                syncTerminalSizeNow()
                applyTerminalThemeColors()
                terminalView.onScreenUpdated()
                scheduleTerminalSizeSync()
                terminalViewClient.attachToVisibleTerminal(showKeyboard = false)
                applyTerminalCursorBlinkSettings(hostPreferencesRepository.terminalCursorBlinkEnabled)
                renderExtraKeysStrip()
            }.onFailure { exception ->
                Log.e(logTag, "Failed to initialize terminal session.", exception)
            }
        }
    }

    /**
     * TerminalSession 会在 TerminalView.attachSession() 的第一次 updateSize() 里启动真实 pty。
     * 必须等 TerminalView 已经拿到最终宽高后再 attach，否则 shell 的首个 prompt 和 readline
     * 会按错误列宽渲染，表现成长路径/长命令只露出最后一段而不是自动折行。
     */
    private suspend fun awaitTerminalSizeReady() {
        if (isTerminalSizeReady()) {
            return
        }

        suspendCancellableCoroutine { continuation ->
            var layoutListener: View.OnLayoutChangeListener? = null

            fun detachListener() {
                layoutListener?.let { listener ->
                    terminalView.removeOnLayoutChangeListener(listener)
                }
                layoutListener = null
            }

            fun resumeIfReady() {
                if (!continuation.isActive || !isTerminalSizeReady()) {
                    return
                }
                detachListener()
                continuation.resume(Unit)
            }

            layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                resumeIfReady()
            }
            terminalView.addOnLayoutChangeListener(layoutListener)
            terminalView.post {
                resumeIfReady()
            }
            continuation.invokeOnCancellation {
                detachListener()
            }
        }
    }

    private fun isTerminalSizeReady(): Boolean {
        return terminalView.isAttachedToWindow && terminalView.width > 0 && terminalView.height > 0
    }

    // 终端字号修改后需要立即回写当前 TerminalView，
    // 这样用户在终端设置里拖动字号时，能直接看到真实宿主终端的渲染变化。
    private fun applyTerminalFontSize(fontSizePx: Int) {
        terminalView.setTextSize(fontSizePx)
        scheduleTerminalSizeSync()
        terminalView.onScreenUpdated()
    }

    /**
     * TerminalView 只有在最终布局尺寸落定后，才会把列数回写给 pty。
     * 页签切换、首次 attach、IME 动画和字体变化会跨多帧改变可视高度；
     * 这里分几次同步，避免长粘贴按 0 宽或旧列宽渲染成只露尾部的横向滚动行。
     */
    private fun scheduleTerminalSizeSync() {
        terminalView.doOnLayout {
            syncTerminalSizeNow()
        }
        terminalView.post {
            syncTerminalSizeNow()
        }
        terminalView.postDelayed({
            syncTerminalSizeNow()
        }, terminalSizeSyncSecondPassDelayMillis)
        terminalView.postDelayed({
            syncTerminalSizeNow()
        }, terminalSizeSyncFinalPassDelayMillis)
    }

    private fun syncTerminalSizeNow() {
        if (!terminalView.isAttachedToWindow || terminalView.width <= 0 || terminalView.height <= 0) {
            return
        }
        terminalView.updateSize()
        terminalView.onScreenUpdated()
        terminalView.invalidate()
    }

    /**
     * 设置页终端在浅色主题下不能继续吃 Termux 默认的深色终端色表，
     * 否则会出现浅色面板上仍是白色默认前景、用户几乎看不见输出。
     * 这里只同步“默认前景/背景/光标”三个基础色，保留 ANSI 颜色语义本身不被宿主改写。
     */
    private fun applyTerminalThemeColors() {
        val backgroundColor = resolveThemeColor(MaterialR.attr.colorSurfaceContainerLow)
        val foregroundColor = resolveThemeColor(MaterialR.attr.colorOnSurface)
        val cursorColor = resolveThemeColor(MaterialR.attr.colorPrimary)
        terminalView.setBackgroundColor(backgroundColor)
        terminalView.currentSession?.emulator?.mColors?.mCurrentColors?.let { colors ->
            colors[TextStyle.COLOR_INDEX_BACKGROUND] = backgroundColor
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = foregroundColor
            colors[TextStyle.COLOR_INDEX_CURSOR] = cursorColor
        }
        terminalView.invalidate()
    }

    // 光标闪烁配置需要同时控制 blinker rate 和当前可见态；
    // 关闭闪烁时仍保留静态光标，避免终端看起来像失焦或无法输入。
    private fun applyTerminalCursorBlinkSettings(enabled: Boolean) {
        terminalView.setTerminalCursorBlinkerRate(
            if (enabled) terminalCursorBlinkRateMillis else 0
        )
        if (selectedTab == SettingsTab.TERMINAL && currentSessionState.isRunning) {
            terminalView.setTerminalCursorBlinkerState(true, true)
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
        resetButton.isEnabled = state.phase != HostConsolePhase.STARTING
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
            selectionModeActive = selectionModeActive,
            extraKeysEnabled = hostPreferencesRepository.terminalExtraKeysEnabled
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
        syncTerminalSizeNow()
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

    private fun resolveThemeColor(attrRes: Int, fallback: Int = 0): Int {
        return MaterialColors.getColor(activity, attrRes, fallback)
    }
}
