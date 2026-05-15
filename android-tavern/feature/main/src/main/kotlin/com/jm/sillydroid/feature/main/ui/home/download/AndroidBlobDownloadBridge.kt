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
    private val onFailure: (DownloadFailureReport) -> Unit
) {
    @JavascriptInterface
    fun onBlobDownloadPreparing(fileName: String?) {
        val resolvedFileName = controller.resolveFileName(fileName)
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
            onSaving(request.fileName)

            val result = runCatching {
                withContext(dispatchers.io) {
                    controller.persist(request)
                }
            }

            result.onSuccess { fileName ->
                onSaved(fileName)
            }.onFailure { error ->
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

        runOnUiThread {
            onFailure(report)
        }
    }
}
