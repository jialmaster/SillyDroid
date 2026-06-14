package com.jm.sillydroid.feature.main.ui.home.bridge

import android.webkit.WebView
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.feature.main.model.download.BlobDownloadSavedFile
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import com.jm.sillydroid.feature.main.ui.home.download.AndroidBlobDownloadBridge
import com.jm.sillydroid.feature.main.ui.home.download.BlobDownloadController
import com.jm.sillydroid.feature.main.ui.home.notification.AndroidSystemNotificationBridge
import com.jm.sillydroid.feature.main.ui.home.notification.SystemNotificationController
import kotlinx.coroutines.CoroutineScope

/**
 * 系统 WebView 的完整宿主桥接安装器。
 *
 * 这里集中安装三条 WebView 专属桥：blob/data 下载桥、Notification shim 和 AndroidHostBridge。
 * 其它浏览器内核不能复用 addJavascriptInterface，需要实现自己的 BrowserHostBridgeInstaller。
 */
class WebViewBrowserHostBridgeInstaller(
    private val actions: BrowserHostBridgeActions,
    private val blobDownloadController: BlobDownloadController,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val systemNotificationController: SystemNotificationController,
    private val requestNotificationPermission: () -> Unit,
    private val unknownDownloadErrorMessage: () -> String,
    private val emptyDownloadPayloadMessage: () -> String,
    private val onBlobDownloadPreparing: (String) -> Unit,
    private val onBlobDownloadSaving: (String) -> Unit,
    private val onBlobDownloadSaved: (BlobDownloadSavedFile) -> Unit,
    private val onBlobDownloadFailure: (DownloadFailureReport) -> Unit,
    private val diagnosticSink: (String) -> Unit = {},
) : BrowserHostBridgeInstaller {
    override val blobDownloadBridgeName: String = BrowserHostBridgeInstaller.DEFAULT_BLOB_DOWNLOAD_BRIDGE_NAME
    override val androidHostBridgeName: String = BrowserHostBridgeInstaller.DEFAULT_ANDROID_HOST_BRIDGE_NAME
    override val systemNotificationBridgeName: String = BrowserHostBridgeInstaller.DEFAULT_SYSTEM_NOTIFICATION_BRIDGE_NAME

    private fun recordDiagnostic(body: String) {
        runCatching { diagnosticSink(body) }
    }

    override fun install(target: BrowserHostBridgeTarget) {
        val webView = target.surface as? WebView
        if (webView == null || target.browserEngine != BrowserEngine.SYSTEM_WEBVIEW) {
            recordDiagnostic(
                "event=browser_host_bridge_install_skipped reason=unsupported_target engine=${target.browserEngine.name} surface=${target.surface.javaClass.name}"
            )
            return
        }

        // WebView 的 JS bridge 只能安装在真实 WebView 实例上；renderer 重建后新实例会再次走这里。
        webView.addJavascriptInterface(
            AndroidBlobDownloadBridge(
                controller = blobDownloadController,
                scope = scope,
                dispatchers = dispatchers,
                runOnUiThread = actions.runOnUiThread,
                unknownErrorMessage = unknownDownloadErrorMessage,
                emptyPayloadMessage = emptyDownloadPayloadMessage,
                onPreparing = onBlobDownloadPreparing,
                onSaving = onBlobDownloadSaving,
                onSaved = onBlobDownloadSaved,
                onFailure = onBlobDownloadFailure,
                diagnosticSink = ::recordDiagnostic
            ),
            blobDownloadBridgeName
        )
        installBlobBridgeScript(webView, target.allowedOrigin)

        webView.addJavascriptInterface(
            AndroidSystemNotificationBridge(
                notificationController = systemNotificationController,
                isHostActive = actions.isHostActive,
                runOnUiThread = actions.runOnUiThread,
                requestNotificationPermission = requestNotificationPermission
            ),
            systemNotificationBridgeName
        )

        webView.addJavascriptInterface(
            AndroidHostBridge(
                isHostActive = actions.isHostActive,
                runOnUiThread = actions.runOnUiThread,
                openSettings = actions.openSettings,
                showFloatingLogsBubble = actions.showFloatingLogsBubble,
                requestOpenCurrentPageInBrowser = actions.requestOpenCurrentPageInBrowser,
                applyFloatingLogsBubbleEnabled = actions.applyFloatingLogsBubbleEnabled,
                applyWebViewPullRefreshEnabled = actions.applyBrowserPullRefreshEnabled,
                applySystemBarsBackgroundColor = actions.applySystemBarsBackgroundColor,
                applySystemBarsBackgroundColors = actions.applySystemBarsBackgroundColors,
                reloadTavern = actions.reloadTavern,
                hostVersionInfoJson = actions.hostVersionInfoJson,
                recordWebPerformanceDiagnosticPayload = actions.recordWebPerformanceDiagnosticPayload
            ),
            androidHostBridgeName
        )
    }

    override fun installAfterPageFinished(target: BrowserHostBridgeTarget) {
        val webView = target.surface as? WebView
        if (webView == null || target.browserEngine != BrowserEngine.SYSTEM_WEBVIEW) {
            return
        }
        installBlobBridgeScript(webView, target.allowedOrigin)
    }

    override fun close() {
        blobDownloadController.close()
    }

    private fun installBlobBridgeScript(webView: WebView, allowedOrigin: String) {
        blobDownloadController.installBridgeScript(
            webView = webView,
            bridgeName = blobDownloadBridgeName,
            allowedOrigin = allowedOrigin
        )
    }
}
