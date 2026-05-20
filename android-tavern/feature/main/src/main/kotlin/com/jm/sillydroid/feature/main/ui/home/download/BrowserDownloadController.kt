package com.jm.sillydroid.feature.main.ui.home.download

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.jm.sillydroid.feature.main.components.download.resolveDownloadFileName
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadResult

class BrowserDownloadController(
    private val downloadManager: DownloadManager,
    private val pendingDescription: (fileName: String) -> String,
    private val blobDownloadController: BlobDownloadController? = null,
    private val blobDownloadBridgeName: String? = null,
    private val diagnosticSink: (String) -> Unit = {}
) {
    private fun recordDiagnostic(body: String) {
        runCatching { diagnosticSink(body) }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Suppress("DEPRECATION")
    fun enqueue(request: BrowserDownloadRequest): BrowserDownloadResult? {
        val targetUrl = request.url.trim()
        if (targetUrl.isBlank()) {
            recordDiagnostic(
                "event=browser_download_skipped reason=blank_url scheme=${Uri.parse(targetUrl.ifBlank { "about:blank" }).scheme.orEmpty()} " +
                    "url=${targetUrl.ifBlank { "-" }}"
            )
            return null
        }

        val fileName = resolveDownloadFileName(
            rawName = URLUtil.guessFileName(targetUrl, request.contentDisposition, request.mimeType),
            fallbackUrl = targetUrl
        )

        if (targetUrl.startsWith("blob:") || targetUrl.startsWith("data:")) {
            val bridgeName = blobDownloadBridgeName
            val blobController = blobDownloadController
            if (bridgeName.isNullOrBlank() || blobController == null) {
                // 这里保留明确诊断，区分“命中了 blob/data 下载”与“宿主当前没有可用的 blob 保存接管链”。
                recordDiagnostic(
                    "event=browser_download_skipped reason=missing_blob_capture_support scheme=${Uri.parse(targetUrl).scheme.orEmpty()} url=$targetUrl"
                )
                return null
            }

            // 真机已确认部分导出会直接落到 DownloadListener(blob) 而绕过前置页面桥；
            // enqueue 是当前稳定必经点，因此这里必须再次把 blob/data URL 回送给页面转 base64 保存。
            recordDiagnostic(
                "event=browser_download_delegate_blob_capture scheme=${Uri.parse(targetUrl).scheme.orEmpty()} fileName=$fileName url=$targetUrl"
            )
            blobController.captureFromDownloadListener(
                webView = request.sourceWebView,
                bridgeName = bridgeName,
                request = request,
                diagnosticSink = ::recordDiagnostic
            )
            return BrowserDownloadResult.Delegated(fileName)
        }

        return try {
            val systemRequest = DownloadManager.Request(Uri.parse(targetUrl)).apply {
                setTitle(fileName)
                setDescription(pendingDescription(fileName))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setVisibleInDownloadsUi(true)
                setMimeType(request.mimeType.ifBlank { "application/octet-stream" })

                val cookieHeader = CookieManager.getInstance().getCookie(targetUrl)
                if (!cookieHeader.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookieHeader)
                }

                if (request.userAgent.isNotBlank()) {
                    addRequestHeader("User-Agent", request.userAgent)
                }

                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val downloadId = downloadManager.enqueue(systemRequest)
            recordDiagnostic(
                "event=download_manager_enqueued downloadId=$downloadId fileName=$fileName mime=${request.mimeType.ifBlank { "application/octet-stream" }} " +
                    "url=$targetUrl hasCookie=${!CookieManager.getInstance().getCookie(targetUrl).isNullOrBlank()} hasUserAgent=${request.userAgent.isNotBlank()}"
            )
            BrowserDownloadResult.Started(fileName)
        } catch (error: Exception) {
            recordDiagnostic(
                "event=download_manager_enqueue_failed fileName=$fileName url=$targetUrl error=${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}"
            )
            BrowserDownloadResult.Failed(
                fileName = fileName,
                message = error.message.orEmpty()
            )
        }
    }
}
