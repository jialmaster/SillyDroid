package com.jm.sillydroid.feature.settings.ui.terminal

import com.jm.sillydroid.feature.settings.model.SettingsTab

/**
 * 终端页的 IME / 焦点判定需要固定成一套可测试的策略，
 * 这样页面层、TerminalViewClient 和 extra keys 显隐就不会各自散落不同判断。
 */
internal object TerminalInteractionPolicy {
    fun shouldEnforceCharBasedInput(): Boolean = true

    fun isTerminalViewSelected(isTerminalTabSelected: Boolean, terminalHasFocus: Boolean): Boolean {
        return isTerminalTabSelected || terminalHasFocus
    }

    fun shouldShowExtraKeys(
        selectedTab: SettingsTab,
        imeVisible: Boolean,
        selectionModeActive: Boolean,
        extraKeysEnabled: Boolean
    ): Boolean {
        return selectedTab == SettingsTab.TERMINAL &&
            (selectionModeActive || (extraKeysEnabled && imeVisible))
    }
}
