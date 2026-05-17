package com.jm.sillydroid.feature.settings.ui.terminal

import com.termux.terminal.KeyHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExtraKeysStateTest {

    @Test
    fun `toggleCtrl arms ctrl and clears alt`() {
        val modifiers = TerminalExtraKeysState(altArmed = true).toggleCtrl()

        assertTrue(modifiers.ctrlArmed)
        assertFalse(modifiers.altArmed)
        assertEquals(KeyHandler.KEYMOD_CTRL, modifiers.currentKeyMod())
    }

    @Test
    fun `toggleAlt arms alt and clears ctrl`() {
        val modifiers = TerminalExtraKeysState(ctrlArmed = true).toggleAlt()

        assertFalse(modifiers.ctrlArmed)
        assertTrue(modifiers.altArmed)
        assertEquals(KeyHandler.KEYMOD_ALT, modifiers.currentKeyMod())
    }

    @Test
    fun `toggling armed modifier again clears one shot state`() {
        val modifiers = TerminalExtraKeysState(ctrlArmed = true).toggleCtrl()

        assertFalse(modifiers.hasArmedModifier)
        assertEquals(0, modifiers.currentKeyMod())
    }

    @Test
    fun `clear resets all armed modifiers`() {
        val modifiers = TerminalExtraKeysState(ctrlArmed = true, altArmed = true).clear()

        assertFalse(modifiers.ctrlArmed)
        assertFalse(modifiers.altArmed)
        assertFalse(modifiers.hasArmedModifier)
        assertEquals(0, modifiers.currentKeyMod())
    }
}
