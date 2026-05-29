package com.jm.sillydroid.feature.main.ui.home.system

import com.jm.sillydroid.core.model.settings.HostDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MainSystemBarLayoutPolicyTest {
    @Test
    fun `landscape normal mode uses status-bar-hidden behavior`() {
        assertEquals(
            HostDisplayMode.STATUS_BAR_HIDDEN,
            resolveMainHostDisplayMode(HostDisplayMode.NORMAL, isLandscape = true)
        )
    }

    @Test
    fun `landscape status-bar-hidden mode draws webview under side and bottom system bars`() {
        val padding = calculateMainContentPadding(
            initialPadding = SystemBarEdgeInsets(left = 1, top = 2, right = 3, bottom = 4),
            systemBarsInsets = SystemBarEdgeInsets(left = 40, top = 24, right = 0, bottom = 18),
            imeBottomInset = 0,
            displayMode = HostDisplayMode.STATUS_BAR_HIDDEN,
            isLandscape = true
        )

        assertEquals(
            MainContentPadding(
                left = 1,
                top = 2,
                right = 3,
                bottom = 4,
                statusBarBackgroundHeight = 0,
                navigationBarBackgroundHeight = 0
            ),
            padding
        )
    }

    @Test
    fun `portrait normal mode keeps system bar safe areas`() {
        val padding = calculateMainContentPadding(
            initialPadding = SystemBarEdgeInsets(left = 1, top = 2, right = 3, bottom = 4),
            systemBarsInsets = SystemBarEdgeInsets(left = 5, top = 24, right = 7, bottom = 18),
            imeBottomInset = 30,
            displayMode = HostDisplayMode.NORMAL,
            isLandscape = false
        )

        assertEquals(
            MainContentPadding(
                left = 6,
                top = 26,
                right = 10,
                bottom = 52,
                statusBarBackgroundHeight = 24,
                navigationBarBackgroundHeight = 18
            ),
            padding
        )
    }
}
