package com.jm.sillydroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证进程级 Activity 前后台计数。
 *
 * 允许：覆盖多 Activity 交接和异常 stop 顺序；不允许依赖真实 Android 窗口或具体 Activity 类。
 */
class ApplicationForegroundTransitionCounterTest {
    /** App 内 Activity 交接时，只有最后一个 started Activity 停止才算整体退后台。 */
    @Test
    fun appRemainsForegroundUntilLastStartedActivityStops() {
        val transitions = mutableListOf<Boolean>()
        val counter = ApplicationForegroundTransitionCounter { foreground -> transitions.add(foreground) }

        counter.onActivityStarted()
        counter.onActivityStarted()
        counter.onActivityStopped()

        assertTrue(counter.isInForeground)
        assertEquals(listOf(true), transitions)

        counter.onActivityStopped()

        assertFalse(counter.isInForeground)
        assertEquals(listOf(true, false), transitions)
    }

    /** 异常多余 stop 只保持后台状态，不重复触发后台迁移。 */
    @Test
    fun extraStopsDoNotEmitDuplicateBackgroundTransitions() {
        val transitions = mutableListOf<Boolean>()
        val counter = ApplicationForegroundTransitionCounter { foreground -> transitions.add(foreground) }

        counter.onActivityStopped()
        counter.onActivityStarted()
        counter.onActivityStopped()
        counter.onActivityStopped()

        assertFalse(counter.isInForeground)
        assertEquals(listOf(true, false), transitions)
    }
}
