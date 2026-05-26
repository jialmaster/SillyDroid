package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.pm.PackageInfo
import android.webkit.WebView
import androidx.core.content.pm.PackageInfoCompat
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue

private const val RECOMMENDED_CHROMIUM_MAJOR_VERSION = 111

data class WebViewRuntimeCompatibility(
    val providerPackageName: String,
    val providerVersionName: String,
    val providerVersionCode: String,
    val userAgent: String,
    val chromiumVersion: String,
    val chromiumMajorVersion: Int,
) {
    val isOutdated: Boolean
        get() = chromiumMajorVersion in 1 until RECOMMENDED_CHROMIUM_MAJOR_VERSION

    fun toDiagnosticText(): String {
        return buildString {
            append("providerPackage=${normalizeDiagnosticValue(providerPackageName)}")
            append(" providerVersionName=${normalizeDiagnosticValue(providerVersionName)}")
            append(" providerVersionCode=${normalizeDiagnosticValue(providerVersionCode)}")
            append(" chromiumVersion=${normalizeDiagnosticValue(chromiumVersion)}")
            append(" chromiumMajorVersion=${chromiumMajorVersion.takeIf { it > 0 } ?: "-"}")
            append(" recommendedChromiumMajor=$RECOMMENDED_CHROMIUM_MAJOR_VERSION")
            append(" outdated=$isOutdated")
            append(" userAgent=${normalizeDiagnosticValue(userAgent)}")
        }
    }

    companion object {
        fun from(webView: WebView, providerPackageInfo: PackageInfo?): WebViewRuntimeCompatibility {
            val userAgent = runCatching { webView.settings.userAgentString.orEmpty().trim() }
                .getOrDefault("")
            val chromiumVersion = parseChromiumVersion(userAgent)
            return WebViewRuntimeCompatibility(
                providerPackageName = providerPackageInfo?.packageName.orEmpty().trim(),
                providerVersionName = providerPackageInfo?.versionName.orEmpty().trim(),
                providerVersionCode = providerPackageInfo
                    ?.let { PackageInfoCompat.getLongVersionCode(it).toString() }
                    .orEmpty(),
                userAgent = userAgent,
                chromiumVersion = chromiumVersion,
                chromiumMajorVersion = parseMajorVersion(chromiumVersion),
            )
        }

        fun recommendedChromiumMajorVersion(): Int = RECOMMENDED_CHROMIUM_MAJOR_VERSION
    }
}

internal fun parseChromiumVersion(userAgent: String): String {
    // WebView provider 版本在部分厂商 ROM 上不是 Chromium 版本；兼容性提示必须以 UA 中的真实内核版本为准。
    val match = Regex("""(?:Chrome|CriOS)/(\d+(?:\.\d+){1,3})""", RegexOption.IGNORE_CASE)
        .find(userAgent)
    return match?.groupValues?.getOrNull(1).orEmpty()
}

internal fun parseMajorVersion(version: String): Int {
    val majorText = version.trim().substringBefore('.', missingDelimiterValue = version).trim()
    return majorText.toIntOrNull()?.takeIf { it > 0 } ?: 0
}
