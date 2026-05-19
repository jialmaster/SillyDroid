package com.jm.sillydroid.feature.main.ui.home.webview

import org.junit.Test

class PullRefreshTriggerZonePolicyTest {

    private val policy = PullRefreshTriggerZonePolicy()

    @Test
    fun `gesture can start inside top third`() {
        assert(policy.canStartGesture(initialDownY = 99f, containerHeight = 300))
        assert(policy.canStartGesture(initialDownY = 100f, containerHeight = 300))
    }

    @Test
    fun `gesture is rejected below top third`() {
        assert(!policy.canStartGesture(initialDownY = 101f, containerHeight = 300))
        assert(!policy.canStartGesture(initialDownY = 250f, containerHeight = 300))
    }

    @Test
    fun `gesture is rejected when container height is invalid`() {
        assert(!policy.canStartGesture(initialDownY = 0f, containerHeight = 0))
    }
}
