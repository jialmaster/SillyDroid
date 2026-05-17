package com.jm.sillydroid.feature.settings.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLongPressMenuPolicyTest {

    @Test
    fun `long press menu exposes selection and paste when session is running`() {
        assertEquals(
            listOf(
                TerminalLongPressMenuAction.START_SELECTION,
                TerminalLongPressMenuAction.PASTE
            ),
            TerminalLongPressMenuPolicy.buildActions(
                isSessionRunning = true,
                selectionModeActive = false
            )
        )
    }

    @Test
    fun `long press menu hides actions while selection mode is active`() {
        assertTrue(
            TerminalLongPressMenuPolicy.buildActions(
                isSessionRunning = true,
                selectionModeActive = true
            ).isEmpty()
        )
    }

    @Test
    fun `long press menu hides actions before terminal session is ready`() {
        assertTrue(
            TerminalLongPressMenuPolicy.buildActions(
                isSessionRunning = false,
                selectionModeActive = false
            ).isEmpty()
        )
    }
}
