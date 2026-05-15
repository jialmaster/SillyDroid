package com.jm.sillydroid.feature.settings.ui.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionProgressControllerTest {

    @Test
    fun `scaleProgress returns null when loaded missing`() {
        assertNull(scaleProgress(null, 100, 0, 100))
    }

    @Test
    fun `scaleProgress returns null when total missing`() {
        assertNull(scaleProgress(50, null, 0, 100))
    }

    @Test
    fun `scaleProgress returns null when total zero or negative`() {
        assertNull(scaleProgress(50, 0, 0, 100))
        assertNull(scaleProgress(50, -5, 0, 100))
    }

    @Test
    fun `scaleProgress maps zero loaded to min`() {
        assertEquals(6, scaleProgress(0, 100, 6, 82))
    }

    @Test
    fun `scaleProgress maps full loaded to max`() {
        assertEquals(82, scaleProgress(100, 100, 6, 82))
    }

    @Test
    fun `scaleProgress maps half loaded to midpoint`() {
        // midpoint between 6 and 82 = 6 + 76*0.5 = 44
        assertEquals(44, scaleProgress(50, 100, 6, 82))
    }

    @Test
    fun `scaleProgress clamps loaded above total`() {
        assertEquals(82, scaleProgress(150, 100, 6, 82))
    }

    @Test
    fun `scaleProgress clamps loaded below zero`() {
        assertEquals(6, scaleProgress(-10, 100, 6, 82))
    }

    @Test
    fun `scaleProgress receiving objects band`() {
        assertEquals(82, scaleProgress(100, 100, 6, 82))
    }

    @Test
    fun `scaleProgress resolving deltas band stays inside 82-94`() {
        val mid = scaleProgress(50, 100, 82, 94)
        assertEquals(88, mid)
    }
}
