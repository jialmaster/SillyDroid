package com.jm.sillydroid.core.model.update

data class AvailableAppRelease(
    val releaseTag: String,
    val releaseTitle: String,
    val versionName: String,
    val hostVersion: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSha256: String
)

data class AppDownloadState(
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

enum class AppDownloadStatus {
    PENDING,
    PAUSED,
    RUNNING,
    SUCCESSFUL,
    FAILED,
    MISSING
}

data class AppDownloadRecord(
    val status: AppDownloadStatus,
    val reason: Int? = null,
    val localUri: String? = null
)

data class AppUpdateRequestConfig(
    val latestReleaseMetadataUrl: String,
    val buildType: String,
    val currentVersionName: String
)

data class AppUpdateBuildConfig(
    val githubRepository: String,
    val latestReleaseMetadataUrl: String,
    val buildType: String,
    val hostVersion: String,
    val upstreamVersion: String
)
