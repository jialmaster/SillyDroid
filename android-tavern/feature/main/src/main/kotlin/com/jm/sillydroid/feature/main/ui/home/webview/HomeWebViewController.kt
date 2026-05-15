package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest

class HomeWebViewController(
    private val context: Context,
    private val webViewProvider: () -> WebView,
    private val installSessionPersistence: () -> Unit,
    private val installJavascriptInterfaces: (WebView) -> Unit,
    private val shouldOpenExternally: (Uri) -> Boolean,
    private val openExternalBrowser: (Uri) -> Boolean,
    private val onPageStarted: (String?) -> Unit,
    private val onPageCommitVisible: (String?) -> Unit,
    private val onPageFinished: (String?) -> Unit,
    private val isLocalTavernUrl: (String) -> Boolean,
    private val onMainFrameLocalLoadError: (String) -> Unit,
    private val onRendererGone: (didCrash: Boolean) -> Unit,
    private val onDownloadRequested: (BrowserDownloadRequest) -> Unit,
    private val onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit
) {
    fun configure() {
        val webView = webViewProvider()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        }

        installSessionPersistence()
        installJavascriptInterfaces(webView)
        configureClients(webView)
    }

    private fun configureClients(webView: WebView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUri = request?.url ?: return false
                if (request.isForMainFrame && shouldOpenExternally(targetUri)) {
                    return openExternalBrowser(targetUri)
                }
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val targetUri = url?.let(Uri::parse) ?: return false
                return if (shouldOpenExternally(targetUri)) {
                    openExternalBrowser(targetUri)
                } else {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onPageStarted(url)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                onPageCommitVisible(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onPageFinished(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) {
                    return
                }
                val failingUrl = request.url?.toString().orEmpty()
                if (isLocalTavernUrl(failingUrl)) {
                    onMainFrameLocalLoadError(failingUrl)
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash() == true
                } else {
                    true
                }
                onRendererGone(didCrash)
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownloadRequested(
                BrowserDownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType
                )
            )
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val proxyWebView = WebView(context)
                var handled = false

                fun forwardToBrowser(targetUrl: String?) {
                    if (handled || targetUrl.isNullOrBlank() || targetUrl == "about:blank") {
                        return
                    }

                    handled = true
                    openExternalBrowser(Uri.parse(targetUrl))
                    proxyWebView.stopLoading()
                    proxyWebView.destroy()
                }

                proxyWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        forwardToBrowser(request?.url?.toString())
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        forwardToBrowser(url)
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        forwardToBrowser(url)
                    }
                }

                transport.webView = proxyWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) {
                    return false
                }

                onShowFileChooser(filePathCallback, fileChooserParams)
                return true
            }
        }
    }
}
