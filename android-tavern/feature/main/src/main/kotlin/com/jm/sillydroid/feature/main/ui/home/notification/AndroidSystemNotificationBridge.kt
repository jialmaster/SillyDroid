package com.jm.sillydroid.feature.main.ui.home.notification

import android.webkit.JavascriptInterface

/**
 * 向浏览器页面暴露原生通知与提示音能力。
 *
 * 允许：解析通知请求、查询权限并记录无内容诊断；不允许记录通知标题、正文或页面消息。
 */
class AndroidSystemNotificationBridge(
    private val notificationController: SystemNotificationController,
    private val isHostActive: () -> Boolean,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val requestNotificationPermission: () -> Unit,
    private val diagnosticSink: (String) -> Unit = {},
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    /** 播放宿主提示音，并记录不含消息内容的触发结果。 */
    @JavascriptInterface
    fun playAlertSound(): Boolean {
        val active = isHostActive()
        if (!active) {
            recordDiagnostic("event=notification_bridge_sound atEpochMs=${currentTimeMillis()} hostActive=false played=false")
            return false
        }
        val played = notificationController.playAlertSound()
        recordDiagnostic("event=notification_bridge_sound atEpochMs=${currentTimeMillis()} hostActive=true played=$played")
        return played
    }

    /** 解析并发送原生通知；诊断只记录状态、时间和结果，不记录标题或正文。 */
    @JavascriptInterface
    fun showNotification(payload: String?): Boolean {
        val invokedAt = currentTimeMillis()
        val active = isHostActive()
        if (!active) {
            recordDiagnostic("event=notification_bridge_show atEpochMs=$invokedAt hostActive=false parsed=false canPost=false posted=false")
            return false
        }

        val request = notificationController.parseRequest(payload)
        if (request == null) {
            recordDiagnostic("event=notification_bridge_show atEpochMs=$invokedAt hostActive=true parsed=false canPost=false posted=false")
            return false
        }
        val canPost = notificationController.canPost()
        if (!canPost) {
            runOnUiThread { requestNotificationPermission() }
            recordDiagnostic("event=notification_bridge_show atEpochMs=$invokedAt hostActive=true parsed=true canPost=false posted=false")
            return false
        }

        val posted = notificationController.show(request)
        recordDiagnostic("event=notification_bridge_show atEpochMs=$invokedAt hostActive=true parsed=true canPost=true posted=$posted")
        return posted
    }

    /** 返回当前系统通知权限状态，不触发 UI。 */
    @JavascriptInterface
    fun permissionState(): String {
        return notificationController.permissionState()
    }

    /** 仅在进程级浏览器仍存活时把权限请求转交给当前 Activity delegate。 */
    @JavascriptInterface
    fun requestPermission(): String {
        val active = isHostActive()
        if (active) {
            // WebView 会在 JavaBridge 线程调用 @JavascriptInterface 方法；这里必须显式调用宿主权限动作，
            // 不能与桥方法同名，否则 Kotlin 会解析为当前方法并造成递归栈溢出。
            runOnUiThread { requestNotificationPermission() }
        }
        recordDiagnostic("event=notification_bridge_permission_request atEpochMs=${currentTimeMillis()} hostActive=$active")
        return notificationController.permissionState()
    }

    /** 诊断写入失败不得影响页面通知链路。 */
    private fun recordDiagnostic(body: String) {
        runCatching { diagnosticSink(body) }
    }
}
