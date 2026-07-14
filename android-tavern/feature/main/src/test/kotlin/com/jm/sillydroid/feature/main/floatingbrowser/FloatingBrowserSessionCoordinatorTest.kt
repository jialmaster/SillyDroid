package com.jm.sillydroid.feature.main.floatingbrowser

import android.view.ViewGroup
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.feature.main.ui.home.webview.TavernBrowserHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** 验证进程级浏览器在 Activity、overlay 和无窗口状态间的所有权转换。 */
class FloatingBrowserSessionCoordinatorTest {

    /** detached 页面可直接迁移到 overlay，再恢复到当前 Activity。 */
    @Test
    fun `detached session attaches to overlay and restores to activity`() {
        val surfaces = FakeSurfaceController()
        val coordinator = FloatingBrowserSessionCoordinator(surfaces)
        val container = mock<ViewGroup>()

        assertTrue(coordinator.attachToOverlay(container))
        assertEquals(FloatingBrowserAttachmentState.OVERLAY, coordinator.state)

        surfaces.restoreAllowed = true
        assertTrue(coordinator.restoreToActivity())
        assertEquals(FloatingBrowserAttachmentState.ACTIVITY, coordinator.state)
    }

    /** Activity 不存在时恢复失败，随后必须能从旧 overlay 安全 detach。 */
    @Test
    fun `failed restore can detach from overlay`() {
        val surfaces = FakeSurfaceController()
        val coordinator = FloatingBrowserSessionCoordinator(surfaces)

        assertTrue(coordinator.attachToOverlay(mock()))
        assertFalse(coordinator.restoreToActivity())
        assertTrue(coordinator.detachFromOverlay())
        assertEquals(FloatingBrowserAttachmentState.DETACHED, coordinator.state)
    }

    /** overlay 期间可用新 Activity host 替换同一会话包装器。 */
    @Test
    fun `overlay host replacement accepts same session`() {
        val surfaces = FakeSurfaceController()
        val coordinator = FloatingBrowserSessionCoordinator(surfaces)
        val firstHost = browserHost(surfaces.activeSessionIdentity.orEmpty(), surfaces)
        val secondHost = browserHost(surfaces.activeSessionIdentity.orEmpty(), surfaces)

        coordinator.registerHost(firstHost)
        assertTrue(coordinator.attachToOverlay(mock()))
        coordinator.registerHost(secondHost)

        assertEquals(FloatingBrowserAttachmentState.OVERLAY, coordinator.state)
        assertEquals(surfaces.activeSessionIdentity, coordinator.sessionIdentity)
    }

    /** 不允许把 overlay 中的真实页面冒充成另一个浏览器会话。 */
    @Test(expected = IllegalStateException::class)
    fun `overlay host replacement rejects different session`() {
        val surfaces = FakeSurfaceController()
        val coordinator = FloatingBrowserSessionCoordinator(surfaces)

        coordinator.registerHost(browserHost(surfaces.activeSessionIdentity.orEmpty(), surfaces))
        assertTrue(coordinator.attachToOverlay(mock()))
        coordinator.registerHost(browserHost("SYSTEM_WEBVIEW:different", surfaces))
    }

    /** Activity 注销只移除 UI host，不得把 overlay 表面挂回旧容器。 */
    @Test
    fun `unregister host preserves overlay attachment`() {
        val surfaces = FakeSurfaceController()
        val coordinator = FloatingBrowserSessionCoordinator(surfaces)
        val host = browserHost(surfaces.activeSessionIdentity.orEmpty(), surfaces)

        coordinator.registerHost(host)
        assertTrue(coordinator.attachToOverlay(mock()))
        coordinator.unregisterHost(host)

        assertEquals(FloatingBrowserAttachmentState.OVERLAY, coordinator.state)
        assertEquals(0, surfaces.restoreCalls)
        assertTrue(coordinator.isSessionActive())
    }

    /** 存活 host 必须承担迁移调用，使内核专属可见性和会话诊断不会被 coordinator 绕过。 */
    @Test
    fun `registered host handles overlay attach and activity restore`() {
        val surfaces = FakeSurfaceController().apply { restoreAllowed = true }
        val coordinator = FloatingBrowserSessionCoordinator(surfaces)
        val host = browserHost(surfaces.activeSessionIdentity, surfaces)
        val container = mock<ViewGroup>()

        coordinator.registerHost(host)
        assertTrue(coordinator.attachToOverlay(container))
        assertTrue(coordinator.notifyOverlayWindowVisible())
        assertTrue(coordinator.restoreToActivity())

        verify(host).attachToFloatingBrowser(container)
        verify(host).onFloatingBrowserWindowVisible()
        verify(host).attachToActivityBrowser()
        assertEquals(FloatingBrowserAttachmentState.ACTIVITY, coordinator.state)
    }

    /** 创建会把迁移动作委派给进程 surface fake 的 host mock。 */
    private fun browserHost(identity: String, surfaces: FakeSurfaceController): TavernBrowserHost {
        return mock {
            on { browserSessionIdentity } doReturn identity
            on { browserEngine } doReturn BrowserEngine.SYSTEM_WEBVIEW
            on { canAttachToFloatingBrowser() } doAnswer { surfaces.canAttachActiveSurfaceToOverlay() }
            on { attachToFloatingBrowser(any()) } doAnswer { invocation ->
                surfaces.attachActiveSurfaceToOverlay(invocation.getArgument(0))
            }
            on { attachToActivityBrowser() } doAnswer { surfaces.restoreActiveSurfaceToActivity() }
        }
    }

    /** 纯状态 surface fake，不创建 Android 浏览器对象。 */
    private class FakeSurfaceController : FloatingBrowserSurfaceController {
        override val activeSessionIdentity: String = "SYSTEM_WEBVIEW:retained"
        override val activeBrowserEngine: BrowserEngine = BrowserEngine.SYSTEM_WEBVIEW
        override val activePageLoadCount: Long = 3L
        var restoreAllowed: Boolean = false
        var restoreCalls: Int = 0
        private var state = FloatingBrowserAttachmentState.DETACHED
        private var overlayContainer: ViewGroup? = null

        /** fake 页面始终具备 overlay 迁移条件。 */
        override fun canAttachActiveSurfaceToOverlay(): Boolean = true

        /** 记录 overlay 父容器并切换状态。 */
        override fun attachActiveSurfaceToOverlay(container: ViewGroup): Boolean {
            overlayContainer = container
            state = FloatingBrowserAttachmentState.OVERLAY
            return true
        }

        /** 根据测试开关模拟有无存活 Activity 容器。 */
        override fun restoreActiveSurfaceToActivity(): Boolean {
            restoreCalls += 1
            if (restoreAllowed) {
                overlayContainer = null
                state = FloatingBrowserAttachmentState.ACTIVITY
            }
            return restoreAllowed
        }

        /** 模拟移除旧 overlay Window 后的无窗口状态。 */
        override fun detachActiveSurfaceFromOverlay(): Boolean {
            overlayContainer = null
            state = FloatingBrowserAttachmentState.DETACHED
            return true
        }

        /** 判断 fake 当前是否由指定 overlay 容器持有。 */
        override fun isActiveSurfaceAttachedTo(container: ViewGroup): Boolean = overlayContainer === container

        /** 返回 fake 当前真实挂载状态。 */
        override fun currentAttachmentState(): FloatingBrowserAttachmentState = state
    }
}
