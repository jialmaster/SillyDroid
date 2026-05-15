package com.jm.sillydroid.feature.main.ui.home.notification

import android.webkit.JavascriptInterface

class AndroidSystemNotificationBridge(
    private val notificationController: SystemNotificationController,
    private val isHostActive: () -> Boolean,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val requestPermission: () -> Unit
) {
    @JavascriptInterface
    fun showNotification(payload: String?): Boolean {
        if (!isHostActive()) {
            return false
        }

        val request = notificationController.parseRequest(payload) ?: return false
        if (!notificationController.canPost()) {
            runOnUiThread { requestPermission() }
            return false
        }

        return notificationController.show(request)
    }

    @JavascriptInterface
    fun permissionState(): String {
        return notificationController.permissionState()
    }

    @JavascriptInterface
    fun requestPermission(): String {
        if (isHostActive()) {
            runOnUiThread { requestPermission() }
        }
        return notificationController.permissionState()
    }
}
