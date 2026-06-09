package com.jm.sillydroid.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateDownloadCachePolicyTest {
    @Test
    fun keepsOnlyCurrentReleaseApkAndPartFile() {
        val release = release(versionName = "1.0.1.60")

        assertTrue(AppUpdateDownloadCachePolicy.shouldKeepCacheFile("sillydroid-1.0.1.60.apk", release))
        assertTrue(AppUpdateDownloadCachePolicy.shouldKeepCacheFile("sillydroid-1.0.1.60.apk.part", release))
        assertFalse(AppUpdateDownloadCachePolicy.shouldKeepCacheFile("sillydroid-1.0.1.59.apk", release))
        assertFalse(AppUpdateDownloadCachePolicy.shouldKeepCacheFile("sillydroid-1.0.1.59.apk.part", release))
        assertFalse(AppUpdateDownloadCachePolicy.shouldKeepCacheFile("orphan.tmp", release))
    }

    @Test
    fun treatsChangedVersionOrShaAsDifferentTask() {
        val oldRelease = release(versionName = "1.0.1.60", sha = "a".repeat(64))
        val newVersion = release(versionName = "1.0.1.61", sha = "a".repeat(64))
        val newSha = release(versionName = "1.0.1.60", sha = "b".repeat(64))

        assertFalse(
            AppUpdateDownloadCachePolicy.isSameTask(
                AppUpdateDownloadCachePolicy.taskKey(oldRelease),
                AppUpdateDownloadCachePolicy.taskKey(newVersion)
            )
        )
        assertFalse(
            AppUpdateDownloadCachePolicy.isSameTask(
                AppUpdateDownloadCachePolicy.taskKey(oldRelease),
                AppUpdateDownloadCachePolicy.taskKey(newSha)
            )
        )
    }

    @Test
    fun treatsSameReleaseMetadataAsSameTask() {
        val release = release(versionName = "1.0.1.60")

        assertTrue(
            AppUpdateDownloadCachePolicy.isSameTask(
                AppUpdateDownloadCachePolicy.taskKey(release),
                AppUpdateDownloadCachePolicy.taskKey(release.copy())
            )
        )
    }

    private fun release(
        versionName: String,
        sha: String = "a".repeat(64)
    ) = com.jm.sillydroid.core.model.update.AvailableAppRelease(
        releaseTag = "sillydroid-v$versionName",
        releaseTitle = "SillyDroid $versionName",
        versionName = versionName,
        hostVersion = versionName,
        releaseNotesMarkdown = null,
        apkAssetName = "sillydroid-$versionName.apk",
        apkDownloadUrl = "https://example.invalid/sillydroid-$versionName.apk",
        apkSha256 = sha
    )
}
