package com.jm.sillydroid.feature.main.ui.home.io

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.main.MainActivity
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadResult
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import com.jm.sillydroid.feature.main.ui.home.download.BlobDownloadController
import com.jm.sillydroid.feature.main.ui.home.download.BrowserDownloadController
import com.jm.sillydroid.feature.main.ui.home.notification.SystemNotificationController

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
    private val blobDownloadBridgeName: String,
    private val downloadDiagnosticSink: (String) -> Unit = {},
) {
    private data class FileChooserLaunchRequest(
        val intent: Intent,
        val selectionFilter: ((Uri) -> Boolean)? = null
    )

    private val downloadManager by lazy { activity.getSystemService(DownloadManager::class.java) }

    private fun recordDownloadDiagnostic(body: String) {
        runCatching { downloadDiagnosticSink(body) }
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
        BlobDownloadController(activity.contentResolver)
    }

    val systemNotificationController: SystemNotificationController by lazy {
        SystemNotificationController(
            context = activity,
            channelId = runtimeConfigRepository.systemNotificationChannelId,
            channelTitle = activity.getString(R.string.system_notification_channel_title),
            channelDescription = activity.getString(R.string.system_notification_channel_description),
            smallIconResId = android.R.drawable.stat_notify_chat,
            launchIntent = Intent(activity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            pendingIntentRequestCode = runtimeConfigRepository.notificationId
        )
    }

    // Android 13+ 的宿主通知需要显式运行时授权，否则 NotificationManager 会直接拒发。
    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op；用户授权与否都由后续真实通知时再决定 */ }

    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserSelectionFilter: ((Uri) -> Boolean)? = null
    private val fileChooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        val selectionFilter = pendingFileChooserSelectionFilter
        pendingFileChooserSelectionFilter = null
        callback.onReceiveValue(resolveFileChooserUris(result.resultCode, result.data, selectionFilter))
    }

    fun ensureNotificationChannel() {
        systemNotificationController.ensureChannel()
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
        val launchRequest = buildFileChooserLaunchRequest(fileChooserParams)
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = callback
        pendingFileChooserSelectionFilter = launchRequest.selectionFilter
        fileChooserLauncher.launch(launchRequest.intent)
    }

    fun cancelPendingFileChooser() {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        pendingFileChooserSelectionFilter = null
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
        blobDownloadController.captureFromDownloadListener(
            webView = request.sourceWebView,
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

    private fun buildFileChooserLaunchRequest(fileChooserParams: WebChromeClient.FileChooserParams): FileChooserLaunchRequest {
        val chooserIntent = fileChooserParams.createIntent()
        val acceptTokens = fileChooserParams.acceptTypes
            .asSequence()
            .flatMap { acceptValue -> acceptValue.split(',').asSequence() }
            .map { acceptToken -> acceptToken.trim() }
            .filter { acceptToken -> acceptToken.isNotEmpty() }
            .toList()
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
            selectionFilter = buildJsonlAwareSelectionFilter(acceptTokens)
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

    private fun buildJsonlAwareSelectionFilter(acceptTokens: List<String>): (Uri) -> Boolean {
        val normalizedAcceptTokens = acceptTokens.map { acceptToken -> acceptToken.trim() }.filter { acceptToken -> acceptToken.isNotEmpty() }
        val jsonlMimeAliases = setOf(
            "application/x-ndjson",
            "application/jsonl",
            "application/json",
            "text/plain"
        )
        return selectionFilter@{ uri ->
            val displayName = resolveDisplayName(uri)
            val mimeType = activity.contentResolver.getType(uri)?.trim().orEmpty()
            normalizedAcceptTokens.any { acceptToken ->
                when {
                    acceptToken.equals(".jsonl", ignoreCase = true) -> {
                        displayName?.endsWith(".jsonl", ignoreCase = true) == true ||
                            mimeType in jsonlMimeAliases
                    }

                    acceptToken.startsWith(".") -> displayName?.endsWith(acceptToken, ignoreCase = true) == true
                    acceptToken.endsWith("/*") -> {
                        val mimePrefix = acceptToken.removeSuffix("*")
                        mimeType.startsWith(mimePrefix, ignoreCase = true)
                    }

                    acceptToken.contains('/') -> mimeType.equals(acceptToken, ignoreCase = true)
                    else -> false
                }
            }
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

    private fun resolveFileChooserUris(
        resultCode: Int,
        data: Intent?,
        selectionFilter: ((Uri) -> Boolean)?
    ): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) {
            return null
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
            return emptyArray()
        }
        val acceptedUris = selectionFilter?.let { filter -> selectedUris.filter(filter) } ?: selectedUris
        if (acceptedUris.isEmpty()) {
            return null
        }
        return acceptedUris.toTypedArray()
    }
}
