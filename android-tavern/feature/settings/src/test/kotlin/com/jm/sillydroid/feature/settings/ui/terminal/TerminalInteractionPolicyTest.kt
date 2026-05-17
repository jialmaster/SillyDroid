package com.jm.sillydroid.feature.settings.ui.terminal

import com.jm.sillydroid.feature.settings.model.SettingsTab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInteractionPolicyTest {

    @Test
    fun `terminal view always enforces char based input`() {
        assertTrue(TerminalInteractionPolicy.shouldEnforceCharBasedInput())
    }

    @Test
    fun `terminal view stays selected when terminal itself still has focus`() {
        assertTrue(TerminalInteractionPolicy.isTerminalViewSelected(isTerminalTabSelected = false, terminalHasFocus = true))
        assertTrue(TerminalInteractionPolicy.isTerminalViewSelected(isTerminalTabSelected = true, terminalHasFocus = false))
        assertFalse(TerminalInteractionPolicy.isTerminalViewSelected(isTerminalTabSelected = false, terminalHasFocus = false))
    }

    @Test
    fun `extra keys strip only shows when terminal tab is active and ime is visible`() {
        assertTrue(TerminalInteractionPolicy.shouldShowExtraKeys(SettingsTab.TERMINAL, imeVisible = true, selectionModeActive = false))
        assertTrue(TerminalInteractionPolicy.shouldShowExtraKeys(SettingsTab.TERMINAL, imeVisible = false, selectionModeActive = true))
        assertFalse(TerminalInteractionPolicy.shouldShowExtraKeys(SettingsTab.TERMINAL, imeVisible = false, selectionModeActive = false))
        assertFalse(TerminalInteractionPolicy.shouldShowExtraKeys(SettingsTab.LOGS, imeVisible = true, selectionModeActive = true))
    }
}
