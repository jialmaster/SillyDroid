package com.jm.sillydroid.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapReadyWatchdogPolicyTest {
    @Test
    fun `foreground keeps the normal watchdog threshold`() {
        val policy = BootstrapReadyWatchdogPolicy.resolve(isAppInForeground = true)

        assertEquals("foreground", policy.name)
        assertEquals(6, policy.failureThreshold)
    }

    @Test
    fun `background uses relaxed watchdog threshold`() {
        val foregroundPolicy = BootstrapReadyWatchdogPolicy.resolve(isAppInForeground = true)
        val backgroundPolicy = BootstrapReadyWatchdogPolicy.resolve(isAppInForeground = false)

        assertEquals("background-relaxed", backgroundPolicy.name)
        assertEquals(18, backgroundPolicy.failureThreshold)
        assertTrue(backgroundPolicy.failureThreshold > foregroundPolicy.failureThreshold)
    }
}
