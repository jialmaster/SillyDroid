package com.jm.sillydroid.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalFontSizeOptionsTest {
    @Test
    fun `sanitize keeps value inside supported terminal font range`() {
        assertEquals(TerminalFontSizeOptions.MIN_PX, TerminalFontSizeOptions.sanitize(1))
        assertEquals(18, TerminalFontSizeOptions.sanitize(18))
        assertEquals(TerminalFontSizeOptions.MAX_PX, TerminalFontSizeOptions.sanitize(99))
    }
}
