package com.jm.sillydroid.data.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class AppUpdateHttpDownloader(
    private val userAgent: String = defaultUserAgent,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun download(
        url: String,
        partFile: File,
        stalledTimeoutMillis: Long,
        onProgress: (AppUpdateHttpDownloadProgress) -> Unit
    ): AppUpdateHttpDownloadResult {
        val resumeBytes = partFile.length().takeIf { value -> value > 0L } ?: 0L
        var downloadedBytes = resumeBytes
        var lastProgressBytes = resumeBytes
        var lastProgressAtMillis = nowMillis()
        var totalBytes: Long? = null
        val connection = openConnection(url, resumeBytes)
        try {
            val responseCode = connection.responseCode
            val appendPart = responseCode == HttpURLConnection.HTTP_PARTIAL && resumeBytes > 0L
            if (responseCode == HttpURLConnection.HTTP_OK && resumeBytes > 0L) {
                partFile.delete()
                downloadedBytes = 0L
                lastProgressBytes = 0L
            } else if (responseCode == httpRequestedRangeNotSatisfiable) {
                partFile.delete()
                return download(url, partFile, stalledTimeoutMillis, onProgress)
            } else if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }

            val contentLength = connection.contentLengthLong.takeIf { value -> value > 0L }
            totalBytes = when {
                appendPart && contentLength != null -> resumeBytes + contentLength
                else -> contentLength
            }
            partFile.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(partFile, appendPart).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) {
                            break
                        }
                        if (bytesRead == 0) {
                            continue
                        }
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead.toLong()
                        val now = nowMillis()
                        if (downloadedBytes > lastProgressBytes) {
                            lastProgressBytes = downloadedBytes
                            lastProgressAtMillis = now
                        }
                        onProgress(
                            AppUpdateHttpDownloadProgress(
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                lastProgressAtMillis = lastProgressAtMillis,
                                resumed = appendPart
                            )
                        )
                        if (now - lastProgressAtMillis >= stalledTimeoutMillis) {
                            return AppUpdateHttpDownloadResult.Stalled(
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                lastProgressAtMillis = lastProgressAtMillis
                            )
                        }
                    }
                }
            }
            return AppUpdateHttpDownloadResult.Completed(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                resumed = appendPart
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, resumeBytes: Long): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", userAgent)
            if (resumeBytes > 0L) {
                setRequestProperty("Range", "bytes=$resumeBytes-")
            }
        }
    }

    private companion object {
        private const val defaultUserAgent = "SillyDroid-Android-Updater"
        private const val httpRequestedRangeNotSatisfiable = 416
    }
}

internal data class AppUpdateHttpDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val lastProgressAtMillis: Long,
    val resumed: Boolean
)

internal sealed interface AppUpdateHttpDownloadResult {
    data class Completed(
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val resumed: Boolean
    ) : AppUpdateHttpDownloadResult

    data class Stalled(
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val lastProgressAtMillis: Long
    ) : AppUpdateHttpDownloadResult
}
