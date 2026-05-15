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
    private val pendingDescription: (fileName: String) -> String
) {
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Suppress("DEPRECATION")
    fun enqueue(request: BrowserDownloadRequest): BrowserDownloadResult? {
        val targetUrl = request.url.trim()
        if (targetUrl.isBlank() || targetUrl.startsWith("blob:") || targetUrl.startsWith("data:")) {
            return null
        }

        val fileName = resolveDownloadFileName(
            rawName = URLUtil.guessFileName(targetUrl, request.contentDisposition, request.mimeType),
            fallbackUrl = targetUrl
        )

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

            downloadManager.enqueue(systemRequest)
            BrowserDownloadResult.Started(fileName)
        } catch (error: Exception) {
            BrowserDownloadResult.Failed(
                fileName = fileName,
                message = error.message.orEmpty()
            )
        }
    }
}
