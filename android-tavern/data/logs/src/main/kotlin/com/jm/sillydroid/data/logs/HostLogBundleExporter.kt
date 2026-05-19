package com.jm.sillydroid.data.logs

import com.jm.sillydroid.core.model.logs.HostLogBundleExportResult

@Deprecated("Use HostLogManager directly.", ReplaceWith("HostLogManager"))
object HostLogBundleExporter {
    fun buildBundleFileName(nowMillis: Long = System.currentTimeMillis()): String {
        return HostLogManager.buildBundleFileName(nowMillis)
    }

    fun exportToUri(
        context: android.content.Context,
        targetUri: android.net.Uri,
        bundleFileName: String = buildBundleFileName(),
        includedRelativePaths: Set<String>? = null
    ): HostLogBundleExportResult {
        return HostLogManager.exportToUri(
            context = context,
            targetUri = targetUri,
            bundleFileName = bundleFileName,
            includedRelativePaths = includedRelativePaths
        )
    }

    fun exportToPublicDownloads(
        context: android.content.Context,
        bundleFileName: String = buildBundleFileName(),
        includedRelativePaths: Set<String>? = null
    ): HostLogBundleExportResult {
        return HostLogManager.exportToPublicDownloads(
            context = context,
            bundleFileName = bundleFileName,
            includedRelativePaths = includedRelativePaths
        )
    }
}
