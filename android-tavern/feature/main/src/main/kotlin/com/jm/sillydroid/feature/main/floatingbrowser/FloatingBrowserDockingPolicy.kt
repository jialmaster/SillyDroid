package com.jm.sillydroid.feature.main.floatingbrowser

import kotlin.math.roundToInt

/** 悬浮浏览器球可吸附的水平屏幕边缘。 */
internal enum class FloatingBrowserDockSide {
    LEFT,
    RIGHT
}

/**
 * 悬浮浏览器球的纯坐标策略。
 *
 * 允许：选择最近边缘、计算半隐藏坐标、把边缘转换为持久化比例。
 * 不允许：访问 WindowManager、修改 View 或读写设置，便于 JVM 单测锁定交互边界。
 */
internal object FloatingBrowserDockingPolicy {
    private const val HIDDEN_WIDTH_FRACTION = 0.5f

    /** 根据悬浮球中心点选择最近的左右边缘。 */
    fun resolveNearestSide(bubbleCenterX: Float, windowWidth: Int): FloatingBrowserDockSide {
        return if (bubbleCenterX <= windowWidth.coerceAtLeast(0) / 2f) {
            FloatingBrowserDockSide.LEFT
        } else {
            FloatingBrowserDockSide.RIGHT
        }
    }

    /** 根据已保存的归一化横坐标恢复左右吸附边缘。 */
    fun resolveStoredSide(horizontalFraction: Float?): FloatingBrowserDockSide {
        return if ((horizontalFraction ?: 1f) < 0.5f) {
            FloatingBrowserDockSide.LEFT
        } else {
            FloatingBrowserDockSide.RIGHT
        }
    }

    /** 计算半个悬浮球缩入屏幕外的 WindowManager 横坐标。 */
    fun resolveDockedX(side: FloatingBrowserDockSide, windowWidth: Int, bubbleWidth: Int): Int {
        val normalizedBubbleWidth = bubbleWidth.coerceAtLeast(1)
        val maxExposedX = (windowWidth - normalizedBubbleWidth).coerceAtLeast(0)
        val hiddenWidth = (normalizedBubbleWidth * HIDDEN_WIDTH_FRACTION).roundToInt().coerceAtLeast(1)
        return when (side) {
            FloatingBrowserDockSide.LEFT -> -hiddenWidth
            FloatingBrowserDockSide.RIGHT -> maxExposedX + hiddenWidth
        }
    }

    /** 把吸附边缘转换成稳定的持久化横坐标比例。 */
    fun horizontalFraction(side: FloatingBrowserDockSide): Float {
        return if (side == FloatingBrowserDockSide.LEFT) 0f else 1f
    }
}
