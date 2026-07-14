package com.jm.sillydroid.feature.main.ui.home.bridge

import android.view.View
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * 浏览器内核与 Android 宿主能力之间的桥接安装契约。
 *
 * 不同内核的桥接通道不同：系统 WebView 走 addJavascriptInterface/document-start script，
 * GeckoView 走内置 WebExtension native messaging。MainActivity 只提供宿主能力动作，具体通道由各内核实现。
 */
interface BrowserHostBridgeInstaller {
    val blobDownloadBridgeName: String
    val androidHostBridgeName: String
    val systemNotificationBridgeName: String

    fun install(target: BrowserHostBridgeTarget)

    fun install(target: BrowserHostBridgeTarget, onReady: () -> Unit) {
        install(target)
        onReady()
    }

    fun installAfterPageFinished(target: BrowserHostBridgeTarget) = Unit

    fun requestViewportDensityPercent(percent: Int, baseViewportWidthCssPx: Int): Boolean = false

    /** 请求浏览器侧记录当前 document.visibilityState；只允许写无内容诊断。 */
    fun requestDocumentVisibilityDiagnostic(reason: String): Boolean = false

    fun close() = Unit

    companion object {
        const val DEFAULT_BLOB_DOWNLOAD_BRIDGE_NAME = BrowserHostBridgeNames.DEFAULT_BLOB_DOWNLOAD_BRIDGE_NAME
        const val DEFAULT_ANDROID_HOST_BRIDGE_NAME = BrowserHostBridgeNames.DEFAULT_ANDROID_HOST_BRIDGE_NAME
        const val DEFAULT_SYSTEM_NOTIFICATION_BRIDGE_NAME = BrowserHostBridgeNames.DEFAULT_SYSTEM_NOTIFICATION_BRIDGE_NAME
    }
}

object BrowserHostBridgeNames {
    const val DEFAULT_BLOB_DOWNLOAD_BRIDGE_NAME = "AndroidDownloadBridge"
    const val DEFAULT_ANDROID_HOST_BRIDGE_NAME = "SillyDroidAndroidHostBridge"
    const val DEFAULT_SYSTEM_NOTIFICATION_BRIDGE_NAME = "AndroidSystemNotificationBridge"
}

data class BrowserHostBridgeTarget(
    val browserEngine: BrowserEngine,
    val surface: View,
    val allowedOrigin: String,
    val geckoRuntime: GeckoRuntime? = null,
    val geckoSession: GeckoSession? = null
)

/**
 * 浏览器桥可调用的进程安全宿主动作集合。
 *
 * Activity 专属动作必须由动态 delegate 提供；默认空实现不得持有已销毁窗口。
 */
data class BrowserHostBridgeActions(
    val isHostActive: () -> Boolean,
    val runOnUiThread: (() -> Unit) -> Unit,
    val openSettings: () -> Unit,
    val showFloatingLogsBubble: () -> Unit,
    val requestOpenCurrentPageInBrowser: () -> Unit,
    val applyFloatingLogsBubbleEnabled: (Boolean) -> Unit,
    val applyBrowserPullRefreshEnabled: (Boolean) -> Unit,
    val applySystemBarsBackgroundColor: (String) -> Unit,
    val applySystemBarsBackgroundColors: (String, String) -> Unit,
    val reloadTavern: () -> Unit,
    val hostVersionInfoJson: () -> String,
    val recordWebPerformanceDiagnosticPayload: (String) -> Unit = {},
    val requestNotificationPermission: () -> Unit = {},
    val showDownloadFailure: (DownloadFailureReport) -> Unit = {}
)
