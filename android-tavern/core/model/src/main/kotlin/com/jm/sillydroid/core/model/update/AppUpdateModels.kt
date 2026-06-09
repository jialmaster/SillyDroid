package com.jm.sillydroid.core.model.update

data class AvailableAppRelease(
    val releaseTag: String,
    val releaseTitle: String,
    val versionName: String,
    val hostVersion: String,
    val releaseNotesMarkdown: String?,
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
    val releaseNotesMarkdown: String?,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSha256: String,
    val verifiedReadyToInstall: Boolean,
    val status: AppDownloadStatus = AppDownloadStatus.MISSING,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val lastProgressAtMillis: Long = 0L,
    val failureReason: AppDownloadFailureReason? = null,
    val resumable: Boolean = false
) {
    val taskKey: AppDownloadTaskKey
        get() = AppDownloadTaskKey(
            releaseTag = releaseTag,
            versionName = versionName,
            apkAssetName = apkAssetName,
            apkDownloadUrl = apkDownloadUrl,
            apkSha256 = apkSha256
        )
}

data class AppDownloadTaskKey(
    val releaseTag: String,
    val versionName: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSha256: String
)

enum class AppDownloadStatus {
    READY_TO_INSTALL,
    RESUMABLE,
    DOWNLOADING,
    STALLED,
    SUCCESSFUL,
    FAILED,
    MISSING,
    PENDING,
    PAUSED,
    RUNNING
}

enum class AppDownloadFailureReason {
    NETWORK,
    STALLED,
    SERVER,
    STORAGE,
    CHECKSUM,
    UNKNOWN
}

data class AppDownloadRecord(
    val status: AppDownloadStatus,
    val reason: Int? = null,
    val localUri: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val lastProgressAtMillis: Long = 0L,
    val failureReason: AppDownloadFailureReason? = null,
    val resumable: Boolean = false
)

data class AppUpdateRequestConfig(
    val latestReleaseMetadataUrl: String,
    val buildType: String,
    val currentVersionName: String
)

data class AppUpdateBuildConfig(
    val githubRepository: String,
    val latestReleaseMetadataUrl: String,
    val crashLogUploadUrl: String,
    val crashLogUploadWriterApiKey: String,
    val buildType: String,
    val hostVersion: String,
    val upstreamVersion: String
)
