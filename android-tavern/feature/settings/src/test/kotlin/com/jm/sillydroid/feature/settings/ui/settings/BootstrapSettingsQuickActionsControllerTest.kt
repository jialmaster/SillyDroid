package com.jm.sillydroid.feature.settings.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapSettingsQuickActionsControllerTest {

    @Test
    fun `merge adds loopback entries and current wifi wildcard when subnet missing`() {
        val merged = BootstrapSettingsQuickActionsController.mergeLanWhitelistEntriesForWifi(
            existingEntries = listOf("192.168.50.10"),
            wifiIpv4 = "192.168.50.23"
        )

        assertTrue(merged.contains("127.0.0.1"))
        assertTrue(merged.contains("::1"))
        assertTrue(merged.contains("192.168.50.*"))
    }

    @Test
    fun `merge does not add narrower wildcard when broader wildcard already covers wifi`() {
        val merged = BootstrapSettingsQuickActionsController.mergeLanWhitelistEntriesForWifi(
            existingEntries = listOf("127.0.0.1", "::1", "192.168.*.*"),
            wifiIpv4 = "192.168.50.23"
        )

        assertEquals(listOf("127.0.0.1", "::1", "192.168.*.*"), merged)
    }

    @Test
    fun `merge does not add duplicate wildcard when same subnet already exists`() {
        val merged = BootstrapSettingsQuickActionsController.mergeLanWhitelistEntriesForWifi(
            existingEntries = listOf("::1", "127.0.0.1", "192.168.50.*"),
            wifiIpv4 = "192.168.50.88"
        )

        assertEquals(listOf("127.0.0.1", "::1", "192.168.50.*"), merged)
    }
}
