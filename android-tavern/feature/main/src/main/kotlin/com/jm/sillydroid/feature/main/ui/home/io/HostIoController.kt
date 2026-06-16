package com.jm.sillydroid.feature.main.ui.home.io

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.domain.notification.HostNotificationService
import com.jm.sillydroid.domain.notification.HostDownloadNotificationCoordinator
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserResponseDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadResult
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeNames
import com.jm.sillydroid.feature.main.ui.home.download.BlobDownloadController
import com.jm.sillydroid.feature.main.ui.home.download.BrowserDownloadController
import com.jm.sillydroid.feature.main.ui.home.notification.SystemNotificationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 把宿主侧 IO 相关的 launcher 与 controller 全部抽出来：
 * - 浏览器下载（WebView 普通链接）
 * - blob 下载桥（WebView 内 JS 触发）
 * - 系统通知通道 + Android 13+ 通知运行时权限
 * - WebView 文件选择器 launcher
 *
 * 必须在 Activity onCreate（STARTED 之前）构造，因为内部会调用 registerForActivityResult。
 */
class HostIoController(
    private val activity: AppCompatActivity,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val hostPreferencesRepository: HostPreferencesRepository,
    private val hostNotificationService: HostNotificationService,
    private val hostDownloadNotificationCoordinator: HostDownloadNotificationCoordinator,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val blobDownloadBridgeName: String = BrowserHostBridgeNames.DEFAULT_BLOB_DOWNLOAD_BRIDGE_NAME,
    private val downloadDiagnosticSink: (String) -> Unit = {},
    private val hostDiagnosticSink: (category: String, body: String) -> Unit = { _, _ -> },
) {
    data class BrowserFileChooserRequest(
        val intent: Intent,
        val acceptTypes: Array<String>,
        val allowMultiple: Boolean,
        val forceAcceptTokenSelectionFilter: Boolean = false
    )

    private data class FileChooserLaunchRequest(
        val intent: Intent,
        val selectionFilter: ((Uri) -> Boolean)? = null
    )

    private data class FileChooserResolvedResult(
        val uris: Array<Uri>?,
        val rawCount: Int,
        val acceptedCount: Int,
        val rejectedByFilter: Boolean
    )

    private val downloadManager by lazy { activity.getSystemService(DownloadManager::class.java) }

    private fun recordDownloadDiagnostic(body: String) {
        runCatching { downloadDiagnosticSink(body) }
    }

    private fun recordFileChooserDiagnostic(body: String) {
        runCatching { hostDiagnosticSink("file_chooser", body) }
    }

    val browserDownloadController: BrowserDownloadController by lazy {
        BrowserDownloadController(
            downloadManager = downloadManager,
            pendingDescription = { fileName -> activity.getString(R.string.download_status_pending, fileName) },
            blobDownloadController = blobDownloadController,
            blobDownloadBridgeName = blobDownloadBridgeName,
            diagnosticSink = ::recordDownloadDiagnostic
        )
    }

    val blobDownloadController: BlobDownloadController by lazy {
        BlobDownloadController(
            contentResolver = activity.contentResolver,
            // GeckoView 的 native messaging 不适合承载超大 base64 单包；blob/data 导出先分块落在宿主 cache，
            // 完成后再流式写入系统下载目录，避免导出大文件时挤爆 Java heap 或消息序列化上限。
            chunkTempDirectory = java.io.File(activity.cacheDir, "blob-download-chunks")
        )
    }

    val systemNotificationController: SystemNotificationController by lazy {
        SystemNotificationController(
            hostNotificationService = hostNotificationService,
            smallIconResId = android.R.drawable.stat_notify_chat
        )
    }

    // Android 13+ 的宿主通知需要显式运行时授权，否则 NotificationManager 会直接拒发。
    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op；用户授权与否都由后续真实通知时再决定 */ }

    private var pendingFileChooserCallback: ((Array<Uri>?) -> Unit)? = null
    private var pendingFileChooserSelectionFilter: ((Uri) -> Boolean)? = null
    private var pendingFileChooserSource: String = ""
    private val fileChooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        val source = pendingFileChooserSource.ifBlank { "unknown" }
        pendingFileChooserCallback = null
        val selectionFilter = pendingFileChooserSelectionFilter
        pendingFileChooserSelectionFilter = null
        pendingFileChooserSource = ""
        val resolvedResult = resolveFileChooserResult(result.resultCode, result.data, selectionFilter)
        recordFileChooserDiagnostic(
            "event=file_chooser_result source=$source resultCode=${result.resultCode} " +
                "rawCount=${resolvedResult.rawCount} acceptedCount=${resolvedResult.acceptedCount} " +
                "rejectedByFilter=${resolvedResult.rejectedByFilter}"
        )
        callback.invoke(resolvedResult.uris)
    }

    fun ensureNotificationChannel() {
        hostNotificationService.ensureChannels()
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun launchFileChooser(fileChooserParams: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>) {
        val request = BrowserFileChooserRequest(
            intent = fileChooserParams.createIntent(),
            acceptTypes = fileChooserParams.acceptTypes,
            allowMultiple = fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        )
        launchBrowserFileChooser(
            source = "webview",
            request = request,
            callback = { uris -> callback.onReceiveValue(uris) }
        )
    }

    fun launchFileChooser(request: BrowserFileChooserRequest, callback: (Array<Uri>?) -> Unit) {
        launchBrowserFileChooser(
            source = "geckoview",
            request = request,
            callback = callback
        )
    }

    private fun launchBrowserFileChooser(
        source: String,
        request: BrowserFileChooserRequest,
        callback: (Array<Uri>?) -> Unit
    ) {
        val launchRequest = runCatching { buildFileChooserLaunchRequest(request) }
            .onFailure { error ->
                recordFileChooserDiagnostic(
                    "event=file_chooser_prepare_failed source=$source error=${error.javaClass.simpleName} " +
                        "message=${normalizeDiagnosticValue(error.message)} acceptTypes=${request.acceptTypes.joinToString(separator = ",").ifBlank { "-" }}"
                )
                callback.invoke(null)
            }
            .getOrNull() ?: return
        pendingFileChooserCallback?.invoke(null)
        pendingFileChooserCallback = callback
        pendingFileChooserSelectionFilter = launchRequest.selectionFilter
        pendingFileChooserSource = source
        recordFileChooserDiagnostic(
            "event=file_chooser_launch_requested source=$source action=${normalizeDiagnosticValue(launchRequest.intent.action)} " +
                "type=${normalizeDiagnosticValue(launchRequest.intent.type)} allowMultiple=${request.allowMultiple} " +
                "selectionFilter=${launchRequest.selectionFilter != null} acceptTypes=${request.acceptTypes.joinToString(separator = ",").ifBlank { "-" }}"
        )
        try {
            fileChooserLauncher.launch(launchRequest.intent)
        } catch (error: ActivityNotFoundException) {
            launchFallbackFileChooser(source = source, request = request, launchRequest = launchRequest, error = error, callback = callback)
        } catch (error: IllegalStateException) {
            launchFallbackFileChooser(source = source, request = request, launchRequest = launchRequest, error = error, callback = callback)
        } catch (error: SecurityException) {
            launchFallbackFileChooser(source = source, request = request, launchRequest = launchRequest, error = error, callback = callback)
        }
    }

    fun cancelPendingFileChooser() {
        if (pendingFileChooserCallback != null) {
            recordFileChooserDiagnostic(
                "event=file_chooser_cancel_pending source=${pendingFileChooserSource.ifBlank { "unknown" }}"
            )
        }
        pendingFileChooserCallback?.invoke(null)
        pendingFileChooserCallback = null
        pendingFileChooserSelectionFilter = null
        pendingFileChooserSource = ""
    }

    private fun failPendingFileChooserLaunch(
        source: String,
        error: Exception,
        callback: (Array<Uri>?) -> Unit
    ) {
        pendingFileChooserCallback = null
        pendingFileChooserSelectionFilter = null
        pendingFileChooserSource = ""
        recordFileChooserDiagnostic(
            "event=file_chooser_launch_failed source=$source error=${error.javaClass.simpleName} " +
                "message=${normalizeDiagnosticValue(error.message)}"
        )
        Toast.makeText(activity, R.string.file_chooser_open_failed, Toast.LENGTH_LONG).show()
        callback.invoke(null)
    }

    private fun launchFallbackFileChooser(
        source: String,
        request: BrowserFileChooserRequest,
        launchRequest: FileChooserLaunchRequest,
        error: Exception,
        callback: (Array<Uri>?) -> Unit
    ) {
        recordFileChooserDiagnostic(
            "event=file_chooser_primary_launch_failed source=$source error=${error.javaClass.simpleName} " +
                "message=${normalizeDiagnosticValue(error.message)} fallbackAction=${Intent.ACTION_GET_CONTENT}"
        )
        val fallbackIntent = BrowserFileChooserIntentFallback.buildGetContentFallback(
            sourceIntent = launchRequest.intent,
            allowMultiple = request.allowMultiple
        )
        recordFileChooserDiagnostic(
            "event=file_chooser_fallback_launch_requested source=$source action=${normalizeDiagnosticValue(fallbackIntent.action)} " +
                "type=${normalizeDiagnosticValue(fallbackIntent.type)} allowMultiple=${request.allowMultiple} " +
                "selectionFilter=${launchRequest.selectionFilter != null} acceptTypes=${request.acceptTypes.joinToString(separator = ",").ifBlank { "-" }}"
        )
        try {
            fileChooserLauncher.launch(fallbackIntent)
        } catch (fallbackError: ActivityNotFoundException) {
            failPendingFileChooserLaunch(source = source, error = fallbackError, callback = callback)
        } catch (fallbackError: IllegalStateException) {
            failPendingFileChooserLaunch(source = source, error = fallbackError, callback = callback)
        } catch (fallbackError: SecurityException) {
            failPendingFileChooserLaunch(source = source, error = fallbackError, callback = callback)
        }
    }

    @Suppress("DEPRECATION")
    fun handlePageDownload(request: BrowserDownloadRequest) {
        // 这里把 DownloadListener 进入宿主后的第一手参数再记一次，便于和 WebView 侧“监听是否触发”对齐。
        recordDownloadDiagnostic(
            "event=handle_page_download url=${request.url} mime=${request.mimeType.ifBlank { "-" }} " +
                "contentDisposition=${request.contentDisposition.ifBlank { "-" }}"
        )
        val handledByBlobCapture = maybeHandleBlobDownload(request)
        if (handledByBlobCapture) {
            return
        }
        when (val result = browserDownloadController.enqueue(request)) {
            is BrowserDownloadResult.Started -> {
                hostDownloadNotificationCoordinator.recordBrowserDownloadStarted(
                    downloadId = result.downloadId,
                    fileName = result.fileName,
                    mimeType = result.mimeType,
                    localUri = result.localUri
                )
                recordDownloadDiagnostic("event=handle_page_download_started fileName=${result.fileName}")
                Toast.makeText(
                    activity,
                    activity.getString(R.string.download_started, result.fileName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            is BrowserDownloadResult.Delegated -> {
                recordDownloadDiagnostic("event=handle_page_download_delegated fileName=${result.fileName}")
            }
            is BrowserDownloadResult.Failed -> {
                recordDownloadDiagnostic(
                    "event=handle_page_download_failed fileName=${result.fileName} error=${result.message.ifBlank { activity.getString(R.string.download_failed_unknown) }}"
                )
                showDownloadFailure(
                    DownloadFailureReport(
                        fileName = result.fileName,
                        message = result.message.ifBlank { activity.getString(R.string.download_failed_unknown) }
                    )
                )
            }
            null -> {
                recordDownloadDiagnostic("event=handle_page_download_noop reason=controller_returned_null")
            }
        }
    }

    @Suppress("DEPRECATION")
    fun handleBrowserResponseDownload(request: BrowserResponseDownloadRequest) {
        val rawFileName = URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        val fileName = blobDownloadController.resolveFileName(rawFileName)
        val mimeType = request.mimeType.ifBlank { "application/octet-stream" }
        // GeckoView 已经把下载响应体交给宿主；这里直接落 MediaStore，避免 DownloadManager 再用裸 URL 重拉导致 Cookie/会话丢失。
        recordDownloadDiagnostic(
            "event=handle_response_download_started fileName=$fileName url=${request.url} mime=$mimeType " +
                "contentDisposition=${request.contentDisposition.ifBlank { "-" }}"
        )
        Toast.makeText(
            activity,
            activity.getString(R.string.download_status_saving, fileName),
            Toast.LENGTH_SHORT
        ).show()

        scope.launch {
            val result = runCatching {
                withContext(dispatchers.io) {
                    blobDownloadController.persistStream(
                        rawFileName = rawFileName,
                        fallbackUrl = request.url,
                        mimeType = mimeType,
                        inputStream = request.body
                    )
                }
            }
            result.onSuccess { savedFile ->
                hostDownloadNotificationCoordinator.postBrowserDownloadSaved(
                    fileName = savedFile.fileName,
                    mimeType = savedFile.mimeType,
                    contentUri = savedFile.contentUri,
                    displayPath = savedFile.displayPath
                )
                recordDownloadDiagnostic(
                    "event=handle_response_download_saved fileName=${savedFile.fileName} uri=${savedFile.contentUri}"
                )
                Toast.makeText(
                    activity,
                    activity.getString(R.string.download_saved, savedFile.fileName),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                val message = error.message ?: activity.getString(R.string.download_failed_unknown)
                recordDownloadDiagnostic(
                    "event=handle_response_download_failed fileName=$fileName error=$message"
                )
                showDownloadFailure(
                    DownloadFailureReport(
                        fileName = fileName,
                        message = message
                    )
                )
            }
        }
    }

    private fun maybeHandleBlobDownload(request: BrowserDownloadRequest): Boolean {
        val scheme = Uri.parse(request.url).scheme.orEmpty()
        if (!scheme.equals("blob", ignoreCase = true) && !scheme.equals("data", ignoreCase = true)) {
            return false
        }

        // 这条路径只在真机已经确认命中的 DownloadListener(blob/data) 上执行：
        // 当前页面有时不会被前置 anchor 拦截命中，所以宿主需要在这里再次要求页面把同一个 URL 回传成 base64。
        recordDownloadDiagnostic(
            "event=handle_page_download_delegate_blob_capture scheme=$scheme url=${request.url}"
        )
        val sourceWebView = request.sourceWebView
        if (sourceWebView == null) {
            recordDownloadDiagnostic(
                "event=handle_page_download_delegate_blob_capture_skipped reason=missing_source_webview scheme=$scheme url=${request.url}"
            )
            return false
        }
        blobDownloadController.captureFromDownloadListener(
            webView = sourceWebView,
            bridgeName = blobDownloadBridgeName,
            request = request,
            diagnosticSink = ::recordDownloadDiagnostic
        )
        return true
    }

    fun showDownloadFailure(report: DownloadFailureReport) {
        recordDownloadDiagnostic(
            "event=show_download_failure fileName=${report.fileName} error=${report.message.ifBlank { activity.getString(R.string.download_failed_unknown) }}"
        )
        Toast.makeText(activity, buildDownloadFailureMessage(report), Toast.LENGTH_LONG).show()
    }

    private fun buildDownloadFailureMessage(report: DownloadFailureReport): String {
        val details = report.message.trim().ifBlank { activity.getString(R.string.download_failed_unknown) }
        return activity.getString(R.string.download_failed, report.fileName, details)
    }

    private fun buildFileChooserLaunchRequest(request: BrowserFileChooserRequest): FileChooserLaunchRequest {
        val chooserIntent = request.intent
        chooserIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.allowMultiple)
        val acceptTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(request.acceptTypes)
        if (BrowserFileChooserSelectionPolicy.shouldForceSelectionFilter(
                acceptTokens = acceptTokens,
                forceAcceptTokenSelectionFilter = request.forceAcceptTokenSelectionFilter
            )
        ) {
            // GeckoView 只把 MIME 交给 Android chooser 会丢掉 .json/.charx 等扩展过滤；
            // 因此先放开系统选择器，再按网页原始 accept token 做选后校验，避免合法导入文件被置灰。
            applyAnyFileTypeToFileChooserIntent(chooserIntent)
            return FileChooserLaunchRequest(
                intent = chooserIntent,
                selectionFilter = buildAcceptTokenSelectionFilter(acceptTokens)
            )
        }

        if (acceptTokens.none { acceptToken -> acceptToken.equals(".jsonl", ignoreCase = true) }) {
            return FileChooserLaunchRequest(intent = chooserIntent)
        }

        // 旧路径继续依赖系统 MIME 过滤；只有用户显式开启“无限制文件扩展名导入选择”后，
        // 才切到放开 chooser + 选后校验的 jsonl 兼容链，避免默认行为偏离原网页 input 语义。
        if (!hostPreferencesRepository.unrestrictedFileImportSelectionEnabled) {
            return FileChooserLaunchRequest(
                intent = applyJsonlMimeAliasesToFileChooserIntent(chooserIntent, acceptTokens)
            )
        }

        // 真机文件管理器会把 .jsonl 标成 bin/octet-stream，并在 MIME 过滤阶段直接置灰；
        // 因此 jsonl 分支不能继续依赖 Android chooser 过滤，而要放开选择后再按原始 accept 校验。
        applyAnyFileTypeToFileChooserIntent(chooserIntent)
        return FileChooserLaunchRequest(
            intent = chooserIntent,
            selectionFilter = buildAcceptTokenSelectionFilter(acceptTokens)
        )
    }

    @Suppress("DEPRECATION")
    private fun applyJsonlMimeAliasesToFileChooserIntent(intent: Intent, acceptTokens: List<String>): Intent {
        val requestedMimeTypes = LinkedHashSet<String>()
        acceptTokens
            .filterNot { acceptToken -> acceptToken.startsWith(".") }
            .forEach { acceptToken -> requestedMimeTypes += acceptToken }
        requestedMimeTypes += "application/x-ndjson"
        requestedMimeTypes += "application/jsonl"
        requestedMimeTypes += "application/json"
        requestedMimeTypes += "text/plain"
        return applyMimeTypesToFileChooserIntent(intent, requestedMimeTypes.toTypedArray())
    }

    @Suppress("DEPRECATION")
    private fun applyAnyFileTypeToFileChooserIntent(intent: Intent): Intent {
        val targetIntent = if (intent.action == Intent.ACTION_CHOOSER) {
            val chooserTarget = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (chooserTarget != null) {
                Intent(chooserTarget).also { mutableTarget ->
                    intent.putExtra(Intent.EXTRA_INTENT, mutableTarget)
                }
            } else {
                intent
            }
        } else {
            intent
        }
        targetIntent.type = "*/*"
        targetIntent.removeExtra(Intent.EXTRA_MIME_TYPES)
        return intent
    }

    @Suppress("DEPRECATION")
    private fun applyMimeTypesToFileChooserIntent(intent: Intent, mimeTypes: Array<String>): Intent {
        val targetIntent = if (intent.action == Intent.ACTION_CHOOSER) {
            val chooserTarget = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (chooserTarget != null) {
                Intent(chooserTarget).also { mutableTarget ->
                    intent.putExtra(Intent.EXTRA_INTENT, mutableTarget)
                }
            } else {
                intent
            }
        } else {
            intent
        }
        if (mimeTypes.size == 1) {
            targetIntent.type = mimeTypes.single()
            targetIntent.removeExtra(Intent.EXTRA_MIME_TYPES)
            return intent
        }
        targetIntent.type = "*/*"
        targetIntent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        return intent
    }

    private fun buildAcceptTokenSelectionFilter(acceptTokens: List<String>): (Uri) -> Boolean {
        val normalizedAcceptTokens = acceptTokens.map { acceptToken -> acceptToken.trim() }.filter { acceptToken -> acceptToken.isNotEmpty() }
        return selectionFilter@{ uri ->
            val displayName = resolveDisplayName(uri)
            val mimeType = activity.contentResolver.getType(uri)?.trim().orEmpty()
            BrowserFileChooserSelectionPolicy.accepts(
                acceptTokens = normalizedAcceptTokens,
                displayName = displayName,
                mimeType = mimeType
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return activity.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.trim()?.takeIf { name -> name.isNotEmpty() }
            } else {
                null
            }
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { name -> name.isNotEmpty() }
    }

    private fun resolveFileChooserResult(
        resultCode: Int,
        data: Intent?,
        selectionFilter: ((Uri) -> Boolean)?
    ): FileChooserResolvedResult {
        if (resultCode != Activity.RESULT_OK) {
            return FileChooserResolvedResult(
                uris = null,
                rawCount = 0,
                acceptedCount = 0,
                rejectedByFilter = false
            )
        }
        val selectedUris = mutableListOf<Uri>()
        val clipData = data?.clipData
        if (clipData != null) {
            repeat(clipData.itemCount) { index ->
                clipData.getItemAt(index)?.uri?.let(selectedUris::add)
            }
        } else {
            data?.data?.let(selectedUris::add)
        }
        if (selectedUris.isEmpty()) {
            return FileChooserResolvedResult(
                uris = emptyArray(),
                rawCount = 0,
                acceptedCount = 0,
                rejectedByFilter = false
            )
        }
        val acceptedUris = selectionFilter?.let { filter -> selectedUris.filter(filter) } ?: selectedUris
        if (acceptedUris.isEmpty()) {
            return FileChooserResolvedResult(
                uris = null,
                rawCount = selectedUris.size,
                acceptedCount = 0,
                rejectedByFilter = selectionFilter != null
            )
        }
        return FileChooserResolvedResult(
            uris = acceptedUris.toTypedArray(),
            rawCount = selectedUris.size,
            acceptedCount = acceptedUris.size,
            rejectedByFilter = selectionFilter != null && acceptedUris.size < selectedUris.size
        )
    }
}
