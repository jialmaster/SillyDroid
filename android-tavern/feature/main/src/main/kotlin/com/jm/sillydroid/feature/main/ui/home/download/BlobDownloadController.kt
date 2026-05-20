package com.jm.sillydroid.feature.main.ui.home.download

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jm.sillydroid.feature.main.components.download.resolveDownloadFileName
import com.jm.sillydroid.feature.main.model.download.BlobDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import org.json.JSONObject

class BlobDownloadController(
    private val contentResolver: ContentResolver
) {
    private var documentStartScriptHandler: ScriptHandler? = null

    fun installBridgeScript(webView: WebView, bridgeName: String, allowedOrigin: String) {
        installDocumentStartScript(webView, bridgeName, allowedOrigin)
        // 文档启动脚本只能覆盖“后续进入”的页面；当前已经可见的页面仍需要立刻补打一遍，
        // 这样冷启动后的首个 Tavern 页面和恢复态页面都能拿到同一份下载桥逻辑。
        webView.evaluateJavascript(buildBridgeScript(bridgeName), null)
    }

    fun close() {
        documentStartScriptHandler?.remove()
        documentStartScriptHandler = null
    }

    private fun installDocumentStartScript(
        webView: WebView,
        bridgeName: String,
        allowedOrigin: String
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }

        documentStartScriptHandler?.remove()
        documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildBridgeScript(bridgeName),
            setOf(allowedOrigin)
        )
    }

    private fun buildBridgeScript(bridgeName: String): String {
        return """
            (function() {
              const nativeBridge = window.$bridgeName;
              if (!nativeBridge || typeof nativeBridge.saveBase64File !== 'function') {
                return;
              }

              if (window.__staiAndroidDownloadBridgeInstalled) {
                return;
              }

              window.__staiAndroidDownloadBridgeInstalled = true;

              const blobStore = new Map();
              const blobCleanupTimers = new Map();
              window.__staiAndroidDownloadBlobStore = blobStore;
              const originalCreateObjectUrl = URL.createObjectURL.bind(URL);
              const originalRevokeObjectUrl = URL.revokeObjectURL.bind(URL);
              const originalElementClick = HTMLElement.prototype.click;
              const originalResponseBlob = Response.prototype.blob;

              Response.prototype.blob = function() {
                return originalResponseBlob.call(this).then(function(blob) {
                  // 真机已经证明仅靠 blob:url -> Blob 映射不够稳定；
                  // 这里把最近一次 Response.blob() 的真实 Blob 也缓存下来，
                  // 供后续导出点击在 URL 映射缺失时直接复用同一个响应体对象。
                  window.__staiAndroidRecentResponseBlob = {
                    blob: blob,
                    capturedAt: Date.now()
                  };
                  return blob;
                });
              };

              URL.createObjectURL = function(object) {
                const objectUrl = originalCreateObjectUrl(object);
                // 真机已经证明这里不能依赖 instanceof Blob：
                // createObjectURL 收到的对象虽然来自导出 blob，但在当前 WebView 上 realm 判等并不稳定，
                // 导致 blobStore 漏记，后续宿主只能退回 fetch(blob:) 并失败。
                const previousCleanupTimer = blobCleanupTimers.get(objectUrl);
                if (previousCleanupTimer) {
                  clearTimeout(previousCleanupTimer);
                  blobCleanupTimers.delete(objectUrl);
                }
                blobStore.set(objectUrl, object);
                return objectUrl;
              };

              URL.revokeObjectURL = function(objectUrl) {
                // 页面导出代码会在 a.click() 后立刻 revokeObjectURL；
                // 真机上 DownloadListener 回注发生在这之后，因此这里必须保留一个短暂缓存窗口，
                // 让宿主仍能从 blobStore 取到原始 Blob，而不是只能对已失效的 blob URL fetch 失败。
                const cleanupTimer = setTimeout(function() {
                  blobStore.delete(objectUrl);
                  blobCleanupTimers.delete(objectUrl);
                }, 15000);
                const previousCleanupTimer = blobCleanupTimers.get(objectUrl);
                if (previousCleanupTimer) {
                  clearTimeout(previousCleanupTimer);
                }
                blobCleanupTimers.set(objectUrl, cleanupTimer);
                return originalRevokeObjectUrl(objectUrl);
              };

              function readBlobAsBase64(blob) {
                return new Promise(function(resolve, reject) {
                  const reader = new FileReader();
                  reader.onloadend = function() {
                    const result = typeof reader.result === 'string' ? reader.result : '';
                    const commaIndex = result.indexOf(',');
                    if (commaIndex < 0) {
                      reject(new Error('无法解析导出数据'));
                      return;
                    }
                    resolve(result.slice(commaIndex + 1));
                  };
                  reader.onerror = function() {
                    reject(reader.error || new Error('无法读取导出数据'));
                  };
                  reader.readAsDataURL(blob);
                });
              }

              function resolveBlob(href) {
                if (href.startsWith('blob:')) {
                  const cachedBlob = blobStore.get(href);
                  if (cachedBlob) {
                    return Promise.resolve(cachedBlob);
                  }
                  const recentResponseBlob = window.__staiAndroidRecentResponseBlob;
                  if (recentResponseBlob && recentResponseBlob.blob && (Date.now() - recentResponseBlob.capturedAt) <= 15000) {
                    return Promise.resolve(recentResponseBlob.blob);
                  }
                  return fetch(href).then(function(response) {
                    return response.blob();
                  });
                }

                if (href.startsWith('data:')) {
                  return fetch(href).then(function(response) {
                    return response.blob();
                  });
                }

                return Promise.resolve(null);
              }

              function saveBlobUrl(downloadUrl, fileName) {
                nativeBridge.onBlobDownloadPreparing(fileName);

                resolveBlob(downloadUrl)
                  .then(function(blob) {
                    if (!blob) {
                      throw new Error('未找到导出数据');
                    }

                    return readBlobAsBase64(blob).then(function(base64) {
                      nativeBridge.saveBase64File(JSON.stringify({
                        fileName: fileName,
                        mimeType: blob.type || '',
                        base64: base64
                      }));
                    });
                  })
                  .catch(function(error) {
                    nativeBridge.reportDownloadFailure(JSON.stringify({
                      fileName: fileName,
                      message: error && error.message ? error.message : '导出失败'
                    }));
                  });
              }

              window.__staiAndroidDownloadBridgeCaptureUrl = function(downloadUrl, fileNameHint) {
                if (!downloadUrl || (!downloadUrl.startsWith('blob:') && !downloadUrl.startsWith('data:'))) {
                  return false;
                }

                const fileName = (fileNameHint || '').trim() || 'download';
                saveBlobUrl(downloadUrl, fileName);
                return true;
              };

              function interceptAnchorDownload(anchor) {
                if (!anchor) {
                  return false;
                }

                const href = anchor.href || '';
                if (!href.startsWith('blob:') && !href.startsWith('data:')) {
                  return false;
                }

                const fileName = (anchor.getAttribute('download') || '').trim() || 'download';
                window.__staiAndroidDownloadBridgeCaptureUrl(href, fileName);

                return true;
              }

              // 真机已经证明确实存在“a.click() 触发了 DownloadListener，但没有命中旧桥脚本”的路径；
              // 这里改拦 HTMLElement.prototype.click，确保程序化点击也走同一条 blob 保存链。
              HTMLElement.prototype.click = function() {
                if (this instanceof HTMLAnchorElement && interceptAnchorDownload(this)) {
                  return;
                }

                return originalElementClick.call(this);
              };

              document.addEventListener('click', function(event) {
                const target = event.target;
                const anchor = target && typeof target.closest === 'function' ? target.closest('a[href]') : null;
                if (!anchor || !interceptAnchorDownload(anchor)) {
                  return;
                }

                event.preventDefault();
                event.stopPropagation();
              }, true);
            })();
            """.trimIndent()
    }

    fun captureFromDownloadListener(
        webView: WebView,
        bridgeName: String,
        request: BrowserDownloadRequest,
        diagnosticSink: (String) -> Unit = {}
    ) {
        val fileNameHint = resolveFileName(
            URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        )
        val captureScript = """
            (function() {
              const nativeBridge = window[${
            JSONObject.quote(bridgeName)
        }];
              const downloadUrl = ${JSONObject.quote(request.url)};
              const fileNameHint = ${JSONObject.quote(fileNameHint)};

              if (!nativeBridge || typeof nativeBridge.saveBase64File !== 'function') {
                return JSON.stringify({ status: 'bridge_missing' });
              }

              const installedCapture = window.__staiAndroidDownloadBridgeCaptureUrl;
              if (typeof installedCapture === 'function') {
                return JSON.stringify({
                  status: installedCapture(downloadUrl, fileNameHint) ? 'capture_started' : 'capture_rejected',
                  source: 'installed_helper'
                });
              }

              function readBlobAsBase64(blob) {
                return new Promise(function(resolve, reject) {
                  const reader = new FileReader();
                  reader.onloadend = function() {
                    const result = typeof reader.result === 'string' ? reader.result : '';
                    const commaIndex = result.indexOf(',');
                    if (commaIndex < 0) {
                      reject(new Error('无法解析导出数据'));
                      return;
                    }
                    resolve(result.slice(commaIndex + 1));
                  };
                  reader.onerror = function() {
                    reject(reader.error || new Error('无法读取导出数据'));
                  };
                  reader.readAsDataURL(blob);
                });
              }

              nativeBridge.onBlobDownloadPreparing(fileNameHint);
              fetch(downloadUrl)
                .then(function(response) { return response.blob(); })
                .then(function(blob) {
                  return readBlobAsBase64(blob).then(function(base64) {
                    nativeBridge.saveBase64File(JSON.stringify({
                      fileName: fileNameHint,
                      mimeType: blob.type || ${JSONObject.quote(request.mimeType)},
                      base64: base64
                    }));
                  });
                })
                .catch(function(error) {
                  nativeBridge.reportDownloadFailure(JSON.stringify({
                    fileName: fileNameHint,
                    message: error && error.message ? error.message : '导出失败'
                  }));
                });
              return JSON.stringify({ status: 'capture_started', source: 'direct_fetch' });
            })();
        """.trimIndent()

        webView.evaluateJavascript(captureScript) { result ->
            runCatching {
                diagnosticSink(
                    "event=download_listener_blob_capture_eval fileName=$fileNameHint result=${result.orEmpty().ifBlank { "null" }}"
                )
            }
        }
    }

    fun resolveFileName(rawName: String?): String {
        return resolveDownloadFileName(rawName, fallbackUrl = null)
    }

    fun parseDownloadRequest(payload: String?): BlobDownloadRequest? {
        if (payload.isNullOrBlank()) {
            return null
        }

        val json = JSONObject(payload)
        val fileName = resolveFileName(json.optString("fileName"))
        val mimeType = json.optString("mimeType").trim().ifBlank { "application/octet-stream" }
        val base64Data = json.optString("base64").trim()
        if (base64Data.isBlank()) {
            return null
        }

        return BlobDownloadRequest(fileName = fileName, mimeType = mimeType, base64Data = base64Data)
    }

    fun parseFailureReport(payload: String?, unknownMessage: String): DownloadFailureReport {
        if (payload.isNullOrBlank()) {
            return DownloadFailureReport(
                fileName = resolveFileName(null),
                message = unknownMessage
            )
        }

        val json = JSONObject(payload)
        return DownloadFailureReport(
            fileName = resolveFileName(json.optString("fileName")),
            message = json.optString("message").trim().ifBlank { unknownMessage }
        )
    }

    suspend fun persist(request: BlobDownloadRequest): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, request.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val targetUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建下载文件")

        try {
            contentResolver.openOutputStream(targetUri)?.use { output ->
                output.write(Base64.decode(request.base64Data, Base64.DEFAULT))
                output.flush()
            } ?: throw IllegalStateException("无法写入下载文件")

            val completedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            contentResolver.update(targetUri, completedValues, null, null)
            return request.fileName
        } catch (error: Exception) {
            contentResolver.delete(targetUri, null, null)
            throw error
        }
    }
}
