package com.jm.sillydroid.feature.main.ui.home.bridge

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.domain.notification.HostDownloadNotificationCoordinator
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.ui.home.io.HostIoController
import kotlinx.coroutines.CoroutineScope

/**
 * 按浏览器内核创建宿主桥接 adapter。
 *
 * Activity 只提供宿主动作和 IO 能力；WebView 的 addJavascriptInterface / document-start script，
 * 以及 GeckoView 的内置 WebExtension native messaging 都收口在这里，避免新增内核时继续污染 Activity 装配层。
 */
class BrowserHostBridgeInstallerFactory(
    private val activity: AppCompatActivity,
    private val hostIo: HostIoController,
    private val downloadNotificationCoordinator: HostDownloadNotificationCoordinator,
    private val actions: BrowserHostBridgeActions,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val diagnosticSink: (category: String, body: String) -> Unit = { _, _ -> },
) {
    fun create(browserEngine: BrowserEngine): BrowserHostBridgeInstaller {
        return when (browserEngine) {
            BrowserEngine.SYSTEM_WEBVIEW -> createWebViewInstaller()
            BrowserEngine.GECKOVIEW -> createGeckoViewInstaller()
        }
    }

    private fun createWebViewInstaller(): BrowserHostBridgeInstaller {
        val appContext = activity.applicationContext
        return WebViewBrowserHostBridgeInstaller(
            actions = actions,
            blobDownloadController = hostIo.blobDownloadController,
            scope = scope,
            dispatchers = dispatchers,
            systemNotificationController = hostIo.systemNotificationController,
            requestNotificationPermission = actions.requestNotificationPermission,
            unknownDownloadErrorMessage = { appContext.getString(R.string.download_failed_unknown) },
            emptyDownloadPayloadMessage = { appContext.getString(R.string.download_failed_empty_payload) },
            onBlobDownloadPreparing = { fileName ->
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.download_status_preparing, fileName),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onBlobDownloadSaving = { fileName ->
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.download_status_saving, fileName),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onBlobDownloadSaved = { savedFile ->
                // blob/data 导出没有 DownloadManager downloadId；保存完成后从统一下载通知出口提示用户。
                downloadNotificationCoordinator.postBrowserDownloadSaved(
                    fileName = savedFile.fileName,
                    mimeType = savedFile.mimeType,
                    contentUri = savedFile.contentUri,
                    displayPath = savedFile.displayPath
                )
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.download_saved, savedFile.fileName),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onBlobDownloadFailure = actions.showDownloadFailure,
            diagnosticSink = { body ->
                val category = if (body.startsWith("event=notification_bridge")) {
                    "notification"
                } else {
                    "download"
                }
                recordDiagnostic(category = category, body = body)
            }
        )
    }

    private fun createGeckoViewInstaller(): BrowserHostBridgeInstaller {
        val appContext = activity.applicationContext
        return GeckoViewBrowserHostBridgeInstaller(
            actions = actions,
            blobDownloadController = hostIo.blobDownloadController,
            scope = scope,
            dispatchers = dispatchers,
            systemNotificationController = hostIo.systemNotificationController,
            requestNotificationPermission = actions.requestNotificationPermission,
            unknownDownloadErrorMessage = { appContext.getString(R.string.download_failed_unknown) },
            emptyDownloadPayloadMessage = { appContext.getString(R.string.download_failed_empty_payload) },
            onBlobDownloadPreparing = { fileName ->
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.download_status_preparing, fileName),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onBlobDownloadSaving = { fileName ->
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.download_status_saving, fileName),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onBlobDownloadSaved = { savedFile ->
                downloadNotificationCoordinator.postBrowserDownloadSaved(
                    fileName = savedFile.fileName,
                    mimeType = savedFile.mimeType,
                    contentUri = savedFile.contentUri,
                    displayPath = savedFile.displayPath
                )
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.download_saved, savedFile.fileName),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onBlobDownloadFailure = actions.showDownloadFailure,
            diagnosticSink = { body ->
                recordDiagnostic(category = resolveGeckoBridgeDiagnosticCategory(body), body = body)
            }
        )
    }

    private fun resolveGeckoBridgeDiagnosticCategory(body: String): String {
        val compactBody = body.trim()
        return when {
            compactBody.startsWith("event=blob_bridge") ||
                compactBody.startsWith("event=gecko_download_bridge") ||
                compactBody.contains("action=download.") -> "download"
            compactBody.startsWith("event=notification_bridge") ||
                compactBody.contains("action=notification.") -> "notification"
            else -> "browser_bridge"
        }
    }

    private fun recordDiagnostic(category: String, body: String) {
        runCatching { diagnosticSink(category, body) }
    }
}
