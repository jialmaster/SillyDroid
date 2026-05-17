package com.jm.sillydroid.data.logs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class HostDiagnosticLogLineFormatterTest {

    @Test
    fun `buildLine adds timestamp category and newline`() {
        val previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val line = HostDiagnosticLogLineFormatter.buildLine(
                category = "webview",
                body = "event=renderer_gone didCrash=true",
                nowMillis = 0L
            )

            assertEquals(
                "[1970-01-01 00:00:00.000] [webview] event=renderer_gone didCrash=true\n",
                line
            )
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun `buildLine collapses multiline body into one line`() {
        val line = HostDiagnosticLogLineFormatter.buildLine(
            category = " activity ",
            body = " event=on_create \n  savedStatePresent=true \r\n "
        )

        val suffix = line.substringAfter("] [activity] ")
        assertEquals("event=on_create savedStatePresent=true\n", suffix)
    }
}
