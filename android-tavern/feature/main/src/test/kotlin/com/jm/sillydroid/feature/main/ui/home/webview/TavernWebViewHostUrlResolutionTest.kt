package com.jm.sillydroid.feature.main.ui.home.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernWebViewHostUrlResolutionTest {

    @Test
    fun `resolveInitialTavernUrl reuses remembered in-site route`() {
        val result = resolveInitialTavernUrl(
            baseUrl = "http://127.0.0.1:8000",
            rememberedUrl = "http://127.0.0.1:8000/characters?tab=chat#latest"
        )

        assertEquals("http://127.0.0.1:8000/characters?tab=chat#latest", result)
    }

    @Test
    fun `resolveInitialTavernUrl falls back to site root when remembered url is blank`() {
        val result = resolveInitialTavernUrl(
            baseUrl = "http://127.0.0.1:8000",
            rememberedUrl = "   "
        )

        assertEquals("http://127.0.0.1:8000/", result)
    }

    @Test
    fun `resolveInitialTavernUrl falls back to current base when remembered url belongs to another port`() {
        val result = resolveInitialTavernUrl(
            baseUrl = "http://127.0.0.1:8000",
            rememberedUrl = "http://127.0.0.1:8080/characters/1"
        )

        assertEquals("http://127.0.0.1:8000/", result)
    }

    @Test
    fun `isTavernUrlForBaseUrl only matches urls under the same local site`() {
        assertTrue(isTavernUrlForBaseUrl("http://127.0.0.1:8000/#/chat", "http://127.0.0.1:8000"))
        assertTrue(isTavernUrlForBaseUrl("http://127.0.0.1:8000/settings/theme", "http://127.0.0.1:8000"))
        assertFalse(isTavernUrlForBaseUrl("https://127.0.0.1:8000/", "http://127.0.0.1:8000"))
        assertFalse(isTavernUrlForBaseUrl("http://127.0.0.1:9000/", "http://127.0.0.1:8000"))
    }
}
