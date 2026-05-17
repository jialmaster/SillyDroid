package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录一行 WebView JS 报错/加载错误。实现方负责写盘、加时间戳。
 */
fun interface WebViewJsErrorSink {
    fun append(line: String)
}

class HomeWebViewController(
    private val context: Context,
    private val webViewProvider: () -> WebView,
    private val installSessionPersistence: () -> Unit,
    private val installJavascriptInterfaces: (WebView) -> Unit,
    private val shouldOpenExternally: (Uri) -> Boolean,
    private val openExternalBrowser: (Uri) -> Boolean,
    private val onPageStarted: (WebView, String?) -> Unit,
    private val onPageCommitVisible: (WebView, String?) -> Unit,
    private val onPageFinished: (WebView, String?) -> Unit,
    private val isLocalTavernUrl: (String) -> Boolean,
    private val onMainFrameLocalLoadError: (String) -> Unit,
    private val onRendererGone: (didCrash: Boolean) -> Unit,
    private val onDownloadRequested: (BrowserDownloadRequest) -> Unit,
    private val onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    private val jsErrorSink: WebViewJsErrorSink = WebViewJsErrorSink { /* no-op */ }
) {
    private val jsErrorTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun jsErrorTimestamp(): String = jsErrorTimestampFormat.format(Date())

    private fun writeJsErrorLine(category: String, body: String) {
        // 以一行为单位写入；多行内容 (堆栈) 内部已包含 \n，末尾填一个足以让 tail 可读。
        val safeBody = body.replace("\r\n", "\n").trim('\n')
        val line = "[${jsErrorTimestamp()}] [$category] $safeBody\n"
        runCatching { jsErrorSink.append(line) }
    }
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
                view?.let { currentWebView ->
                    this@HomeWebViewController.onPageStarted(currentWebView, url)
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                view?.let { currentWebView ->
                    this@HomeWebViewController.onPageCommitVisible(currentWebView, url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.let { currentWebView ->
                    this@HomeWebViewController.onPageFinished(currentWebView, url)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request == null) return
                val failingUrl = request.url?.toString().orEmpty()
                val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode else null
                val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error?.description?.toString()
                } else {
                    null
                }
                val mainFrameTag = if (request.isForMainFrame) "main" else "sub"
                writeJsErrorLine(
                    category = "net-error",
                    body = "$mainFrameTag method=${request.method} code=${errorCode ?: "?"} url=$failingUrl desc=${description.orEmpty()}"
                )
                if (request.isForMainFrame && isLocalTavernUrl(failingUrl)) {
                    onMainFrameLocalLoadError(failingUrl)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request == null || errorResponse == null) return
                val failingUrl = request.url?.toString().orEmpty()
                val mainFrameTag = if (request.isForMainFrame) "main" else "sub"
                writeJsErrorLine(
                    category = "http-error",
                    body = "$mainFrameTag method=${request.method} status=${errorResponse.statusCode} url=$failingUrl reason=${errorResponse.reasonPhrase.orEmpty()}"
                )
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

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val message = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val sourceId = message.sourceId().orEmpty()
                    val lineNumber = message.lineNumber()
                    writeJsErrorLine(
                        category = "console",
                        body = "$sourceId:$lineNumber ${message.message().orEmpty()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }
}
