package com.jm.sillydroid.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateErrorFormattingTest {

    private val fallback = "未知失败原因"

    @Test
    fun `null message returns fallback`() {
        val result = formatAppUpdateCheckError(RuntimeException(), fallback)
        assertEquals(fallback, result)
    }

    @Test
    fun `blank message returns fallback`() {
        val result = formatAppUpdateCheckError(RuntimeException("   \n  \t  "), fallback)
        assertEquals(fallback, result)
    }

    @Test
    fun `single-line message returned as is`() {
        val result = formatAppUpdateCheckError(RuntimeException("network unreachable"), fallback)
        assertEquals("network unreachable", result)
    }

    @Test
    fun `leading and trailing whitespace stripped`() {
        val result = formatAppUpdateCheckError(RuntimeException("  hello  "), fallback)
        assertEquals("hello", result)
    }

    @Test
    fun `multi-line message picks first non-blank trimmed line`() {
        val raw = """
            
              first line
            second line
        """.trimIndent()
        val result = formatAppUpdateCheckError(RuntimeException(raw), fallback)
        assertEquals("first line", result)
    }

    @Test
    fun `long line truncated to 240 chars`() {
        val long = "x".repeat(500)
        val result = formatAppUpdateCheckError(RuntimeException(long), fallback)
        assertEquals(240, result.length)
    }

    @Test
    fun `exactly 240 char line preserved`() {
        val exact = "y".repeat(240)
        val result = formatAppUpdateCheckError(RuntimeException(exact), fallback)
        assertEquals(exact, result)
    }
}
