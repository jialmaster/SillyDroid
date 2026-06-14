package com.jm.sillydroid.feature.main.ui.home.notification

import android.webkit.JavascriptInterface

class AndroidSystemNotificationBridge(
    private val notificationController: SystemNotificationController,
    private val isHostActive: () -> Boolean,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val requestNotificationPermission: () -> Unit
) {
    @JavascriptInterface
    fun showNotification(payload: String?): Boolean {
        if (!isHostActive()) {
            return false
        }

        val request = notificationController.parseRequest(payload) ?: return false
        if (!notificationController.canPost()) {
            runOnUiThread { requestNotificationPermission() }
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
            // WebView 会在 JavaBridge 线程调用 @JavascriptInterface 方法；这里必须显式调用宿主权限动作，
            // 不能与桥方法同名，否则 Kotlin 会解析为当前方法并造成递归栈溢出。
            runOnUiThread { requestNotificationPermission() }
        }
        return notificationController.permissionState()
    }
}
