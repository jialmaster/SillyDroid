package com.jm.sillydroid.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：[BootstrapError] 分类与 [BootstrapException] 兼容构造器。
 */
class BootstrapErrorTest {

    @Test
    fun `Generic carries message`() {
        val e = BootstrapError.Generic("boom")
        assertEquals("boom", e.message)
    }

    @Test
    fun `data classes with same message are equal`() {
        assertEquals(BootstrapError.ArchiveCorrupted("a"), BootstrapError.ArchiveCorrupted("a"))
        assertEquals(BootstrapError.ServerNotReady("s"), BootstrapError.ServerNotReady("s"))
    }

    @Test
    fun `different subclasses with same message are not equal`() {
        // 防止"分类丢失"——string 相同但分类不同的两个错误必须不相等
        val a: BootstrapError = BootstrapError.Generic("x")
        val b: BootstrapError = BootstrapError.ArchiveCorrupted("x")
        assertTrue("Generic and ArchiveCorrupted with same message must not be equal", a != b)
    }

    @Test
    fun `BootstrapException backward-compat constructor wraps as Generic`() {
        val ex = BootstrapException("legacy message")
        assertTrue(ex.error is BootstrapError.Generic)
        assertEquals("legacy message", ex.message)
        assertEquals("legacy message", ex.error.message)
        assertNull(ex.cause)
    }

    @Test
    fun `BootstrapException with sealed error preserves error reference`() {
        val classified: BootstrapError = BootstrapError.ServerNotReady("server timed out")
        val ex = BootstrapException(classified)
        assertSame(classified, ex.error)
        assertEquals("server timed out", ex.message)
    }

    @Test
    fun `BootstrapException carries cause when provided`() {
        val cause = RuntimeException("root")
        val ex = BootstrapException(BootstrapError.PostExtractHookFailed("hook x"), cause)
        assertSame(cause, ex.cause)
    }

    @Test
    fun `when expression covers all subclasses`() {
        // 编译期防御：sealed class 添加新子类时这里会变成"非穷尽"，提示更新使用方。
        val cases = listOf(
            BootstrapError.Generic("g"),
            BootstrapError.ArchiveCorrupted("a"),
            BootstrapError.ServerNotReady("s"),
            BootstrapError.RuntimeStopTimeout("r"),
            BootstrapError.PostExtractHookFailed("p")
        )
        for (e in cases) {
            val tag: String = when (e) {
                is BootstrapError.Generic -> "g"
                is BootstrapError.ArchiveCorrupted -> "a"
                is BootstrapError.ServerNotReady -> "s"
                is BootstrapError.RuntimeStopTimeout -> "r"
                is BootstrapError.PostExtractHookFailed -> "p"
            }
            assertEquals(e.message, tag)
        }
    }
}
