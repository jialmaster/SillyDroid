package com.jm.sillydroid.feature.main.ui.home.notification

import android.app.Notification
import android.app.Service
import com.jm.sillydroid.core.model.notification.HostNotificationSpec
import com.jm.sillydroid.domain.notification.HostNotificationService
import com.jm.sillydroid.feature.main.model.notification.SystemNotificationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidSystemNotificationBridgeTest {

    @Test
    fun `requestPermission invokes host permission action once`() {
        var permissionRequestCount = 0
        val bridge = AndroidSystemNotificationBridge(
            notificationController = FakeSystemNotificationController(),
            isHostActive = { true },
            runOnUiThread = { action -> action() },
            requestNotificationPermission = { permissionRequestCount += 1 }
        )

        assertEquals("prompt", bridge.requestPermission())

        assertEquals(1, permissionRequestCount)
    }

    @Test
    fun `requestPermission skips host action when inactive`() {
        var permissionRequestCount = 0
        val bridge = AndroidSystemNotificationBridge(
            notificationController = FakeSystemNotificationController(),
            isHostActive = { false },
            runOnUiThread = { action -> action() },
            requestNotificationPermission = { permissionRequestCount += 1 }
        )

        assertEquals("prompt", bridge.requestPermission())

        assertEquals(0, permissionRequestCount)
    }

    @Test
    fun `showNotification requests permission instead of posting when blocked`() {
        var permissionRequestCount = 0
        val controller = FakeSystemNotificationController(canPost = false)
        val bridge = AndroidSystemNotificationBridge(
            notificationController = controller,
            isHostActive = { true },
            runOnUiThread = { action -> action() },
            requestNotificationPermission = { permissionRequestCount += 1 }
        )

        assertFalse(bridge.showNotification("""{"title":"hello"}"""))

        assertEquals(1, permissionRequestCount)
        assertEquals(0, controller.showCount)
    }

    private class FakeSystemNotificationController(
        private val canPost: Boolean = true
    ) : SystemNotificationController(NoOpHostNotificationService, smallIconResId = 0) {
        var showCount: Int = 0

        override fun parseRequest(payload: String?): SystemNotificationRequest? {
            return payload?.takeIf { it.isNotBlank() }?.let {
                SystemNotificationRequest(
                    notificationId = "id",
                    title = "title",
                    body = "body",
                    tag = ""
                )
            }
        }

        override fun canPost(): Boolean = canPost

        override fun permissionState(): String = "prompt"

        override fun show(request: SystemNotificationRequest): Boolean {
            showCount += 1
            return true
        }
    }

    private object NoOpHostNotificationService : HostNotificationService {
        override fun ensureChannels() = Unit
        override fun canPostNotifications(): Boolean = false
        override fun post(spec: HostNotificationSpec): Notification = error("not used")
        override fun postForeground(service: Service, spec: HostNotificationSpec): Notification = error("not used")
        override fun remove(notificationKey: String) = Unit
        override fun removeGroup(prefix: String) = Unit
        override fun buildNotification(spec: HostNotificationSpec): Notification = error("not used")
    }
}
