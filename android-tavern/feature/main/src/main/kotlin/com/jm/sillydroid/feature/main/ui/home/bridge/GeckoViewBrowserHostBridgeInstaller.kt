package com.jm.sillydroid.feature.main.ui.home.bridge

import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.BrowserZoomOptions
import com.jm.sillydroid.feature.main.model.download.BlobDownloadSavedFile
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import com.jm.sillydroid.feature.main.ui.home.download.AndroidBlobDownloadBridge
import com.jm.sillydroid.feature.main.ui.home.download.BlobDownloadController
import com.jm.sillydroid.feature.main.ui.home.notification.AndroidSystemNotificationBridge
import com.jm.sillydroid.feature.main.ui.home.notification.SystemNotificationController
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * GeckoView 的宿主桥接安装器。
 *
 * GeckoView 没有 WebView 的 addJavascriptInterface，同名页面桥由 APK 内置 WebExtension 注入，
 * native messaging 只把消息转回现有宿主动作和下载/通知控制器，避免重新实现一套业务规则。
 */
class GeckoViewBrowserHostBridgeInstaller(
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

    private var extension: WebExtension? = null
    private var installedSession: GeckoSession? = null
    private var installStarted = false
    private var pendingReadyCallbacks = mutableListOf<() -> Unit>()
    private var allowedOrigin: String = ""
    private var viewportDensityPort: WebExtension.Port? = null
    private var pendingViewportDensityRequest: ViewportDensityRequest? = null

    private val androidHostBridge by lazy {
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
        )
    }

    private val notificationBridge by lazy {
        AndroidSystemNotificationBridge(
            notificationController = systemNotificationController,
            isHostActive = actions.isHostActive,
            runOnUiThread = actions.runOnUiThread,
            requestNotificationPermission = requestNotificationPermission,
            diagnosticSink = ::recordDiagnostic
        )
    }

    private val blobDownloadBridge by lazy {
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
        )
    }

    override fun install(target: BrowserHostBridgeTarget) {
        install(target) { /* no-op */ }
    }

    override fun install(target: BrowserHostBridgeTarget, onReady: () -> Unit) {
        if (target.browserEngine != BrowserEngine.GECKOVIEW || target.geckoRuntime == null || target.geckoSession == null) {
            recordDiagnostic(
                "event=geckoview_bridge_install_skipped reason=unsupported_target engine=${target.browserEngine.name} surface=${target.surface.javaClass.name}"
            )
            onReady()
            return
        }

        allowedOrigin = target.allowedOrigin
        val currentExtension = extension
        if (currentExtension != null) {
            bindSessionMessageDelegate(target.geckoSession, currentExtension)
            onReady()
            return
        }

        pendingReadyCallbacks += onReady
        if (installStarted) {
            return
        }
        installStarted = true
        target.geckoRuntime.webExtensionController
            .ensureBuiltIn(BRIDGE_EXTENSION_URI, BRIDGE_EXTENSION_ID)
            .accept(
                { installedExtension ->
                    if (installedExtension == null) {
                        installStarted = false
                        recordDiagnostic("event=geckoview_bridge_extension_failed id=$BRIDGE_EXTENSION_ID error=extension_null")
                        drainReadyCallbacks()
                        return@accept
                    }
                    extension = installedExtension
                    bindExtensionMessageDelegate(installedExtension)
                    bindSessionMessageDelegate(target.geckoSession, installedExtension)
                    recordDiagnostic("event=geckoview_bridge_extension_ready id=$BRIDGE_EXTENSION_ID")
                    drainReadyCallbacks()
                },
                { error ->
                    installStarted = false
                    val message = error?.message ?: error?.javaClass?.simpleName ?: "unknown"
                    recordDiagnostic(
                        "event=geckoview_bridge_extension_failed id=$BRIDGE_EXTENSION_ID error=$message"
                    )
                    drainReadyCallbacks()
                }
            )
    }

    override fun close() {
        installedSession = null
        runCatching { viewportDensityPort?.disconnect() }
        viewportDensityPort = null
        pendingReadyCallbacks.clear()
        blobDownloadController.close()
    }

    override fun requestViewportDensityPercent(percent: Int, baseViewportWidthCssPx: Int): Boolean {
        val request = ViewportDensityRequest(
            percent = BrowserZoomOptions.sanitizeViewportDensity(percent),
            baseViewportWidthCssPx = baseViewportWidthCssPx.coerceAtLeast(240)
        )
        pendingViewportDensityRequest = request
        if (!postViewportDensityCommandIfPossible(reason = "host_request", request = request)) {
            recordDiagnostic(
                "event=gecko_viewport_density_pending reason=port_not_ready percent=${request.percent} baseViewportWidthCssPx=${request.baseViewportWidthCssPx}"
            )
        }
        return true
    }

    /** 通过已建立的扩展控制端口请求页面侧记录最终可见性；消息不包含页面内容。 */
    override fun requestDocumentVisibilityDiagnostic(reason: String): Boolean {
        val port = viewportDensityPort ?: run {
            recordDiagnostic("event=gecko_document_visibility_command_skipped reason=port_not_ready source=$reason")
            return false
        }
        val payload = JSONObject()
            .put("action", "recordDocumentVisibility")
            .put("reason", reason)
        return runCatching {
            port.postMessage(payload)
        }.onSuccess {
            recordDiagnostic("event=gecko_document_visibility_command_sent source=$reason")
        }.onFailure { error ->
            if (viewportDensityPort === port) {
                viewportDensityPort = null
            }
            recordDiagnostic(
                "event=gecko_document_visibility_command_failed source=$reason error=${error.message ?: error.javaClass.simpleName}"
            )
        }.isSuccess
    }

    private fun bindExtensionMessageDelegate(installedExtension: WebExtension) {
        installedExtension.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender
                ): GeckoResult<Any>? {
                    return handleNativeMessage(nativeApp, message, sender)
                }

                override fun onConnect(port: WebExtension.Port) {
                    bindViewportDensityPort(port)
                }
            },
            NATIVE_APP_NAME
        )
    }

    private fun bindSessionMessageDelegate(session: GeckoSession, installedExtension: WebExtension) {
        if (installedSession === session) {
            return
        }
        session.webExtensionController.setMessageDelegate(
            installedExtension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender
                ): GeckoResult<Any>? {
                    return handleNativeMessage(nativeApp, message, sender)
                }

                override fun onConnect(port: WebExtension.Port) {
                    bindViewportDensityPort(port)
                }
            },
            NATIVE_APP_NAME
        )
        installedSession = session
        recordDiagnostic("event=geckoview_bridge_session_bound id=$BRIDGE_EXTENSION_ID")
    }

    private fun handleNativeMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender
    ): GeckoResult<Any>? {
        if (nativeApp != NATIVE_APP_NAME) {
            return GeckoResult.fromValue(GeckoNativeMessageCodec.errorResponse("unexpected_native_app"))
        }
        if (!isTrustedSender(sender)) {
            recordDiagnostic(
                "event=geckoview_bridge_message_rejected reason=untrusted_sender url=${sender.url.orEmpty()} isTopLevel=${sender.isTopLevel}"
            )
            return GeckoResult.fromValue(GeckoNativeMessageCodec.errorResponse("untrusted_sender"))
        }

        val payload = GeckoNativeMessageCodec.toPayloadOrNull(message)
            ?: return GeckoResult.fromValue(GeckoNativeMessageCodec.errorResponse("invalid_payload"))
        val action = payload.action
        val data = payload.payload
        recordDiagnostic("event=geckoview_bridge_message action=$action")

        return GeckoResult.fromValue(
            try {
                GeckoNativeMessageCodec.successResponse(handleAction(action = action, data = data))
            } catch (error: Exception) {
                recordDiagnostic(
                    "event=geckoview_bridge_message_failed action=$action error=${error.message ?: error.javaClass.simpleName}"
                )
                GeckoNativeMessageCodec.errorResponse(error.message ?: error.javaClass.simpleName)
            }
        )
    }

    private fun handleAction(action: String, data: Any?): Any {
        return when (action) {
            "openSettings" -> androidHostBridge.openSettings()
            "showFloatingLogsBubble" -> androidHostBridge.showFloatingLogsBubble()
            "openCurrentPageInBrowser" -> androidHostBridge.openCurrentPageInBrowser()
            "setFloatingLogsBubbleEnabled" -> androidHostBridge.setFloatingLogsBubbleEnabled(data.asBoolean())
            "setWebViewPullRefreshEnabled" -> androidHostBridge.setWebViewPullRefreshEnabled(data.asBoolean())
            "setSystemBarsBackgroundColor" -> androidHostBridge.setSystemBarsBackgroundColor(data.asString())
            "setSystemBarsBackgroundColors" -> {
                androidHostBridge.setSystemBarsBackgroundColors(
                    GeckoNativeMessageCodec.objectString(data, "statusBarColor"),
                    GeckoNativeMessageCodec.objectString(data, "navigationBarColor")
                )
            }
            "reloadTavern" -> androidHostBridge.reloadTavern()
            "getHostVersionInfo" -> androidHostBridge.getHostVersionInfo()
            "recordWebPerformanceDiagnostic" -> androidHostBridge.recordWebPerformanceDiagnostic(data.asString())
            "notification.show" -> notificationBridge.showNotification(data.asString())
            "notification.playAlertSound" -> notificationBridge.playAlertSound()
            "notification.permissionState" -> notificationBridge.permissionState()
            "notification.requestPermission" -> notificationBridge.requestPermission()
            "download.preparing" -> blobDownloadBridge.onBlobDownloadPreparing(data.asString())
            "download.saveBase64File" -> blobDownloadBridge.saveBase64File(data.asString())
            "download.beginBase64File" -> blobDownloadBridge.beginBase64File(data.asString())
            "download.appendBase64FileChunk" -> blobDownloadBridge.appendBase64FileChunk(data.asString())
            "download.completeBase64File" -> blobDownloadBridge.completeBase64File(data.asString())
            "download.cancelBase64File" -> blobDownloadBridge.cancelBase64File(data.asString())
            "download.reportFailure" -> blobDownloadBridge.reportDownloadFailure(data.asString())
            "download.recordDiagnostic" -> {
                recordDiagnostic(data.asString())
                true
            }
            else -> false
        }
    }

    private fun bindViewportDensityPort(port: WebExtension.Port) {
        if (!isTrustedPortSender(port.sender)) {
            recordDiagnostic(
                "event=gecko_viewport_density_port_rejected reason=untrusted_sender url=${port.sender.url.orEmpty()} environmentType=${port.sender.environmentType}"
            )
            runCatching { port.disconnect() }
            return
        }
        viewportDensityPort = port
        port.setDelegate(
            object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    recordDiagnostic(
                        "event=gecko_viewport_density_port_message payload=${message.toString().replaceWhitespaceForDiagnostic()}"
                    )
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (viewportDensityPort === port) {
                        viewportDensityPort = null
                    }
                    recordDiagnostic("event=gecko_viewport_density_port_disconnected")
                }
            }
        )
        recordDiagnostic(
            "event=gecko_viewport_density_port_connected environmentType=${port.sender.environmentType} url=${port.sender.url.orEmpty()}"
        )
        pendingViewportDensityRequest?.let { request ->
            postViewportDensityCommandIfPossible(reason = "port_connected", request = request)
        }
    }

    private fun postViewportDensityCommandIfPossible(reason: String, request: ViewportDensityRequest): Boolean {
        val port = viewportDensityPort ?: return false
        val payload = JSONObject()
            .put("action", "setViewportDensity")
            .put("percent", request.percent)
            .put("baseViewportWidthCssPx", request.baseViewportWidthCssPx)
            .put("reason", reason)
        return runCatching {
            port.postMessage(payload)
        }.onSuccess {
            recordDiagnostic(
                "event=gecko_viewport_density_command_sent reason=$reason percent=${request.percent} baseViewportWidthCssPx=${request.baseViewportWidthCssPx}"
            )
        }.onFailure { error ->
            if (viewportDensityPort === port) {
                viewportDensityPort = null
            }
            recordDiagnostic(
                "event=gecko_viewport_density_command_failed reason=$reason percent=${request.percent} error=${error.message ?: error.javaClass.simpleName}"
            )
        }.isSuccess
    }

    private fun isTrustedSender(sender: WebExtension.MessageSender): Boolean {
        if (isTrustedExtensionSender(sender)) {
            return true
        }
        if (sender.session !== installedSession) {
            return false
        }
        if (!sender.isTopLevel) {
            return false
        }
        val localOrigin = allowedOrigin.trim().trimEnd('/')
        val senderUrl = sender.url.orEmpty()
        return senderUrl == localOrigin ||
            senderUrl == "$localOrigin/" ||
            senderUrl.startsWith("$localOrigin/#") ||
            senderUrl.startsWith("$localOrigin/?") ||
            senderUrl.startsWith("$localOrigin/")
    }

    private fun isTrustedPortSender(sender: WebExtension.MessageSender): Boolean {
        if (isTrustedExtensionSender(sender)) {
            return true
        }
        return isTrustedSender(sender)
    }

    private fun isTrustedExtensionSender(sender: WebExtension.MessageSender): Boolean {
        return sender.environmentType == WebExtension.MessageSender.ENV_TYPE_EXTENSION &&
            sender.webExtension === extension
    }

    private fun drainReadyCallbacks() {
        val callbacks = pendingReadyCallbacks.toList()
        pendingReadyCallbacks.clear()
        callbacks.forEach { callback -> runCatching(callback) }
    }

    private fun Any?.asString(): String = when (this) {
        null -> ""
        is String -> this
        is JSONObject -> this.toString()
        else -> toString()
    }

    private data class ViewportDensityRequest(
        val percent: Int,
        val baseViewportWidthCssPx: Int
    )

    private fun Any?.asBoolean(): Boolean = when (this) {
        is Boolean -> this
        is String -> equals("true", ignoreCase = true)
        is Number -> toInt() != 0
        else -> false
    }

    private fun recordDiagnostic(body: String) {
        runCatching { diagnosticSink(body) }
    }

    private fun String.replaceWhitespaceForDiagnostic(): String {
        return replace(Regex("\\s+"), "_").take(180)
    }

    private companion object {
        private const val BRIDGE_EXTENSION_ID = "sillydroid-geckoview-bridge@jlmaster.online"
        private const val BRIDGE_EXTENSION_URI = "resource://android/assets/geckoview/sillydroid-host-bridge/"
        private const val NATIVE_APP_NAME = "sillydroid_host"
    }
}

internal object GeckoNativeMessageCodec {
    data class NativeMessagePayload(
        val action: String,
        val payload: Any?
    )

    fun successResponse(result: Any?): GeckoBundle {
        return GeckoBundle(2).apply {
            putBoolean("ok", true)
            // Gecko native messaging 只能稳定回传 GeckoBundle/基础类型；不能把 org.json.JSONObject 直接塞回调。
            putNativeValue("result", result)
        }
    }

    fun errorResponse(message: String): GeckoBundle {
        return GeckoBundle(2).apply {
            putBoolean("ok", false)
            putString("error", message)
        }
    }

    fun toPayloadOrNull(value: Any): NativeMessagePayload? {
        return when (value) {
            is GeckoBundle -> NativeMessagePayload(
                action = value.getString("action", "").trim(),
                payload = value.get("payload")
            )
            is JSONObject -> NativeMessagePayload(
                action = value.optString("action").trim(),
                payload = value.opt("payload")
            )
            is String -> runCatching {
                JSONObject(value).let { json ->
                    NativeMessagePayload(
                        action = json.optString("action").trim(),
                        payload = json.opt("payload")
                    )
                }
            }.getOrNull()
            else -> null
        }?.takeIf { payload -> payload.action.isNotBlank() }
    }

    fun objectString(value: Any?, key: String): String {
        return when (value) {
            is GeckoBundle -> value.getString(key, "")
            is JSONObject -> value.optString(key)
            is String -> runCatching { JSONObject(value).optString(key) }.getOrDefault("")
            else -> ""
        }.trim()
    }

    private fun GeckoBundle.putNativeValue(key: String, value: Any?) {
        when (value) {
            null -> putString(key, "")
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putDouble(key, value.toDouble())
            is Double -> putDouble(key, value)
            is GeckoBundle -> putBundle(key, value)
            is JSONObject -> putString(key, value.toString())
            else -> putString(key, value.toString())
        }
    }
}
