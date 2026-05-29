package com.jm.sillydroid.data.update

import android.content.Context
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AvailableAppRelease
import com.jm.sillydroid.domain.update.AppUpdateStateRepository
import org.json.JSONObject

class AppUpdateStateStore(context: Context) : AppUpdateStateRepository {
    companion object {
        private const val preferencesName = "app-update-state"
        private const val availableReleaseKey = "available-release"
        private const val downloadStateKey = "download-state"
        private const val checkErrorMessageKey = "check-error-message"
    }

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override var availableRelease: AvailableAppRelease?
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

    override var downloadState: AppDownloadState?
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

    override var checkErrorMessage: String?
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

    private fun encodeAvailableRelease(value: AvailableAppRelease): String {
        return JSONObject()
            .put("releaseTag", value.releaseTag)
            .put("releaseTitle", value.releaseTitle)
            .put("versionName", value.versionName)
            .put("hostVersion", value.hostVersion)
            .put("releaseNotesMarkdown", value.releaseNotesMarkdown)
            .put("apkAssetName", value.apkAssetName)
            .put("apkDownloadUrl", value.apkDownloadUrl)
            .put("apkSha256", value.apkSha256)
            .toString()
    }

    private fun decodeAvailableRelease(rawValue: String): AvailableAppRelease? {
        return runCatching {
            val json = JSONObject(rawValue)
            val releaseTag = json.optString("releaseTag").trim()
            val releaseTitle = json.optString("releaseTitle").trim()
            val versionName = json.optString("versionName").trim()
            val hostVersion = json.optString("hostVersion").trim()
            val releaseNotesMarkdown = json.optTrimmedStringOrNull("releaseNotesMarkdown")
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

            AvailableAppRelease(
                releaseTag = releaseTag,
                releaseTitle = releaseTitle,
                versionName = versionName,
                hostVersion = hostVersion,
                releaseNotesMarkdown = releaseNotesMarkdown,
                apkAssetName = apkAssetName,
                apkDownloadUrl = apkDownloadUrl,
                apkSha256 = apkSha256
            )
        }.getOrNull()
    }

    private fun encodeDownloadState(value: AppDownloadState): String {
        return JSONObject()
            .put("downloadId", value.downloadId)
            .put("releaseTag", value.releaseTag)
            .put("releaseTitle", value.releaseTitle)
            .put("versionName", value.versionName)
            .put("hostVersion", value.hostVersion)
            .put("releaseNotesMarkdown", value.releaseNotesMarkdown)
            .put("apkAssetName", value.apkAssetName)
            .put("apkDownloadUrl", value.apkDownloadUrl)
            .put("apkSha256", value.apkSha256)
            .put("verifiedReadyToInstall", value.verifiedReadyToInstall)
            .toString()
    }

    private fun decodeDownloadState(rawValue: String): AppDownloadState? {
        return runCatching {
            val json = JSONObject(rawValue)
            val downloadId = json.optLong("downloadId", -1L)
            val releaseTag = json.optString("releaseTag").trim()
            val releaseTitle = json.optString("releaseTitle").trim()
            val versionName = json.optString("versionName").trim()
            val hostVersion = json.optString("hostVersion").trim()
            val releaseNotesMarkdown = json.optTrimmedStringOrNull("releaseNotesMarkdown")
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

            AppDownloadState(
                downloadId = downloadId,
                releaseTag = releaseTag,
                releaseTitle = releaseTitle,
                versionName = versionName,
                hostVersion = hostVersion,
                releaseNotesMarkdown = releaseNotesMarkdown,
                apkAssetName = apkAssetName,
                apkDownloadUrl = apkDownloadUrl,
                apkSha256 = apkSha256,
                verifiedReadyToInstall = json.optBoolean("verifiedReadyToInstall", false)
            )
        }.getOrNull()
    }

    private fun JSONObject.optTrimmedStringOrNull(name: String): String? {
        return (opt(name) as? String)?.trim()?.ifBlank { null }
    }
}
