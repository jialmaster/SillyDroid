package com.jm.sillydroid.data.update

import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import com.jm.sillydroid.core.model.update.AvailableAppRelease
import java.util.Locale
import org.json.JSONObject

internal object SiteLatestReleaseParser {
    /**
     * 站点 latest 接口是 App 内更新的唯一读取契约；这里集中解析版本、更新说明和 APK 下载信息。
     */
    fun parseLatestAvailableRelease(
        latestReleaseState: JSONObject,
        config: AppUpdateRequestConfig
    ): AvailableAppRelease? {
        val statusCode = latestReleaseState.optJSONObject("status")?.optString("code")?.trim().orEmpty()
        if (!statusCode.equals("ready", ignoreCase = true)) {
            return null
        }

        val release = latestReleaseState.optJSONObject("release") ?: return null
        if (!release.optString("buildType").trim().equals(config.buildType, ignoreCase = true)) {
            return null
        }

        val releaseTag = release.optString("tag").trim()
        val releaseTitle = release.optString("title").trim().ifBlank { releaseTag }
        val versionName = release.optString("versionName").trim()
        val hostVersion = release.optString("hostVersion").trim()
        val releaseNotesMarkdown = release.optTrimmedStringOrNull("notesMarkdown")
        val apk = release.optJSONObject("apk") ?: return null
        val apkAssetName = apk.optString("assetName").trim()
        val apkDownloadUrl = apk.optString("downloadUrl").trim()
        val apkSha256 = apk.optString("sha256").trim().lowercase(Locale.US)
        if (
            releaseTag.isBlank() ||
            releaseTitle.isBlank() ||
            versionName.isBlank() ||
            hostVersion.isBlank() ||
            apkAssetName.isBlank() ||
            apkDownloadUrl.isBlank() ||
            apkSha256.length != 64 ||
            compareVersionNames(versionName, config.currentVersionName) <= 0
        ) {
            return null
        }

        return AvailableAppRelease(
            releaseTag = releaseTag,
            releaseTitle = releaseTitle,
            versionName = versionName,
            hostVersion = hostVersion,
            releaseNotesMarkdown = releaseNotesMarkdown,
            apkAssetName = apkAssetName,
            apkDownloadUrl = apkDownloadUrl,
            apkSha256 = apkSha256
        )
    }

    private fun JSONObject.optTrimmedStringOrNull(name: String): String? {
        return (opt(name) as? String)?.trim()?.ifBlank { null }
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftTokens = left.split(Regex("[^0-9A-Za-z]+"))
            .filter { token -> token.isNotBlank() }
        val rightTokens = right.split(Regex("[^0-9A-Za-z]+"))
            .filter { token -> token.isNotBlank() }
        val maxSize = maxOf(leftTokens.size, rightTokens.size)
        for (index in 0 until maxSize) {
            val leftToken = leftTokens.getOrElse(index) { "0" }
            val rightToken = rightTokens.getOrElse(index) { "0" }
            val leftNumber = leftToken.toIntOrNull()
            val rightNumber = rightToken.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> 1
                rightNumber != null -> -1
                else -> leftToken.compareTo(rightToken, ignoreCase = true)
            }
            if (comparison != 0) {
                return comparison
            }
        }
        return 0
    }
}
