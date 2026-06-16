package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWebViewControllerTest {

    @Test
    fun `renderer priority diagnostic names are stable`() {
        assertEquals(
            "RENDERER_PRIORITY_IMPORTANT",
            resolveWebViewRendererPriorityName(WebView.RENDERER_PRIORITY_IMPORTANT)
        )
        assertEquals(
            "RENDERER_PRIORITY_BOUND",
            resolveWebViewRendererPriorityName(WebView.RENDERER_PRIORITY_BOUND)
        )
        assertEquals(
            "RENDERER_PRIORITY_WAIVED",
            resolveWebViewRendererPriorityName(WebView.RENDERER_PRIORITY_WAIVED)
        )
        assertEquals("UNKNOWN", resolveWebViewRendererPriorityName(Int.MIN_VALUE))
    }

    @Test
    fun `web settings cache mode diagnostic names are stable`() {
        assertEquals("LOAD_DEFAULT", resolveWebSettingsCacheModeName(WebSettings.LOAD_DEFAULT))
        assertEquals(
            "LOAD_CACHE_ELSE_NETWORK",
            resolveWebSettingsCacheModeName(WebSettings.LOAD_CACHE_ELSE_NETWORK)
        )
        assertEquals("LOAD_NO_CACHE", resolveWebSettingsCacheModeName(WebSettings.LOAD_NO_CACHE))
        assertEquals("LOAD_CACHE_ONLY", resolveWebSettingsCacheModeName(WebSettings.LOAD_CACHE_ONLY))
        assertEquals("UNKNOWN", resolveWebSettingsCacheModeName(Int.MIN_VALUE))
    }

    @Test
    fun `viewport density script shows more content without bitmap scaling`() {
        val script = buildApplyWebViewViewportDensityScript(
            percent = 50,
            baseViewportWidthCssPx = 400,
            reason = "unit_test"
        )

        assertTrue(script.contains("const viewportWidth = 800"))
        assertTrue(script.contains("const initialScale = 0.5"))
        assertTrue(script.contains("document.querySelector('meta[name=\"viewport\"]')"))
        assertTrue(script.contains("'width=' + viewportWidth"))
        assertTrue(script.contains("initial-scale="))
        assertTrue(script.contains("initialScale"))
        assertTrue(script.contains("root.dataset.sillydroidViewportDensityPercent = String(percent)"))
        assertTrue(script.contains("root.dataset.sillydroidViewportDensityReason = \"unit_test\""))
        assertFalse(script.contains("body.style.transform = 'scale(' + factor + ')'"))
        assertFalse(script.contains("initialScale = percent > 100 ? factor : 1"))
        assertFalse(script.contains("style.zoom"))
    }

    @Test
    fun `viewport density width only expands layout viewport below one hundred`() {
        assertEquals(800, resolveViewportDensityWidthCssPx(percent = 50, baseViewportWidthCssPx = 400))
        assertEquals(533, resolveViewportDensityWidthCssPx(percent = 75, baseViewportWidthCssPx = 400))
        assertEquals(400, resolveViewportDensityWidthCssPx(percent = 100, baseViewportWidthCssPx = 400))
        assertEquals(400, resolveViewportDensityWidthCssPx(percent = 125, baseViewportWidthCssPx = 400))
        assertEquals("0.5", resolveViewportDensityInitialScale(percent = 50))
        assertEquals("0.75", resolveViewportDensityInitialScale(percent = 75))
        assertEquals("1", resolveViewportDensityInitialScale(percent = 100))
        assertEquals("1", resolveViewportDensityInitialScale(percent = 150))
    }

    @Test
    fun `local load error info keeps webview network failure evidence`() {
        val info = WebViewLocalLoadErrorInfo(
            failingUrl = "http://127.0.0.1:8000/",
            method = "GET",
            errorCode = -6,
            description = "net::ERR_CONNECTION_REFUSED"
        )

        assertEquals("http://127.0.0.1:8000/", info.failingUrl)
        assertEquals("GET", info.method)
        assertEquals(-6, info.errorCode)
        assertEquals("net::ERR_CONNECTION_REFUSED", info.description)
    }

    @Test
    fun `renderer cleanup while activity is finishing is not auto uploaded as crash`() {
        val info = WebViewRendererGoneInfo(didCrash = false, rendererPriorityAtExit = null)

        assertFalse(
            shouldAutoUploadRendererGoneBundle(
                info = info,
                activityFinishing = true,
                activityDestroyed = false
            )
        )
        assertEquals(
            null,
            resolveRendererGoneAutoUploadCrashType(
                info = info,
                activityFinishing = true,
                activityDestroyed = false
            )
        )
    }

    @Test
    fun `renderer crash is auto uploaded even if activity is finishing`() {
        val info = WebViewRendererGoneInfo(didCrash = true, rendererPriorityAtExit = null)

        assertTrue(
            shouldAutoUploadRendererGoneBundle(
                info = info,
                activityFinishing = true,
                activityDestroyed = true
            )
        )
        assertEquals(
            "webview-renderer-crash",
            resolveRendererGoneAutoUploadCrashType(
                info = info,
                activityFinishing = true,
                activityDestroyed = true
            )
        )
    }

    @Test
    fun `foreground renderer non crash exit is still auto uploaded`() {
        val info = WebViewRendererGoneInfo(didCrash = false, rendererPriorityAtExit = null)

        assertTrue(
            shouldAutoUploadRendererGoneBundle(
                info = info,
                activityFinishing = false,
                activityDestroyed = false
            )
        )
        assertEquals(
            "webview-renderer-non-crash-exit",
            resolveRendererGoneAutoUploadCrashType(
                info = info,
                activityFinishing = false,
                activityDestroyed = false
            )
        )
    }
}
