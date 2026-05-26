package com.jm.sillydroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * DownloadManager 完成广播可能发生在页面层不可见时；
 * 这里把宿主自管下载通知需要的最小状态持久化，保证广播接收器仍能按统一通知出口更新结果。
 */
class HostDownloadNotificationStateStore(context: Context) {
    data class BrowserDownloadEntry(
        val downloadId: Long,
        val notificationKey: String,
        val fileName: String,
        val mimeType: String,
        val localUri: String
    )

    companion object {
        private const val preferencesName = "host-download-notification-state"
        private const val browserDownloadsKey = "browser-downloads"
    }

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun saveBrowserDownload(entry: BrowserDownloadEntry) {
        val entries = readBrowserDownloads()
            .filterNot { candidate -> candidate.downloadId == entry.downloadId }
            .plus(entry)
        writeBrowserDownloads(entries)
    }

    fun browserDownloadById(downloadId: Long): BrowserDownloadEntry? {
        return readBrowserDownloads().firstOrNull { entry -> entry.downloadId == downloadId }
    }

    fun removeBrowserDownload(downloadId: Long) {
        writeBrowserDownloads(readBrowserDownloads().filterNot { entry -> entry.downloadId == downloadId })
    }

    private fun readBrowserDownloads(): List<BrowserDownloadEntry> {
        val rawValue = preferences.getString(browserDownloadsKey, "[]").orEmpty()
        return runCatching {
            val json = JSONArray(rawValue)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    val downloadId = item.optLong("downloadId", -1L)
                    val notificationKey = item.optString("notificationKey").trim()
                    val fileName = item.optString("fileName").trim()
                    val mimeType = item.optString("mimeType").trim()
                    val localUri = item.optString("localUri").trim()
                    if (downloadId > 0L && notificationKey.isNotBlank() && fileName.isNotBlank() && localUri.isNotBlank()) {
                        add(
                            BrowserDownloadEntry(
                                downloadId = downloadId,
                                notificationKey = notificationKey,
                                fileName = fileName,
                                mimeType = mimeType,
                                localUri = localUri
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeBrowserDownloads(entries: List<BrowserDownloadEntry>) {
        val json = JSONArray()
        entries.sortedBy { entry -> entry.downloadId }.forEach { entry ->
            json.put(
                JSONObject()
                    .put("downloadId", entry.downloadId)
                    .put("notificationKey", entry.notificationKey)
                    .put("fileName", entry.fileName)
                    .put("mimeType", entry.mimeType)
                    .put("localUri", entry.localUri)
            )
        }
        preferences.edit().putString(browserDownloadsKey, json.toString()).apply()
    }
}
