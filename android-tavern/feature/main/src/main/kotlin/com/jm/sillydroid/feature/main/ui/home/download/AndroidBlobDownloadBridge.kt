package com.jm.sillydroid.feature.main.ui.home.download

import android.webkit.JavascriptInterface
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import kotlinx.coroutines.CoroutineScope
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidBlobDownloadBridge(
    private val controller: BlobDownloadController,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val unknownErrorMessage: () -> String,
    private val emptyPayloadMessage: () -> String,
    private val onPreparing: (String) -> Unit,
    private val onSaving: (String) -> Unit,
    private val onSaved: (String) -> Unit,
    private val onFailure: (DownloadFailureReport) -> Unit,
    private val diagnosticSink: (String) -> Unit = {}
) {
    private fun recordDiagnostic(body: String) {
        runCatching { diagnosticSink(body) }
    }

    @JavascriptInterface
    fun onBlobDownloadPreparing(fileName: String?) {
        val resolvedFileName = controller.resolveFileName(fileName)
        // 这个回调只要出现，就说明页面至少命中了宿主安装的 blob bridge，而不是完全走了页面自有下载实现。
        recordDiagnostic("event=blob_bridge_preparing fileName=$resolvedFileName")
        runOnUiThread {
            onPreparing(resolvedFileName)
        }
    }

    @JavascriptInterface
    fun saveBase64File(payload: String?) {
        val request = try {
            controller.parseDownloadRequest(payload)
        } catch (_: Exception) {
            null
        }

        if (request == null) {
            recordDiagnostic("event=blob_bridge_parse_failed reason=empty_or_invalid_payload")
            runOnUiThread {
                onFailure(
                    DownloadFailureReport(
                        fileName = controller.resolveFileName(null),
                        message = emptyPayloadMessage()
                    )
                )
            }
            return
        }

        scope.launch {
            recordDiagnostic(
                "event=blob_bridge_save_requested fileName=${request.fileName} mime=${request.mimeType} base64Length=${request.base64Data.length}"
            )
            onSaving(request.fileName)

            val result = runCatching {
                withContext(dispatchers.io) {
                    controller.persist(request)
                }
            }

            result.onSuccess { fileName ->
                recordDiagnostic("event=blob_bridge_saved fileName=$fileName")
                onSaved(fileName)
            }.onFailure { error ->
                recordDiagnostic(
                    "event=blob_bridge_save_failed fileName=${request.fileName} error=${error.message ?: error.javaClass.simpleName}"
                )
                onFailure(
                    DownloadFailureReport(
                        fileName = request.fileName,
                        message = error.message ?: unknownErrorMessage()
                    )
                )
            }
        }
    }

    @JavascriptInterface
    fun reportDownloadFailure(payload: String?) {
        val report = try {
            controller.parseFailureReport(
                payload = payload,
                unknownMessage = unknownErrorMessage()
            )
        } catch (_: Exception) {
            DownloadFailureReport(
                fileName = controller.resolveFileName(null),
                message = unknownErrorMessage()
            )
        }

        recordDiagnostic("event=blob_bridge_reported_failure fileName=${report.fileName} error=${report.message}")
        runOnUiThread {
            onFailure(report)
        }
    }
}
