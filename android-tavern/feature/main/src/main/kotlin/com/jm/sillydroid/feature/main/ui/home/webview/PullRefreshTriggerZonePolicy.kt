package com.jm.sillydroid.feature.main.ui.home.webview

/**
 * 下拉刷新入口只允许从容器顶部一段区域触发，
 * 避免正文中部的大多数拖动手势误进入宿主侧下拉刷新判定。
 */
class PullRefreshTriggerZonePolicy(
    private val triggerZoneHeightFraction: Float = DEFAULT_TRIGGER_ZONE_HEIGHT_FRACTION
) {
    fun canStartGesture(initialDownY: Float, containerHeight: Int): Boolean {
        if (containerHeight <= 0) {
            return false
        }

        val clampedFraction = triggerZoneHeightFraction.coerceIn(0f, 1f)
        return initialDownY >= 0f && initialDownY <= containerHeight * clampedFraction
    }

    companion object {
        const val DEFAULT_TRIGGER_ZONE_HEIGHT_FRACTION: Float = 1f / 3f
    }
}
