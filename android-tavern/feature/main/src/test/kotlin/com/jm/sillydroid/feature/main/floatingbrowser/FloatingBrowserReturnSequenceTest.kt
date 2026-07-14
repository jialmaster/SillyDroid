package com.jm.sillydroid.feature.main.floatingbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证悬浮球点击返回时始终保留系统认可的可见窗口启动资格。 */
class FloatingBrowserReturnSequenceTest {

    /** 主界面启动必须发生在 overlay 移除和服务停止之前。 */
    @Test
    fun `launches activity before hiding overlay`() {
        val events = mutableListOf<String>()

        val returned = executeFloatingBrowserReturn(
            launchMainActivity = {
                events += "launch"
                true
            },
            hideOverlay = { events += "hide" },
            stopService = { events += "stop" }
        )

        assertTrue(returned)
        assertEquals(listOf("launch", "hide", "stop"), events)
    }

    /** 启动调用同步失败时不得移除仍承载真实浏览器的窗口。 */
    @Test
    fun `keeps overlay when activity launch fails`() {
        val events = mutableListOf<String>()

        val returned = executeFloatingBrowserReturn(
            launchMainActivity = {
                events += "launch"
                false
            },
            hideOverlay = { events += "hide" },
            stopService = { events += "stop" }
        )

        assertFalse(returned)
        assertEquals(listOf("launch"), events)
    }
}
