package com.jm.sillydroid.feature.main.ui.home.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewMemoryTrimPolicyTest {
    @Test
    fun `clears volatile cache only for severe foreground or background memory pressure`() {
        assertFalse(shouldClearVolatileWebViewCacheForTrimMemory(TRIM_MEMORY_RUNNING_MODERATE_LEVEL))
        assertFalse(shouldClearVolatileWebViewCacheForTrimMemory(TRIM_MEMORY_RUNNING_LOW_LEVEL))
        assertTrue(shouldClearVolatileWebViewCacheForTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL_LEVEL))
        assertFalse(shouldClearVolatileWebViewCacheForTrimMemory(TRIM_MEMORY_UI_HIDDEN_LEVEL))
        assertTrue(shouldClearVolatileWebViewCacheForTrimMemory(TRIM_MEMORY_BACKGROUND_LEVEL))
        assertTrue(shouldClearVolatileWebViewCacheForTrimMemory(TRIM_MEMORY_MODERATE_LEVEL))
        assertTrue(shouldClearVolatileWebViewCacheForTrimMemory(TRIM_MEMORY_COMPLETE_LEVEL))
    }

    @Test
    fun `throttles repeated volatile cache clears inside interval`() {
        assertFalse(
            shouldThrottleVolatileWebViewCacheClear(
                nowElapsedMs = 1_000L,
                lastClearElapsedMs = -1L,
                minIntervalMs = 30_000L
            )
        )
        assertTrue(
            shouldThrottleVolatileWebViewCacheClear(
                nowElapsedMs = 20_000L,
                lastClearElapsedMs = 1_000L,
                minIntervalMs = 30_000L
            )
        )
        assertFalse(
            shouldThrottleVolatileWebViewCacheClear(
                nowElapsedMs = 31_000L,
                lastClearElapsedMs = 1_000L,
                minIntervalMs = 30_000L
            )
        )
    }
}
