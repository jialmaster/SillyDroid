package com.jm.sillydroid.feature.settings.ui.terminal

/**
 * 终端长按菜单只暴露真实终端当前支持的动作。
 * 这里单独收敛菜单策略，避免页面层以后再次把“长按菜单”和“选择态快捷键”混成两套分叉逻辑。
 */
internal object TerminalLongPressMenuPolicy {
    fun buildActions(
        isSessionRunning: Boolean,
        selectionModeActive: Boolean
    ): List<TerminalLongPressMenuAction> {
        if (!isSessionRunning || selectionModeActive) {
            return emptyList()
        }

        return listOf(
            TerminalLongPressMenuAction.START_SELECTION,
            TerminalLongPressMenuAction.PASTE
        )
    }
}

internal enum class TerminalLongPressMenuAction {
    START_SELECTION,
    PASTE
}
