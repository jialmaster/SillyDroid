package com.jm.sillydroid.data.logs

import android.content.Context
import android.os.Build
import com.jm.sillydroid.core.model.logs.HostLogBundleUploadRequestConfig
import com.jm.sillydroid.core.model.logs.HostLogBundleUploadResult
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal class HostLogBundleUploader(
    private val metadataProvider: HostLogUploadMetadataProvider
) {
    constructor(context: Context) : this(AndroidHostLogUploadMetadataProvider(context))

    fun upload(
        archiveFile: File,
        config: HostLogBundleUploadRequestConfig
    ): HostLogBundleUploadResult {
        val uploadUrl = config.uploadUrl.trim()
        val writerApiKey = config.writerApiKey.trim()
        if (uploadUrl.isBlank()) {
            throw IllegalArgumentException("日志上传地址未配置。")
        }
        if (writerApiKey.isBlank()) {
            throw IllegalArgumentException("日志上传密钥未配置。")
        }
        if (!archiveFile.isFile || !archiveFile.name.endsWith(".zip", ignoreCase = true)) {
            throw IllegalArgumentException("日志上传包不存在。")
        }

        val boundary = "sillydroid-${UUID.randomUUID()}"
        val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("X-SillyDroid-Crash-Log-Key", writerApiKey)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            connection.outputStream.use { output ->
                fun writeField(name: String, value: String?) {
                    val normalized = value.orEmpty().trim()
                    if (normalized.isBlank()) {
                        return
                    }
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(normalized.toByteArray(Charsets.UTF_8))
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }

                writeField("occurredAt", utcNowIso8601())
                writeField("packageName", metadataProvider.packageName())
                writeField("versionName", metadataProvider.versionName())
                writeField("versionCode", metadataProvider.versionCode())
                writeField("installationId", metadataProvider.installationId())
                writeField("deviceModel", metadataProvider.deviceModel())
                writeField("androidVersion", metadataProvider.androidVersion())
                writeField("abi", metadataProvider.abi())
                writeField("buildFingerprint", metadataProvider.buildFingerprint())
                writeField("crashType", config.crashType)
                writeField("source", config.source)
                writeField("notes", config.notes)

                output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                output.write(
                    (
                        "Content-Disposition: form-data; name=\"archive\"; filename=\"${archiveFile.name}\"\r\n" +
                            "Content-Type: application/zip\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8)
                )
                archiveFile.inputStream().use { input -> input.copyTo(output) }
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { reader -> reader.readText() }
                .orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode: $responseText")
            }
            return parseUploadResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseUploadResponse(responseText: String): HostLogBundleUploadResult {
        return HostLogBundleUploadResult(
            crashLogId = readJsonLong(responseText, "crashLogId") ?: error("Upload response missing crashLogId."),
            projectKey = readJsonString(responseText, "projectKey").orEmpty(),
            archiveFileName = readJsonString(responseText, "archiveFileName").orEmpty(),
            archiveSizeBytes = readJsonLong(responseText, "archiveSizeBytes") ?: 0L,
            sha256 = readJsonString(responseText, "sha256").orEmpty()
        )
    }

    private fun readJsonLong(json: String, name: String): Long? {
        return Regex(""""${Regex.escape(name)}"\s*:\s*(\d+)""")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private fun readJsonString(json: String, name: String): String? {
        return Regex(""""${Regex.escape(name)}"\s*:\s*"([^"]*)"""")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun utcNowIso8601(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private companion object {
        private const val userAgent = "SillyDroid-Android-LogUploader"
    }
}

internal interface HostLogUploadMetadataProvider {
    fun packageName(): String
    fun versionName(): String
    fun versionCode(): String
    fun installationId(): String
    fun deviceModel(): String
    fun androidVersion(): String
    fun abi(): String?
    fun buildFingerprint(): String?
}

private class AndroidHostLogUploadMetadataProvider(context: Context) : HostLogUploadMetadataProvider {
    private val appContext = context.applicationContext
    private val installationIdStore = appContext.getSharedPreferences("host-log-upload", Context.MODE_PRIVATE)

    override fun packageName(): String = appContext.packageName

    override fun versionName(): String = currentPackageInfo().versionName.orEmpty().trim().ifBlank { "unknown" }

    override fun versionCode(): String = currentPackageInfo().longVersionCode.toString()

    override fun installationId(): String {
        val existing = installationIdStore.getString(installationIdKey, null).orEmpty().trim()
        if (existing.isNotBlank()) {
            return existing
        }
        val created = UUID.randomUUID().toString()
        installationIdStore.edit().putString(installationIdKey, created).apply()
        return created
    }

    override fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    override fun androidVersion(): String = Build.VERSION.RELEASE

    override fun abi(): String? = Build.SUPPORTED_ABIS.firstOrNull()

    override fun buildFingerprint(): String? = Build.FINGERPRINT

    @Suppress("DEPRECATION")
    private fun currentPackageInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        appContext.packageManager.getPackageInfo(
            appContext.packageName,
            android.content.pm.PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }

    private companion object {
        private const val installationIdKey = "installation-id"
    }
}
