package com.jm.sillydroid.feature.main.components.download

import android.webkit.URLUtil

fun resolveDownloadFileName(rawName: String?, fallbackUrl: String?): String {
    val candidate = rawName.orEmpty().trim()
    if (candidate.isNotBlank()) {
        return candidate.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    if (!fallbackUrl.isNullOrBlank()) {
        return URLUtil.guessFileName(fallbackUrl, null, null)
    }

    return "download"
}
