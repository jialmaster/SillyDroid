package com.jm.sillydroid.feature.main.ui.home.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHostBridgeTest {

    @Test
    fun `records web performance diagnostic only when host is active and payload is not blank`() {
        val recordedPayloads = mutableListOf<String>()
        val bridge = newBridge(
            isHostActive = { true },
            recordWebPerformanceDiagnostic = recordedPayloads::add
        )

        assertTrue(bridge.recordWebPerformanceDiagnostic("""{"event":"page_load_summary"}"""))
        assertFalse(bridge.recordWebPerformanceDiagnostic(""))

        assert(recordedPayloads == listOf("""{"event":"page_load_summary"}"""))
    }

    @Test
    fun `rejects web performance diagnostic when host is inactive`() {
        val recordedPayloads = mutableListOf<String>()
        val bridge = newBridge(
            isHostActive = { false },
            recordWebPerformanceDiagnostic = recordedPayloads::add
        )

        assertFalse(bridge.recordWebPerformanceDiagnostic("""{"event":"page_load_summary"}"""))

        assert(recordedPayloads.isEmpty())
    }

    @Test
    fun `compacts web performance diagnostic into one bounded log line`() {
        val recordedPayloads = mutableListOf<String>()
        val bridge = newBridge(
            isHostActive = { true },
            recordWebPerformanceDiagnostic = recordedPayloads::add
        )

        assertTrue(bridge.recordWebPerformanceDiagnostic(" first\r\nsecond " + "x".repeat(5_000)))

        assertTrue(recordedPayloads.single().startsWith("first  second "))
        assertFalse(recordedPayloads.single().contains("\n"))
        assertFalse(recordedPayloads.single().contains("\r"))
        assertTrue(recordedPayloads.single().length <= 4_096)
    }

    private fun newBridge(
        isHostActive: () -> Boolean,
        recordWebPerformanceDiagnostic: (String) -> Unit
    ): AndroidHostBridge {
        return AndroidHostBridge(
            isHostActive = isHostActive,
            runOnUiThread = { action -> action() },
            openSettings = {},
            showFloatingLogsBubble = {},
            requestOpenCurrentPageInBrowser = {},
            applyFloatingLogsBubbleEnabled = {},
            applyWebViewPullRefreshEnabled = {},
            applySystemBarsBackgroundColor = {},
            applySystemBarsBackgroundColors = { _, _ -> },
            reloadTavern = {},
            hostVersionInfoJson = { "{}" },
            recordWebPerformanceDiagnosticPayload = recordWebPerformanceDiagnostic
        )
    }
}
