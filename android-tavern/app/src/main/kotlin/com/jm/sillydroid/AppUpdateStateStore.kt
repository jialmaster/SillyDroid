package com.jm.sillydroid

import android.content.Context
import org.json.JSONObject

internal class AppUpdateStateStore(context: Context) {
    internal data class AvailableRelease(
        val releaseTag: String,
        val releaseTitle: String,
        val versionName: String,
        val hostVersion: String,
        val apkAssetName: String,
        val apkDownloadUrl: String,
        val apkSha256: String
    )

    internal data class DownloadState(
        val downloadId: Long,
        val releaseTag: String,
        val releaseTitle: String,
        val versionName: String,
        val hostVersion: String,
        val apkAssetName: String,
        val apkDownloadUrl: String,
        val apkSha256: String,
        val verifiedReadyToInstall: Boolean
    )

    companion object {
        private const val preferencesName = "app-update-state"
        private const val availableReleaseKey = "available-release"
        private const val downloadStateKey = "download-state"
        private const val checkErrorMessageKey = "check-error-message"
    }

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    var availableRelease: AvailableRelease?
        get() = preferences.getString(availableReleaseKey, null)?.let(::decodeAvailableRelease)
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    remove(availableReleaseKey)
                } else {
                    putString(availableReleaseKey, encodeAvailableRelease(value))
                }
            }.apply()
        }

    var downloadState: DownloadState?
        get() = preferences.getString(downloadStateKey, null)?.let(::decodeDownloadState)
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    remove(downloadStateKey)
                } else {
                    putString(downloadStateKey, encodeDownloadState(value))
                }
            }.apply()
        }

    var checkErrorMessage: String?
        get() = preferences.getString(checkErrorMessageKey, null)?.trim()?.ifBlank { null }
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) {
                    remove(checkErrorMessageKey)
                } else {
                    putString(checkErrorMessageKey, value)
                }
            }.apply()
        }

    private fun encodeAvailableRelease(value: AvailableRelease): String {
        return JSONObject()
            .put("releaseTag", value.releaseTag)
            .put("releaseTitle", value.releaseTitle)
            .put("versionName", value.versionName)
            .put("hostVersion", value.hostVersion)
            .put("apkAssetName", value.apkAssetName)
            .put("apkDownloadUrl", value.apkDownloadUrl)
            .put("apkSha256", value.apkSha256)
            .toString()
    }

    private fun decodeAvailableRelease(rawValue: String): AvailableRelease? {
        return runCatching {
            val json = JSONObject(rawValue)
            val releaseTag = json.optString("releaseTag").trim()
            val releaseTitle = json.optString("releaseTitle").trim()
            val versionName = json.optString("versionName").trim()
            val hostVersion = json.optString("hostVersion").trim()
            val apkAssetName = json.optString("apkAssetName").trim()
            val apkDownloadUrl = json.optString("apkDownloadUrl").trim()
            val apkSha256 = json.optString("apkSha256").trim().lowercase()
            if (
                releaseTag.isBlank() ||
                releaseTitle.isBlank() ||
                versionName.isBlank() ||
                hostVersion.isBlank() ||
                apkAssetName.isBlank() ||
                apkDownloadUrl.isBlank() ||
                apkSha256.length != 64
            ) {
                return null
            }

            AvailableRelease(
                releaseTag = releaseTag,
                releaseTitle = releaseTitle,
                versionName = versionName,
                hostVersion = hostVersion,
                apkAssetName = apkAssetName,
                apkDownloadUrl = apkDownloadUrl,
                apkSha256 = apkSha256
            )
        }.getOrNull()
    }

    private fun encodeDownloadState(value: DownloadState): String {
        return JSONObject()
            .put("downloadId", value.downloadId)
            .put("releaseTag", value.releaseTag)
            .put("releaseTitle", value.releaseTitle)
            .put("versionName", value.versionName)
            .put("hostVersion", value.hostVersion)
            .put("apkAssetName", value.apkAssetName)
            .put("apkDownloadUrl", value.apkDownloadUrl)
            .put("apkSha256", value.apkSha256)
            .put("verifiedReadyToInstall", value.verifiedReadyToInstall)
            .toString()
    }

    private fun decodeDownloadState(rawValue: String): DownloadState? {
        return runCatching {
            val json = JSONObject(rawValue)
            val downloadId = json.optLong("downloadId", -1L)
            val releaseTag = json.optString("releaseTag").trim()
            val releaseTitle = json.optString("releaseTitle").trim()
            val versionName = json.optString("versionName").trim()
            val hostVersion = json.optString("hostVersion").trim()
            val apkAssetName = json.optString("apkAssetName").trim()
            val apkDownloadUrl = json.optString("apkDownloadUrl").trim()
            val apkSha256 = json.optString("apkSha256").trim().lowercase()
            if (
                downloadId <= 0L ||
                releaseTag.isBlank() ||
                releaseTitle.isBlank() ||
                versionName.isBlank() ||
                hostVersion.isBlank() ||
                apkAssetName.isBlank() ||
                apkDownloadUrl.isBlank() ||
                apkSha256.length != 64
            ) {
                return null
            }

            DownloadState(
                downloadId = downloadId,
                releaseTag = releaseTag,
                releaseTitle = releaseTitle,
                versionName = versionName,
                hostVersion = hostVersion,
                apkAssetName = apkAssetName,
                apkDownloadUrl = apkDownloadUrl,
                apkSha256 = apkSha256,
                verifiedReadyToInstall = json.optBoolean("verifiedReadyToInstall", false)
            )
        }.getOrNull()
    }
}
