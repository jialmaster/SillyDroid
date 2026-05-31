package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertEquals
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
}
