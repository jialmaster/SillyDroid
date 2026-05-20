package com.jm.sillydroid.feature.main.model.download

import android.webkit.WebView

data class BrowserDownloadRequest(
    val sourceWebView: WebView,
    val url: String,
    val userAgent: String,
    val contentDisposition: String,
    val mimeType: String
)

sealed interface BrowserDownloadResult {
    data class Started(val fileName: String) : BrowserDownloadResult
    data class Delegated(val fileName: String) : BrowserDownloadResult
    data class Failed(val fileName: String, val message: String) : BrowserDownloadResult
}

data class BlobDownloadRequest(
    val fileName: String,
    val mimeType: String,
    val base64Data: String
)

data class DownloadFailureReport(
    val fileName: String,
    val message: String
)
