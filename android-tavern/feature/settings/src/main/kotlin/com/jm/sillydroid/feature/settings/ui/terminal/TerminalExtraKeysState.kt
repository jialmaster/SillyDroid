package com.jm.sillydroid.feature.settings.ui.terminal

import com.termux.terminal.KeyHandler

/**
 * 终端 extra keys 的 Ctrl/Alt 必须保持一次性修饰键语义：
 * 只影响下一次发送到 pty 的按键，随后立即释放，避免移动端连续输入时把修饰状态卡死。
 */
internal data class TerminalExtraKeysState(
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false
) {
    val hasArmedModifier: Boolean
        get() = ctrlArmed || altArmed

    fun toggleCtrl(): TerminalExtraKeysState {
        return if (ctrlArmed) {
            clear()
        } else {
            copy(ctrlArmed = true, altArmed = false)
        }
    }

    fun toggleAlt(): TerminalExtraKeysState {
        return if (altArmed) {
            clear()
        } else {
            copy(ctrlArmed = false, altArmed = true)
        }
    }

    fun clear(): TerminalExtraKeysState = TerminalExtraKeysState()

    fun currentKeyMod(): Int {
        var keyMod = 0
        if (ctrlArmed) {
            keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        }
        if (altArmed) {
            keyMod = keyMod or KeyHandler.KEYMOD_ALT
        }
        return keyMod
    }
}

internal enum class TerminalExtraKeyAction {
    ESC,
    TAB,
    CTRL,
    ALT,
    LEFT,
    DOWN,
    UP,
    RIGHT,
    COPY
}
