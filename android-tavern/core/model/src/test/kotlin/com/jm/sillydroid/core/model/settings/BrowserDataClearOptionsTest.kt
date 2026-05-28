package com.jm.sillydroid.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDataClearOptionsTest {

    @Test
    fun `default mask only selects resource cache`() {
        val defaultMask = BrowserDataClearOptions.defaultMask

        assertTrue(BrowserDataClearOptions.contains(defaultMask, BrowserDataClearTarget.RESOURCE_CACHE))
        assertFalse(BrowserDataClearOptions.contains(defaultMask, BrowserDataClearTarget.SITE_STORAGE))
        assertFalse(BrowserDataClearOptions.contains(defaultMask, BrowserDataClearTarget.COOKIES))
        assertFalse(BrowserDataClearOptions.contains(defaultMask, BrowserDataClearTarget.HISTORY_AND_FORM_DATA))
    }

    @Test
    fun `normalize strips unknown bits`() {
        val mask = BrowserDataClearTarget.RESOURCE_CACHE.mask or (1 shl 9)

        assertEquals(BrowserDataClearTarget.RESOURCE_CACHE.mask, BrowserDataClearOptions.normalize(mask))
    }
}
