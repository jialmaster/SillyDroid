package com.jm.sillydroid.feature.main.floatingbrowser

import org.junit.Assert.assertEquals
import org.junit.Test

/** 锁定悬浮浏览器球最近边缘选择、半隐藏坐标和持久化比例。 */
class FloatingBrowserDockingPolicyTest {

    /** 屏幕左半区应吸附左侧，包含正中点。 */
    @Test
    fun `center in left half docks left`() {
        assertEquals(
            FloatingBrowserDockSide.LEFT,
            FloatingBrowserDockingPolicy.resolveNearestSide(bubbleCenterX = 540f, windowWidth = 1080)
        )
    }

    /** 屏幕右半区应吸附右侧。 */
    @Test
    fun `center in right half docks right`() {
        assertEquals(
            FloatingBrowserDockSide.RIGHT,
            FloatingBrowserDockingPolicy.resolveNearestSide(bubbleCenterX = 541f, windowWidth = 1080)
        )
    }

    /** 左右吸附都只把半个球留在屏幕内。 */
    @Test
    fun `docked coordinates hide half of bubble`() {
        assertEquals(-28, FloatingBrowserDockingPolicy.resolveDockedX(FloatingBrowserDockSide.LEFT, 1080, 56))
        assertEquals(1052, FloatingBrowserDockingPolicy.resolveDockedX(FloatingBrowserDockSide.RIGHT, 1080, 56))
    }

    /** 持久化比例只表达稳定的左右边缘，不保存拖动中的任意横坐标。 */
    @Test
    fun `stored fractions round trip dock side`() {
        assertEquals(0f, FloatingBrowserDockingPolicy.horizontalFraction(FloatingBrowserDockSide.LEFT))
        assertEquals(1f, FloatingBrowserDockingPolicy.horizontalFraction(FloatingBrowserDockSide.RIGHT))
        assertEquals(FloatingBrowserDockSide.LEFT, FloatingBrowserDockingPolicy.resolveStoredSide(0f))
        assertEquals(FloatingBrowserDockSide.RIGHT, FloatingBrowserDockingPolicy.resolveStoredSide(1f))
    }
}
