package com.jm.sillydroid.data.logs

import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.FloatingBrowserPosition
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.model.settings.TavernServerLaunchMode
import com.jm.sillydroid.data.settings.HostConfigSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchModuleMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOptionSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchTargetFileSnapshot
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
            rootfsManifestRawJson = """{"runtimeVersion":"1.0.0","baseFlavor":"termux","baseVersion":"stable-dash.0.5.12-2"}""",
            serverManifestRawJson = """{"tag":"1.18.0","nodeVersion":"22.14.0"}""",
            runtimePatchMetadataSnapshot = sampleRuntimePatchMetadataSnapshot(),
            hostConfigSnapshot = sampleHostConfigSnapshot(),
            rootfsVersion = "1.0.0",
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
        assertTrue(bundleInfo.contains("rootfsVersion=1.0.0"))
        assertTrue(bundleInfo.contains("serverPayloadVersion=1.18.0+node.22.14.0"))
        assertTrue(bundleInfo.contains("rootfsManifestIncluded=true"))
        assertTrue(bundleInfo.contains("serverManifestIncluded=true"))
        assertTrue(bundleInfo.contains("runtimePatchManifestIncluded=true"))
        assertTrue(bundleInfo.contains("runtimePatchFrameworkVersion=0.3.0"))
        assertTrue(bundleInfo.contains("runtimePatchCompatibleTavernVersions=1.18.0"))
        assertTrue(bundleInfo.contains("runtimePatchModule=character-all-limited-concurrency title=角色索引并发限制 version=0.1.6"))
        assertTrue(bundleInfo.contains("settings=concurrency:select:1:default=auto:important=true"))
        assertTrue(bundleInfo.contains("targetFiles=src/endpoints/characters.js@255751D3BE5FE42FAA993882514A3D3DA9A19CB5E9C1B7E9883311D84F870B70"))
        assertTrue(bundleInfo.contains("hostConfigSnapshotPolicy=explicit-host-preferences-only"))
        assertTrue(bundleInfo.contains("nodeMaxOldSpaceMb=3072"))
        assertTrue(bundleInfo.contains("nodeMaxSemiSpaceMb=64"))
        assertTrue(bundleInfo.contains("browserEngine=SYSTEM_WEBVIEW"))
        assertTrue(bundleInfo.contains("browserZoomPercent=100"))
        assertTrue(bundleInfo.contains("browserPageZoomPercent=100"))
        assertTrue(bundleInfo.contains("tavernServerLaunchMode=AUTO"))
        assertTrue(bundleInfo.contains("tavernServerFastLaunchEnabled=true"))
        assertTrue(bundleInfo.contains("tavernRuntimePatchEnabled=true"))
        assertTrue(bundleInfo.contains("tavernRuntimePatchDisabledModuleIds=character-all-limited-concurrency"))
        assertTrue(bundleInfo.contains("tavernRuntimePatchSettingOverrides=character-all-limited-concurrency.concurrency=4"))
        assertTrue(bundleInfo.contains("crashLogUploadEnabled=true"))
        assertTrue(bundleInfo.contains("crashLogUploadPromptConsumed=true"))
        assertTrue(bundleInfo.contains("logFileCount=2"))
        assertTrue(bundleInfo.contains("rawArtifactCount=0"))
        assertTrue(bundleInfo.contains("includesCrashLog=true"))
        assertTrue(bundleInfo.contains("includesExitInfoLog=false"))
        assertTrue(bundleInfo.contains("includesExitInfoTraceArtifacts=false"))
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
            runtimePatchMetadataSnapshot = null,
            hostConfigSnapshot = sampleHostConfigSnapshot(),
            rootfsVersion = "1.0.0",
            serverPayloadVersion = "1.18.0+node.22.14.0"
        )
        val summary = HostLogBundleLogSummary(
            fileCount = 0,
            includesCrashLog = false,
            includesExitInfoLog = false,
            relativePaths = emptyList(),
            note = "no log artifacts found under android-tavern/logs"
        )

        val bundleInfo = HostLogBundleInfoFormatter.buildText(baseInfo = baseInfo, summary = summary)

        assertTrue(bundleInfo.contains("logFileCount=0"))
        assertTrue(bundleInfo.contains("rawArtifactCount=0"))
        assertTrue(bundleInfo.contains("note=no log artifacts found under android-tavern/logs"))
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
            rootfsManifestRawJson = """{"runtimeVersion":"1.0.0","baseFlavor":"termux","baseVersion":"stable-dash.0.5.12-2"}""",
            serverManifestRawJson = """{"tag":"1.18.0","nodeVersion":"22.14.0"}""",
            runtimePatchMetadataSnapshot = sampleRuntimePatchMetadataSnapshot(),
            hostConfigSnapshot = sampleHostConfigSnapshot(),
            rootfsVersion = "1.0.0",
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
        assertTrue(bundleInfoJson.contains("\"rootfsVersion\": \"1.0.0\""))
        assertTrue(bundleInfoJson.contains("\"rootfsManifest\": {\"runtimeVersion\":\"1.0.0\",\"baseFlavor\":\"termux\",\"baseVersion\":\"stable-dash.0.5.12-2\"}"))
        assertTrue(bundleInfoJson.contains("\"serverManifest\": {\"tag\":\"1.18.0\",\"nodeVersion\":\"22.14.0\"}"))
        assertTrue(bundleInfoJson.contains("\"runtimePatch\": {\"manifestIncluded\": true"))
        assertTrue(bundleInfoJson.contains("\"frameworkVersion\": \"0.3.0\""))
        assertTrue(bundleInfoJson.contains("\"compatibleTavernVersions\": [\"1.18.0\"]"))
        assertTrue(bundleInfoJson.contains("\"id\": \"character-all-limited-concurrency\""))
        assertTrue(bundleInfoJson.contains("\"title\": \"角色索引并发限制\""))
        assertTrue(bundleInfoJson.contains("\"supportedTavernVersions\": [\"1.18.0\"]"))
        assertTrue(bundleInfoJson.contains("\"settings\": [{\"key\": \"concurrency\", \"type\": \"select\""))
        assertTrue(bundleInfoJson.contains("\"important\": true"))
        assertTrue(bundleInfoJson.contains("\"targetFiles\": [{\"path\": \"src/endpoints/characters.js\", \"sha256\": \"255751D3BE5FE42FAA993882514A3D3DA9A19CB5E9C1B7E9883311D84F870B70\"}]"))
        assertTrue(bundleInfoJson.contains("\"hostConfig\": {"))
        assertTrue(bundleInfoJson.contains("\"snapshotPolicy\": \"explicit-host-preferences-only\""))
        assertTrue(bundleInfoJson.contains("\"servicePort\": 8000"))
        assertTrue(bundleInfoJson.contains("\"nodeMaxOldSpaceMb\": 3072"))
        assertTrue(bundleInfoJson.contains("\"nodeMaxSemiSpaceMb\": 64"))
        assertTrue(bundleInfoJson.contains("\"browserEngine\": \"SYSTEM_WEBVIEW\""))
        assertTrue(bundleInfoJson.contains("\"browserZoomPercent\": 100"))
        assertTrue(bundleInfoJson.contains("\"browserPageZoomPercent\": 100"))
        assertTrue(bundleInfoJson.contains("\"launchWebViewOnReady\": true"))
        assertTrue(bundleInfoJson.contains("\"backgroundHealthCheckEnabled\": false"))
        assertTrue(bundleInfoJson.contains("\"tavernServerLaunchMode\": \"AUTO\""))
        assertTrue(bundleInfoJson.contains("\"tavernServerFastLaunchEnabled\": true"))
        assertTrue(bundleInfoJson.contains("\"tavernRuntimePatchEnabled\": true"))
        assertTrue(bundleInfoJson.contains("\"tavernRuntimePatchDisabledModuleIds\": [\"character-all-limited-concurrency\"]"))
        assertTrue(bundleInfoJson.contains("\"tavernRuntimePatchSettingOverrides\": {\"character-all-limited-concurrency\": {\"concurrency\": \"4\"}}"))
        assertTrue(bundleInfoJson.contains("\"terminalFontSizePx\": 16"))
        assertTrue(bundleInfoJson.contains("\"terminalCursorBlinkEnabled\": true"))
        assertTrue(bundleInfoJson.contains("\"terminalExtraKeysEnabled\": true"))
        assertTrue(bundleInfoJson.contains("\"debugDiagnosticsEnabled\": false"))
        assertTrue(bundleInfoJson.contains("\"crashLogUploadEnabled\": true"))
        assertTrue(bundleInfoJson.contains("\"crashLogUploadPromptConsumed\": true"))
        assertTrue(bundleInfoJson.contains("\"floatingLogBubblePosition\": {\"horizontalFraction\": 0.75, \"verticalFraction\": 0.25}"))
        assertTrue(bundleInfoJson.contains("\"fileCount\": 2"))
        assertTrue(bundleInfoJson.contains("\"rawArtifactCount\": 0"))
        assertTrue(bundleInfoJson.contains("\"files\": [\"startup-20260517.log\", \"${HostLogManager.crashLogFileName}\"]"))
        assertTrue(bundleInfoJson.contains("\"supportedAbis\": [\"arm64-v8a\", \"armeabi-v7a\"]"))
    }

    @Test
    fun summarizeMarksExitInfoTraceArtifacts() {
        val logsDir = kotlin.io.path.createTempDirectory(prefix = "host-log-bundle-trace-summary").toFile()
        try {
            val exitInfoLog = java.io.File(logsDir, HostLogManager.exitInfoLogFileName).apply { writeText("exit-info") }
            java.io.File(logsDir, HostLogManager.exitInfoTraceDirectoryName).mkdirs()
            val traceFile = java.io.File(
                logsDir,
                "${HostLogManager.exitInfoTraceDirectoryName}/history-0-1000-pid-123-reason-crash_native-webview.trace"
            ).apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }

            val summary = HostLogBundleInfoFormatter.summarize(
                logFiles = listOf(exitInfoLog, traceFile),
                logsDir = logsDir
            )

            assertTrue(summary.includesExitInfoLog)
            assertTrue(summary.includesExitInfoTraceArtifacts)
            assertTrue(summary.relativePaths.contains(HostLogManager.exitInfoLogFileName))
            assertTrue(
                summary.relativePaths.contains(
                    "${HostLogManager.exitInfoTraceDirectoryName}/history-0-1000-pid-123-reason-crash_native-webview.trace"
                )
            )
        } finally {
            logsDir.deleteRecursively()
        }
    }

    private fun sampleHostConfigSnapshot(): HostConfigSnapshot {
        return HostConfigSnapshot(
            storageBackend = "SharedPreferences",
            storageName = "bootstrap-host-config",
            snapshotPolicy = "explicit-host-preferences-only",
            servicePort = 8000,
            nodeMaxOldSpaceMb = 3072,
            nodeMaxSemiSpaceMb = 64,
            hostDisplayMode = HostDisplayMode.NORMAL,
            browserEngine = BrowserEngine.SYSTEM_WEBVIEW,
            browserZoomPercent = 100,
            browserPageZoomPercent = 100,
            launchWebViewOnReady = true,
            backgroundHealthCheckEnabled = false,
            tavernServerLaunchMode = TavernServerLaunchMode.AUTO,
            tavernServerFastLaunchEnabled = true,
            tavernRuntimePatchEnabled = true,
            tavernRuntimePatchDisabledModuleIds = setOf("character-all-limited-concurrency"),
            tavernRuntimePatchSettingOverrides = mapOf(
                "character-all-limited-concurrency" to mapOf("concurrency" to "4")
            ),
            webViewPullRefreshEnabled = true,
            debugDiagnosticsEnabled = false,
            unrestrictedFileImportSelectionEnabled = false,
            terminalFontSizePx = 16,
            terminalCursorBlinkEnabled = true,
            terminalExtraKeysEnabled = true,
            floatingBrowserEnabled = true,
            floatingBrowserPosition = FloatingBrowserPosition(
                horizontalFraction = 1f,
                verticalFraction = 0.5f
            ),
            floatingLogBubbleEnabled = false,
            floatingLogRefreshIntervalMillis = 1000,
            floatingLogBubblePosition = FloatingLogBubblePosition(
                horizontalFraction = 0.75f,
                verticalFraction = 0.25f
            ),
            defaultExtensionsPromptConsumed = true,
            crashLogUploadEnabled = true,
            crashLogUploadPromptConsumed = true
        )
    }

    private fun sampleRuntimePatchMetadataSnapshot(): RuntimePatchMetadataSnapshot {
        return RuntimePatchMetadataSnapshot(
            schemaVersion = 1,
            frameworkId = "sillydroid-runtime-patches",
            frameworkVersion = "0.3.0",
            compatibleTavernVersions = listOf("1.18.0"),
            defaultPreset = "performance",
            manifestRawJson = """{"schemaVersion":1,"frameworkVersion":"0.3.0"}""",
            modules = listOf(
                RuntimePatchModuleMetadataSnapshot(
                    id = "character-all-limited-concurrency",
                    title = "角色索引并发限制",
                    version = "0.1.6",
                    description = "Limit characters all concurrency.",
                    supportedTavernVersions = listOf("1.18.0"),
                    defaultPresets = listOf("performance"),
                    targetFiles = listOf(
                        RuntimePatchTargetFileSnapshot(
                            path = "src/endpoints/characters.js",
                            sha256 = "255751D3BE5FE42FAA993882514A3D3DA9A19CB5E9C1B7E9883311D84F870B70"
                        )
                    ),
                    settings = listOf(
                        RuntimePatchSettingMetadataSnapshot(
                            key = "concurrency",
                            type = "select",
                            title = "角色索引并发",
                            description = "控制 /api/characters/all 首次索引角色卡时同时处理的数量。",
                            defaultValue = "auto",
                            unit = "tasks",
                            version = "1",
                            restartRequired = true,
                            important = true,
                            min = null,
                            max = null,
                            step = null,
                            options = listOf(
                                RuntimePatchSettingOptionSnapshot(
                                    value = "auto",
                                    label = "自动",
                                    description = "按设备可用 CPU 线程自动选择。"
                                ),
                                RuntimePatchSettingOptionSnapshot(
                                    value = "4",
                                    label = "4",
                                    description = "平衡速度和内存。"
                                )
                            )
                        )
                    ),
                    manifestIncluded = true
                )
            )
        )
    }
}
