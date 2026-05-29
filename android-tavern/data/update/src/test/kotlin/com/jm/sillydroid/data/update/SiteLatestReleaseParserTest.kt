package com.jm.sillydroid.data.update

import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SiteLatestReleaseParserTest {
    @Test
    fun parsesReadySiteReleaseState() {
        val release = SiteLatestReleaseParser.parseLatestAvailableRelease(
            latestReleaseState = JSONObject(siteReleaseStateJson()),
            config = requestConfig(currentVersionName = "1.0.1.38+tavern.1.18.0")
        )

        assertNotNull(release)
        requireNotNull(release)
        assertEquals("sillydroid-v1.0.1.39-1.18.0-release", release.releaseTag)
        assertEquals("1.0.1.39+tavern.1.18.0", release.versionName)
        assertEquals("1.0.1.39", release.hostVersion)
        assertEquals("## 本次更新\n\n- 修复启动诊断", release.releaseNotesMarkdown)
        assertEquals(
            "https://github.com/jialmaster/SillyDroid/releases/download/sillydroid-v1.0.1.39-1.18.0-release/sillydroid-android-v1.0.1.39-1.18.0-release.apk",
            release.apkDownloadUrl
        )
    }

    @Test
    fun ignoresReleaseWhenStatusIsNotReady() {
        val release = SiteLatestReleaseParser.parseLatestAvailableRelease(
            latestReleaseState = JSONObject(siteReleaseStateJson(statusCode = "syncing")),
            config = requestConfig()
        )

        assertNull(release)
    }

    @Test
    fun ignoresReleaseWhenBuildTypeDoesNotMatch() {
        val release = SiteLatestReleaseParser.parseLatestAvailableRelease(
            latestReleaseState = JSONObject(siteReleaseStateJson(buildType = "debug")),
            config = requestConfig(buildType = "release")
        )

        assertNull(release)
    }

    @Test
    fun ignoresReleaseWhenVersionIsNotNewer() {
        val release = SiteLatestReleaseParser.parseLatestAvailableRelease(
            latestReleaseState = JSONObject(siteReleaseStateJson()),
            config = requestConfig(currentVersionName = "1.0.1.39+tavern.1.18.0")
        )

        assertNull(release)
    }

    private fun requestConfig(
        buildType: String = "release",
        currentVersionName: String = "1.0.1.0+tavern.1.18.0"
    ): AppUpdateRequestConfig {
        return AppUpdateRequestConfig(
            latestReleaseMetadataUrl = "https://sd.jlmaster.online/api/releases/latest.json",
            buildType = buildType,
            currentVersionName = currentVersionName
        )
    }

    private fun siteReleaseStateJson(
        statusCode: String = "ready",
        buildType: String = "release"
    ): String {
        return """
            {
              "schemaVersion": 1,
              "channel": "stable",
              "repository": "jialmaster/SillyDroid",
              "updatedAt": "2026-05-29T01:22:58Z",
              "source": {
                "provider": "github-release",
                "trigger": "release-edited"
              },
              "status": {
                "code": "$statusCode",
                "reason": "release-edited"
              },
              "release": {
                "tag": "sillydroid-v1.0.1.39-1.18.0-release",
                "title": "SillyDroid Android v1.0.1.39 / Tavern 1.18.0 release",
                "url": "https://github.com/jialmaster/SillyDroid/releases/tag/sillydroid-v1.0.1.39-1.18.0-release",
                "publishedAt": "2026-05-28T12:15:51Z",
                "isPrerelease": false,
                "notesMarkdown": "## 本次更新\n\n- 修复启动诊断",
                "buildType": "$buildType",
                "versionName": "1.0.1.39+tavern.1.18.0",
                "hostVersion": "1.0.1.39",
                "upstreamVersion": "1.18.0",
                "apk": {
                  "assetName": "sillydroid-android-v1.0.1.39-1.18.0-release.apk",
                  "downloadUrl": "https://github.com/jialmaster/SillyDroid/releases/download/sillydroid-v1.0.1.39-1.18.0-release/sillydroid-android-v1.0.1.39-1.18.0-release.apk",
                  "sha256": "867939dd21d2b2d5347adcfdd750f1c0efcee9f9009d6a474d6457950fb6750d",
                  "sizeBytes": 218885355,
                  "updatedAt": "2026-05-29T01:22:58Z"
                }
              }
            }
        """.trimIndent()
    }
}
