package com.jm.sillydroid.feature.main.ui.home.download

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebView
import com.jm.sillydroid.feature.main.components.download.resolveDownloadFileName
import com.jm.sillydroid.feature.main.model.download.BlobDownloadRequest
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import org.json.JSONObject

class BlobDownloadController(
    private val contentResolver: ContentResolver
) {
    fun installBridgeScript(webView: WebView, bridgeName: String) {
        webView.evaluateJavascript(
            """
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
              const originalCreateObjectUrl = URL.createObjectURL.bind(URL);
              const originalRevokeObjectUrl = URL.revokeObjectURL.bind(URL);
              const originalAnchorClick = HTMLAnchorElement.prototype.click;

              URL.createObjectURL = function(object) {
                const objectUrl = originalCreateObjectUrl(object);
                if (object instanceof Blob) {
                  blobStore.set(objectUrl, object);
                }
                return objectUrl;
              };

              URL.revokeObjectURL = function(objectUrl) {
                blobStore.delete(objectUrl);
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
                  return Promise.resolve(blobStore.get(href) || null);
                }

                if (href.startsWith('data:')) {
                  return fetch(href).then(function(response) {
                    return response.blob();
                  });
                }

                return Promise.resolve(null);
              }

              function interceptAnchorDownload(anchor) {
                if (!anchor) {
                  return false;
                }

                const href = anchor.href || '';
                if (!href.startsWith('blob:') && !href.startsWith('data:')) {
                  return false;
                }

                const fileName = (anchor.getAttribute('download') || '').trim() || 'download';
                nativeBridge.onBlobDownloadPreparing(fileName);

                resolveBlob(href)
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

                return true;
              }

              HTMLAnchorElement.prototype.click = function() {
                if (interceptAnchorDownload(this)) {
                  return;
                }

                return originalAnchorClick.call(this);
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
            """.trimIndent(),
            null
        )
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
