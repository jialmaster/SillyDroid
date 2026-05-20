package com.jm.sillydroid.data.logs

import com.jm.sillydroid.data.settings.HostConfigSnapshot
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogBundleInfoFormatterTest {
    @Test
    fun buildIncludesDiagnosticBaseInfoAndLogSummary() {
        val baseInfo = HostLogBundleBaseInfo(
            bundleFilePrefix = "sillydroid-logs",
            exportedAt = "2026-05-17 11:22:33.444",
            packageName = "com.jm.sillydroid",
            hostVersion = "2.3.4",
            appVersionName = "2.3.4+tavern.1.18.0",
            appVersionCode = "123",
            buildType = "release",
            androidVersion = "16",
            androidSdk = "36",
            device = "Google Pixel 9",
            deviceManufacturer = "Google",
            deviceModel = "Pixel 9",
            deviceBrand = "google",
            deviceDevice = "tokay",
            deviceProduct = "tokay_beta",
            supportedAbis = "arm64-v8a,armeabi-v7a",
            webViewPackageName = "com.google.android.webview",
            webViewVersionName = "136.0.7103.125",
            webViewVersionCode = "710312500",
            rootfsManifestRawJson = """{"runtimeVersion":"24.04+proot.5.4.0","baseFlavor":"ubuntu"}""",
            serverManifestRawJson = """{"tag":"1.18.0","nodeVersion":"22.14.0"}""",
            hostConfigSnapshot = sampleHostConfigSnapshot(),
            rootfsVersion = "24.04+proot.5.4.0",
            serverPayloadVersion = "1.18.0+node.22.14.0"
        )
        val summary = HostLogBundleLogSummary(
            fileCount = 2,
            includesCrashLog = true,
            includesExitInfoLog = false,
            relativePaths = listOf("startup-20260517.log", HostLogManager.crashLogFileName)
        )

        val bundleInfo = HostLogBundleInfoFormatter.buildText(baseInfo = baseInfo, summary = summary)

        assertTrue(bundleInfo.contains("appVersionName=2.3.4+tavern.1.18.0"))
        assertTrue(bundleInfo.contains("androidVersion=16"))
        assertTrue(bundleInfo.contains("deviceModel=Pixel 9"))
        assertTrue(bundleInfo.contains("webViewPackageName=com.google.android.webview"))
        assertTrue(bundleInfo.contains("webViewVersionName=136.0.7103.125"))
        assertTrue(bundleInfo.contains("rootfsVersion=24.04+proot.5.4.0"))
        assertTrue(bundleInfo.contains("serverPayloadVersion=1.18.0+node.22.14.0"))
        assertTrue(bundleInfo.contains("rootfsManifestIncluded=true"))
        assertTrue(bundleInfo.contains("serverManifestIncluded=true"))
        assertTrue(bundleInfo.contains("hostConfigSnapshotPolicy=explicit-host-preferences-only"))
        assertTrue(bundleInfo.contains("logFileCount=2"))
        assertTrue(bundleInfo.contains("includesCrashLog=true"))
        assertTrue(bundleInfo.contains("includesExitInfoLog=false"))
    }

    @Test
    fun buildMarksEmptyBundles() {
        val baseInfo = HostLogBundleBaseInfo(
            bundleFilePrefix = "sillydroid-logs",
            exportedAt = "2026-05-17 11:22:33.444",
            packageName = "com.jm.sillydroid",
            hostVersion = "2.3.4",
            appVersionName = "2.3.4",
            appVersionCode = "123",
            buildType = "debug",
            androidVersion = "16",
            androidSdk = "36",
            device = "Google Pixel 9",
            deviceManufacturer = "Google",
            deviceModel = "Pixel 9",
            deviceBrand = "google",
            deviceDevice = "tokay",
            deviceProduct = "tokay_beta",
            supportedAbis = "arm64-v8a",
            webViewPackageName = "com.google.android.webview",
            webViewVersionName = "136.0.7103.125",
            webViewVersionCode = "710312500",
            rootfsManifestRawJson = null,
            serverManifestRawJson = null,
            hostConfigSnapshot = sampleHostConfigSnapshot(),
            rootfsVersion = "24.04+proot.5.4.0",
            serverPayloadVersion = "1.18.0+node.22.14.0"
        )
        val summary = HostLogBundleLogSummary(
            fileCount = 0,
            includesCrashLog = false,
            includesExitInfoLog = false,
            relativePaths = emptyList(),
            note = "no .log files found under android-tavern/logs"
        )

        val bundleInfo = HostLogBundleInfoFormatter.buildText(baseInfo = baseInfo, summary = summary)

        assertTrue(bundleInfo.contains("logFileCount=0"))
        assertTrue(bundleInfo.contains("note=no .log files found under android-tavern/logs"))
    }

    @Test
    fun buildJsonIncludesStructuredDiagnosticInfo() {
        val baseInfo = HostLogBundleBaseInfo(
            bundleFilePrefix = "sillydroid-logs",
            exportedAt = "2026-05-17 11:22:33.444",
            packageName = "com.jm.sillydroid",
            hostVersion = "2.3.4",
            appVersionName = "2.3.4+tavern.1.18.0",
            appVersionCode = "123",
            buildType = "release",
            androidVersion = "16",
            androidSdk = "36",
            device = "Google Pixel 9",
            deviceManufacturer = "Google",
            deviceModel = "Pixel 9",
            deviceBrand = "google",
            deviceDevice = "tokay",
            deviceProduct = "tokay_beta",
            supportedAbis = "arm64-v8a,armeabi-v7a",
            webViewPackageName = "com.google.android.webview",
            webViewVersionName = "136.0.7103.125",
            webViewVersionCode = "710312500",
            rootfsManifestRawJson = """{"runtimeVersion":"24.04+proot.5.4.0","baseFlavor":"ubuntu"}""",
            serverManifestRawJson = """{"tag":"1.18.0","nodeVersion":"22.14.0"}""",
            hostConfigSnapshot = sampleHostConfigSnapshot(),
            rootfsVersion = "24.04+proot.5.4.0",
            serverPayloadVersion = "1.18.0+node.22.14.0"
        )
        val summary = HostLogBundleLogSummary(
            fileCount = 2,
            includesCrashLog = true,
            includesExitInfoLog = false,
            relativePaths = listOf("startup-20260517.log", HostLogManager.crashLogFileName)
        )

        val bundleInfoJson = HostLogBundleInfoFormatter.buildJson(baseInfo = baseInfo, summary = summary)

        assertTrue(bundleInfoJson.contains("\"versionName\": \"2.3.4+tavern.1.18.0\""))
        assertTrue(bundleInfoJson.contains("\"webView\": {"))
        assertTrue(bundleInfoJson.contains("\"versionName\": \"136.0.7103.125\""))
        assertTrue(bundleInfoJson.contains("\"rootfsVersion\": \"24.04+proot.5.4.0\""))
        assertTrue(bundleInfoJson.contains("\"rootfsManifest\": {\"runtimeVersion\":\"24.04+proot.5.4.0\",\"baseFlavor\":\"ubuntu\"}"))
        assertTrue(bundleInfoJson.contains("\"serverManifest\": {\"tag\":\"1.18.0\",\"nodeVersion\":\"22.14.0\"}"))
        assertTrue(bundleInfoJson.contains("\"hostConfig\": {"))
        assertTrue(bundleInfoJson.contains("\"snapshotPolicy\": \"explicit-host-preferences-only\""))
        assertTrue(bundleInfoJson.contains("\"servicePort\": 8000"))
        assertTrue(bundleInfoJson.contains("\"terminalFontSizePx\": 16"))
        assertTrue(bundleInfoJson.contains("\"terminalCursorBlinkEnabled\": true"))
        assertTrue(bundleInfoJson.contains("\"terminalExtraKeysEnabled\": true"))
        assertTrue(bundleInfoJson.contains("\"debugDiagnosticsEnabled\": false"))
        assertTrue(bundleInfoJson.contains("\"floatingLogBubblePosition\": {\"horizontalFraction\": 0.75, \"verticalFraction\": 0.25}"))
        assertTrue(bundleInfoJson.contains("\"fileCount\": 2"))
        assertTrue(bundleInfoJson.contains("\"files\": [\"startup-20260517.log\", \"${HostLogManager.crashLogFileName}\"]"))
        assertTrue(bundleInfoJson.contains("\"supportedAbis\": [\"arm64-v8a\", \"armeabi-v7a\"]"))
    }

    private fun sampleHostConfigSnapshot(): HostConfigSnapshot {
        return HostConfigSnapshot(
            storageBackend = "SharedPreferences",
            storageName = "bootstrap-host-config",
            snapshotPolicy = "explicit-host-preferences-only",
            servicePort = 8000,
            webViewPullRefreshEnabled = true,
            debugDiagnosticsEnabled = false,
            terminalFontSizePx = 16,
            terminalCursorBlinkEnabled = true,
            terminalExtraKeysEnabled = true,
            floatingLogBubbleEnabled = false,
            floatingLogRefreshIntervalMillis = 1000,
            floatingLogBubblePosition = FloatingLogBubblePosition(
                horizontalFraction = 0.75f,
                verticalFraction = 0.25f
            ),
            defaultExtensionsPromptConsumed = true
        )
    }
}
