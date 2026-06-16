package com.jm.sillydroid.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserZoomOptionsTest {

    @Test
    fun `default browser zoom keeps native one hundred percent`() {
        // 字体缩放保留 50%-150%；界面密度只做 50%-100%，100 表示原始密度。
        assertEquals(100, BrowserZoomOptions.DEFAULT_PERCENT)
        assertEquals(100, BrowserZoomOptions.DEFAULT_TEXT_ZOOM_PERCENT)
    }

    @Test
    fun `browser zoom still sanitizes to five percent steps`() {
        assertEquals(50, BrowserZoomOptions.sanitize(1))
        assertEquals(90, BrowserZoomOptions.sanitize(88))
        assertEquals(150, BrowserZoomOptions.sanitize(999))
    }

    @Test
    fun `viewport density only supports showing more content up to one hundred percent`() {
        assertEquals(50, BrowserZoomOptions.sanitizeViewportDensity(1))
        assertEquals(90, BrowserZoomOptions.sanitizeViewportDensity(88))
        assertEquals(100, BrowserZoomOptions.sanitizeViewportDensity(125))
        assertEquals(100, BrowserZoomOptions.sanitizeViewportDensity(999))
        assertEquals(10, BrowserZoomOptions.viewportDensitySliderProgress(100))
        assertEquals(100, BrowserZoomOptions.viewportDensityPercentFromSliderProgress(10))
    }
}
