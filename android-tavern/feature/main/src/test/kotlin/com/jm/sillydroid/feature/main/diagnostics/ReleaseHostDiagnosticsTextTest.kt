package com.jm.sillydroid.feature.main.diagnostics

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseHostDiagnosticsTextTest {

    @Test
    @Suppress("DEPRECATION")
    fun `formatTrimMemoryLevel maps known Android levels`() {
        assertEquals(
            "TRIM_MEMORY_RUNNING_LOW",
            formatTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        )
        assertEquals(
            "TRIM_MEMORY_COMPLETE",
            formatTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        )
        assertEquals("UNKNOWN", formatTrimMemoryLevel(-1))
    }

    @Test
    fun `normalizeDiagnosticValue collapses multiline text`() {
        assertEquals(
            "alpha beta gamma",
            normalizeDiagnosticValue(" alpha \n beta \r\n gamma ")
        )
        assertEquals("-", normalizeDiagnosticValue(" \n "))
    }
}
