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
        bundleFileName: String = buildBundleFileName()
    ): HostLogBundleExportResult {
        return HostLogManager.exportToUri(context, targetUri, bundleFileName)
    }

    fun exportToPublicDownloads(
        context: android.content.Context,
        bundleFileName: String = buildBundleFileName()
    ): HostLogBundleExportResult {
        return HostLogManager.exportToPublicDownloads(context, bundleFileName)
    }
}
