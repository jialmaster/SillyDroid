package com.jm.sillydroid.data.logs

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.webkit.WebView
import com.jm.sillydroid.data.runtime.AssetRuntimeMetadataRepository
import com.jm.sillydroid.data.settings.HostConfigSnapshot
import com.jm.sillydroid.data.settings.HostConfigSnapshotExporter
import com.jm.sillydroid.domain.bootstrap.RuntimePatchMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchModuleMetadataSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class HostLogBundleBaseInfo(
    val bundleFilePrefix: String,
    val exportedAt: String,
    val packageName: String,
    val hostVersion: String,
    val appVersionName: String,
    val appVersionCode: String,
    val buildType: String,
    val androidVersion: String,
    val androidSdk: String,
    val device: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceBrand: String,
    val deviceDevice: String,
    val deviceProduct: String,
    val supportedAbis: String,
    val webViewPackageName: String,
    val webViewVersionName: String,
    val webViewVersionCode: String,
    val rootfsManifestRawJson: String?,
    val serverManifestRawJson: String?,
    val runtimePatchMetadataSnapshot: RuntimePatchMetadataSnapshot?,
    val hostConfigSnapshot: HostConfigSnapshot,
    val rootfsVersion: String,
    val serverPayloadVersion: String
)

internal data class HostLogBundleLogSummary(
    val fileCount: Int,
    val includesCrashLog: Boolean,
    val includesExitInfoLog: Boolean,
    val rawArtifactCount: Int = 0,
    val includesExitInfoTraceArtifacts: Boolean = false,
    val relativePaths: List<String>,
    val note: String? = null
)

internal object HostLogBundleBaseInfoResolver {
    private const val unknownValue = "unknown"

    // 日志 ZIP 导出后通常脱离现场单独流转，这里统一补齐 APK / 设备 / rootfs / server 版本，
    // 避免排查问题时只能看日志正文却不知道对应的是哪台设备、哪个宿主和哪套运行时。
    fun resolve(
        context: Context,
        bundleFilePrefix: String,
        nowMillis: Long = System.currentTimeMillis()
    ): HostLogBundleBaseInfo {
        val appContext = context.applicationContext
        val packageInfo = resolveCurrentPackageInfo(appContext)
        val webViewPackageInfo = resolveCurrentWebViewPackageInfoOrNull()
        val runtimeMetadataRepository = AssetRuntimeMetadataRepository(appContext)
        val hostConfigSnapshot = HostConfigSnapshotExporter.build(appContext)
        val appVersionName = packageInfo.versionName.orEmpty().trim()
        val deviceManufacturer = normalizeOrUnknown(Build.MANUFACTURER)
        val deviceModel = normalizeOrUnknown(Build.MODEL)

        return HostLogBundleBaseInfo(
            bundleFilePrefix = bundleFilePrefix,
            exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(Date(nowMillis)),
            packageName = appContext.packageName,
            hostVersion = BuildConfig.SILLYDROID_HOST_VERSION,
            appVersionName = appVersionName.ifBlank { unknownValue },
            appVersionCode = packageInfo.longVersionCode.toString(),
            buildType = BuildConfig.BUILD_TYPE,
            androidVersion = normalizeOrUnknown(Build.VERSION.RELEASE),
            androidSdk = Build.VERSION.SDK_INT.toString(),
            device = buildDeviceLabel(deviceManufacturer, deviceModel),
            deviceManufacturer = deviceManufacturer,
            deviceModel = deviceModel,
            deviceBrand = normalizeOrUnknown(Build.BRAND),
            deviceDevice = normalizeOrUnknown(Build.DEVICE),
            deviceProduct = normalizeOrUnknown(Build.PRODUCT),
            supportedAbis = Build.SUPPORTED_ABIS
                .map { abi -> abi.trim() }
                .filter { abi -> abi.isNotBlank() }
                .joinToString(",")
                .ifBlank { unknownValue },
            webViewPackageName = normalizeOrUnknown(webViewPackageInfo?.packageName),
            webViewVersionName = normalizeOrUnknown(webViewPackageInfo?.versionName),
            webViewVersionCode = webViewPackageInfo?.longVersionCode?.toString().orEmpty().ifBlank { unknownValue },
            rootfsManifestRawJson = runtimeMetadataRepository.readRootfsManifestRawJson(),
            serverManifestRawJson = runtimeMetadataRepository.readServerManifestRawJson(),
            runtimePatchMetadataSnapshot = runtimeMetadataRepository.resolveRuntimePatchMetadataSnapshot(),
            hostConfigSnapshot = hostConfigSnapshot,
            rootfsVersion = normalizeOrUnknown(runtimeMetadataRepository.resolveRuntimeVersionLabel()),
            serverPayloadVersion = normalizeOrUnknown(
                runtimeMetadataRepository.resolveServerPayloadVersionLabel(
                    upstreamVersion = "",
                    currentVersionName = appVersionName
                )
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveCurrentPackageInfo(context: Context): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }

    private fun resolveCurrentWebViewPackageInfoOrNull(): PackageInfo? {
        return runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
    }

    private fun normalizeOrUnknown(value: String?): String {
        return value.orEmpty().trim().ifBlank { unknownValue }
    }

    private fun buildDeviceLabel(manufacturer: String, model: String): String {
        return listOf(manufacturer, model)
            .distinct()
            .joinToString(" ")
            .trim()
            .ifBlank { unknownValue }
    }
}

internal object HostLogBundleInfoFormatter {
    private const val unknownValue = "unknown"

    // 文本版保留给人工直接打开速读；JSON 版给后续自动分析或让用户直接贴给问题定位脚本使用。
    fun buildText(baseInfo: HostLogBundleBaseInfo, summary: HostLogBundleLogSummary): String {
        return buildString {
            appendLine("bundleFilePrefix=${baseInfo.bundleFilePrefix}")
            appendLine("exportedAt=${baseInfo.exportedAt}")
            appendLine("packageName=${baseInfo.packageName}")
            appendLine("hostVersion=${baseInfo.hostVersion}")
            appendLine("appVersionName=${baseInfo.appVersionName}")
            appendLine("appVersionCode=${baseInfo.appVersionCode}")
            appendLine("buildType=${baseInfo.buildType}")
            appendLine("androidVersion=${baseInfo.androidVersion}")
            appendLine("androidSdk=${baseInfo.androidSdk}")
            appendLine("device=${baseInfo.device}")
            appendLine("deviceManufacturer=${baseInfo.deviceManufacturer}")
            appendLine("deviceModel=${baseInfo.deviceModel}")
            appendLine("deviceBrand=${baseInfo.deviceBrand}")
            appendLine("deviceDevice=${baseInfo.deviceDevice}")
            appendLine("deviceProduct=${baseInfo.deviceProduct}")
            appendLine("supportedAbis=${baseInfo.supportedAbis}")
            appendLine("webViewPackageName=${baseInfo.webViewPackageName}")
            appendLine("webViewVersionName=${baseInfo.webViewVersionName}")
            appendLine("webViewVersionCode=${baseInfo.webViewVersionCode}")
            appendLine("rootfsVersion=${baseInfo.rootfsVersion}")
            appendLine("serverPayloadVersion=${baseInfo.serverPayloadVersion}")
            appendLine("rootfsManifestIncluded=${baseInfo.rootfsManifestRawJson != null}")
            appendLine("serverManifestIncluded=${baseInfo.serverManifestRawJson != null}")
            appendRuntimePatchText(baseInfo.runtimePatchMetadataSnapshot)
            appendLine("hostConfigSnapshotPolicy=${baseInfo.hostConfigSnapshot.snapshotPolicy}")
            appendLine("nodeMaxOldSpaceMb=${baseInfo.hostConfigSnapshot.nodeMaxOldSpaceMb}")
            appendLine("nodeMaxSemiSpaceMb=${baseInfo.hostConfigSnapshot.nodeMaxSemiSpaceMb}")
            appendLine("browserEngine=${baseInfo.hostConfigSnapshot.browserEngine.name}")
            appendLine("browserZoomPercent=${baseInfo.hostConfigSnapshot.browserZoomPercent}")
            appendLine("browserPageZoomPercent=${baseInfo.hostConfigSnapshot.browserPageZoomPercent}")
            appendLine("tavernServerLaunchMode=${baseInfo.hostConfigSnapshot.tavernServerLaunchMode.name}")
            appendLine("tavernServerFastLaunchEnabled=${baseInfo.hostConfigSnapshot.tavernServerFastLaunchEnabled}")
            appendLine("tavernRuntimePatchEnabled=${baseInfo.hostConfigSnapshot.tavernRuntimePatchEnabled}")
            appendLine("tavernRuntimePatchDisabledModuleIds=${baseInfo.hostConfigSnapshot.tavernRuntimePatchDisabledModuleIds.sorted().joinToString(",")}")
            appendLine("tavernRuntimePatchSettingOverrides=${formatRuntimePatchSettingOverrides(baseInfo.hostConfigSnapshot.tavernRuntimePatchSettingOverrides)}")
            appendLine("crashLogUploadEnabled=${baseInfo.hostConfigSnapshot.crashLogUploadEnabled}")
            appendLine("crashLogUploadPromptConsumed=${baseInfo.hostConfigSnapshot.crashLogUploadPromptConsumed}")
            appendLine("logFileCount=${summary.fileCount}")
            appendLine("rawArtifactCount=${summary.rawArtifactCount}")
            appendLine("includesCrashLog=${summary.includesCrashLog}")
            appendLine("includesExitInfoLog=${summary.includesExitInfoLog}")
            appendLine("includesExitInfoTraceArtifacts=${summary.includesExitInfoTraceArtifacts}")
            if (summary.note != null) {
                appendLine("note=${summary.note}")
            }
        }
    }

    fun buildJson(baseInfo: HostLogBundleBaseInfo, summary: HostLogBundleLogSummary): String {
        val supportedAbis = baseInfo.supportedAbis
            .split(',')
            .map { abi -> abi.trim() }
            .filter { abi -> abi.isNotBlank() && abi != unknownValue }

        return buildString {
            appendLine("{")
            appendLine("  \"bundleFilePrefix\": ${jsonString(baseInfo.bundleFilePrefix)},")
            appendLine("  \"exportedAt\": ${jsonString(baseInfo.exportedAt)},")
            appendLine("  \"packageName\": ${jsonString(baseInfo.packageName)},")
            appendLine("  \"hostVersion\": ${jsonString(baseInfo.hostVersion)},")
            appendLine("  \"app\": {")
            appendLine("    \"versionName\": ${jsonString(baseInfo.appVersionName)},")
            appendLine("    \"versionCode\": ${jsonString(baseInfo.appVersionCode)},")
            appendLine("    \"buildType\": ${jsonString(baseInfo.buildType)}")
            appendLine("  },")
            appendLine("  \"android\": {")
            appendLine("    \"version\": ${jsonString(baseInfo.androidVersion)},")
            appendLine("    \"sdk\": ${baseInfo.androidSdk}")
            appendLine("  },")
            appendLine("  \"device\": {")
            appendLine("    \"label\": ${jsonString(baseInfo.device)},")
            appendLine("    \"manufacturer\": ${jsonString(baseInfo.deviceManufacturer)},")
            appendLine("    \"model\": ${jsonString(baseInfo.deviceModel)},")
            appendLine("    \"brand\": ${jsonString(baseInfo.deviceBrand)},")
            appendLine("    \"device\": ${jsonString(baseInfo.deviceDevice)},")
            appendLine("    \"product\": ${jsonString(baseInfo.deviceProduct)},")
            appendLine("    \"supportedAbis\": ${jsonStringArray(supportedAbis)}")
            appendLine("  },")
            appendLine("  \"webView\": {")
            appendLine("    \"packageName\": ${jsonString(baseInfo.webViewPackageName)},")
            appendLine("    \"versionName\": ${jsonString(baseInfo.webViewVersionName)},")
            appendLine("    \"versionCode\": ${jsonString(baseInfo.webViewVersionCode)}")
            appendLine("  },")
            appendLine("  \"runtime\": {")
            appendLine("    \"rootfsVersion\": ${jsonString(baseInfo.rootfsVersion)},")
            appendLine("    \"serverPayloadVersion\": ${jsonString(baseInfo.serverPayloadVersion)},")
            appendLine("    \"rootfsManifest\": ${jsonRawOrNull(baseInfo.rootfsManifestRawJson)},")
            appendLine("    \"serverManifest\": ${jsonRawOrNull(baseInfo.serverManifestRawJson)},")
            appendLine("    \"runtimePatch\": ${jsonRuntimePatchMetadata(baseInfo.runtimePatchMetadataSnapshot)}")
            appendLine("  },")
            appendLine("  \"hostConfig\": {")
            appendLine("    \"storageBackend\": ${jsonString(baseInfo.hostConfigSnapshot.storageBackend)},")
            appendLine("    \"storageName\": ${jsonString(baseInfo.hostConfigSnapshot.storageName)},")
            appendLine("    \"snapshotPolicy\": ${jsonString(baseInfo.hostConfigSnapshot.snapshotPolicy)},")
            appendLine("    \"servicePort\": ${baseInfo.hostConfigSnapshot.servicePort},")
            appendLine("    \"nodeMaxOldSpaceMb\": ${baseInfo.hostConfigSnapshot.nodeMaxOldSpaceMb},")
            appendLine("    \"nodeMaxSemiSpaceMb\": ${baseInfo.hostConfigSnapshot.nodeMaxSemiSpaceMb},")
            appendLine("    \"browserEngine\": ${jsonString(baseInfo.hostConfigSnapshot.browserEngine.name)},")
            appendLine("    \"browserZoomPercent\": ${baseInfo.hostConfigSnapshot.browserZoomPercent},")
            appendLine("    \"browserPageZoomPercent\": ${baseInfo.hostConfigSnapshot.browserPageZoomPercent},")
            appendLine("    \"launchWebViewOnReady\": ${baseInfo.hostConfigSnapshot.launchWebViewOnReady},")
            appendLine("    \"backgroundHealthCheckEnabled\": ${baseInfo.hostConfigSnapshot.backgroundHealthCheckEnabled},")
            appendLine("    \"tavernServerLaunchMode\": ${jsonString(baseInfo.hostConfigSnapshot.tavernServerLaunchMode.name)},")
            appendLine("    \"tavernServerFastLaunchEnabled\": ${baseInfo.hostConfigSnapshot.tavernServerFastLaunchEnabled},")
            appendLine("    \"tavernRuntimePatchEnabled\": ${baseInfo.hostConfigSnapshot.tavernRuntimePatchEnabled},")
            appendLine("    \"tavernRuntimePatchDisabledModuleIds\": ${jsonStringArray(baseInfo.hostConfigSnapshot.tavernRuntimePatchDisabledModuleIds.sorted())},")
            appendLine("    \"tavernRuntimePatchSettingOverrides\": ${jsonRuntimePatchSettingOverrides(baseInfo.hostConfigSnapshot.tavernRuntimePatchSettingOverrides)},")
            appendLine("    \"webViewPullRefreshEnabled\": ${baseInfo.hostConfigSnapshot.webViewPullRefreshEnabled},")
            appendLine("    \"debugDiagnosticsEnabled\": ${baseInfo.hostConfigSnapshot.debugDiagnosticsEnabled},")
            appendLine("    \"terminalFontSizePx\": ${baseInfo.hostConfigSnapshot.terminalFontSizePx},")
            appendLine("    \"terminalCursorBlinkEnabled\": ${baseInfo.hostConfigSnapshot.terminalCursorBlinkEnabled},")
            appendLine("    \"terminalExtraKeysEnabled\": ${baseInfo.hostConfigSnapshot.terminalExtraKeysEnabled},")
            appendLine("    \"floatingBrowserEnabled\": ${baseInfo.hostConfigSnapshot.floatingBrowserEnabled},")
            appendLine(
                "    \"floatingBrowserPosition\": ${jsonFloatingBrowserPositionOrNull(baseInfo.hostConfigSnapshot)},"
            )
            appendLine("    \"floatingLogBubbleEnabled\": ${baseInfo.hostConfigSnapshot.floatingLogBubbleEnabled},")
            appendLine("    \"floatingLogRefreshIntervalMillis\": ${baseInfo.hostConfigSnapshot.floatingLogRefreshIntervalMillis},")
            appendLine(
                "    \"floatingLogBubblePosition\": ${jsonFloatingLogBubblePositionOrNull(baseInfo.hostConfigSnapshot)}," 
            )
            appendLine("    \"crashLogUploadEnabled\": ${baseInfo.hostConfigSnapshot.crashLogUploadEnabled},")
            appendLine(
                "    \"crashLogUploadPromptConsumed\": ${baseInfo.hostConfigSnapshot.crashLogUploadPromptConsumed},"
            )
            appendLine(
                "    \"defaultExtensionsPromptConsumed\": ${baseInfo.hostConfigSnapshot.defaultExtensionsPromptConsumed}"
            )
            appendLine("  },")
            appendLine("  \"logs\": {")
            appendLine("    \"fileCount\": ${summary.fileCount},")
            appendLine("    \"rawArtifactCount\": ${summary.rawArtifactCount},")
            appendLine("    \"includesCrashLog\": ${summary.includesCrashLog},")
            appendLine("    \"includesExitInfoLog\": ${summary.includesExitInfoLog},")
            appendLine("    \"includesExitInfoTraceArtifacts\": ${summary.includesExitInfoTraceArtifacts},")
            appendLine("    \"files\": ${jsonStringArray(summary.relativePaths)}${if (summary.note != null) "," else ""}")
            if (summary.note != null) {
                appendLine("    \"note\": ${jsonString(summary.note)}")
            }
            appendLine("  }")
            append('}')
        }
    }

    fun summarize(logFiles: List<File>, logsDir: File): HostLogBundleLogSummary {
        val relativePaths = logFiles.map { file ->
            file.relativeTo(logsDir).invariantSeparatorsPath
        }
        return HostLogBundleLogSummary(
            fileCount = logFiles.size,
            includesCrashLog = logFiles.any { it.name.equals(HostLogManager.crashLogFileName, ignoreCase = true) },
            includesExitInfoLog = logFiles.any { it.name.equals(HostLogManager.exitInfoLogFileName, ignoreCase = true) },
            rawArtifactCount = relativePaths.count { path -> !path.endsWith(".log", ignoreCase = true) },
            includesExitInfoTraceArtifacts = relativePaths.any { path ->
                path.startsWith("${HostLogManager.exitInfoTraceDirectoryName}/", ignoreCase = true)
            },
            relativePaths = relativePaths,
            note = if (logFiles.isEmpty()) "no log artifacts found under android-tavern/logs" else null
        )
    }

    private fun StringBuilder.appendRuntimePatchText(metadata: RuntimePatchMetadataSnapshot?) {
        appendLine("runtimePatchManifestIncluded=${metadata != null}")
        if (metadata == null) {
            return
        }
        appendLine("runtimePatchSchemaVersion=${metadata.schemaVersion ?: unknownValue}")
        appendLine("runtimePatchFrameworkId=${metadata.frameworkId.ifBlank { unknownValue }}")
        appendLine("runtimePatchFrameworkVersion=${metadata.frameworkVersion.ifBlank { unknownValue }}")
        appendLine("runtimePatchCompatibleTavernVersions=${metadata.compatibleTavernVersions.joinToString(",")}")
        appendLine("runtimePatchDefaultPreset=${metadata.defaultPreset.ifBlank { unknownValue }}")
        appendLine("runtimePatchModuleCount=${metadata.modules.size}")
        metadata.modules.forEach { module ->
            appendLine(
                buildString {
                    append("runtimePatchModule=")
                    append(module.id.ifBlank { unknownValue })
                    append(" title=")
                    append(module.title.ifBlank { unknownValue })
                    append(" version=")
                    append(module.version.ifBlank { unknownValue })
                    append(" supportedTavernVersions=")
                    append(module.supportedTavernVersions.joinToString(","))
                    append(" defaultPresets=")
                    append(module.defaultPresets.joinToString(","))
                    append(" targetFiles=")
                    append(module.targetFiles.joinToString(";") { targetFile ->
                        listOf(targetFile.path, targetFile.sha256)
                            .filter { value -> value.isNotBlank() }
                            .joinToString("@")
                    })
                    append(" settings=")
                    append(module.settings.joinToString(";") { setting ->
                        listOf(setting.key, setting.type, setting.version, "default=${setting.defaultValue}", "important=${setting.important}")
                            .filter { value -> value.isNotBlank() }
                            .joinToString(":")
                    })
                    append(" manifestIncluded=")
                    append(module.manifestIncluded)
                }
            )
        }
    }

    private fun jsonStringArray(values: List<String>): String {
        return values.joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }
    }

    private fun jsonRuntimePatchMetadata(metadata: RuntimePatchMetadataSnapshot?): String {
        if (metadata == null) {
            return """{"manifestIncluded": false}"""
        }
        return buildString {
            append('{')
            append("\"manifestIncluded\": true, ")
            append("\"schemaVersion\": ")
            append(metadata.schemaVersion?.toString() ?: "null")
            append(", \"frameworkId\": ")
            append(jsonString(metadata.frameworkId))
            append(", \"frameworkVersion\": ")
            append(jsonString(metadata.frameworkVersion))
            append(", \"compatibleTavernVersions\": ")
            append(jsonStringArray(metadata.compatibleTavernVersions))
            append(", \"defaultPreset\": ")
            append(jsonString(metadata.defaultPreset))
            append(", \"modules\": ")
            append(jsonRuntimePatchModules(metadata.modules))
            append('}')
        }
    }

    private fun jsonRuntimePatchModules(modules: List<RuntimePatchModuleMetadataSnapshot>): String {
        return modules.joinToString(prefix = "[", postfix = "]") { module ->
            buildString {
                append('{')
                append("\"id\": ")
                append(jsonString(module.id))
                append(", \"title\": ")
                append(jsonString(module.title))
                append(", \"version\": ")
                append(jsonString(module.version))
                append(", \"description\": ")
                append(jsonString(module.description))
                append(", \"supportedTavernVersions\": ")
                append(jsonStringArray(module.supportedTavernVersions))
                append(", \"defaultPresets\": ")
                append(jsonStringArray(module.defaultPresets))
                append(", \"targetFiles\": ")
                append(jsonRuntimePatchTargetFiles(module.targetFiles))
                append(", \"settings\": ")
                append(jsonRuntimePatchSettings(module.settings))
                append(", \"manifestIncluded\": ")
                append(module.manifestIncluded)
                append('}')
            }
        }
    }

    private fun jsonRuntimePatchSettings(
        settings: List<com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingMetadataSnapshot>
    ): String {
        return settings.joinToString(prefix = "[", postfix = "]") { setting ->
            buildString {
                append('{')
                append("\"key\": ")
                append(jsonString(setting.key))
                append(", \"type\": ")
                append(jsonString(setting.type))
                append(", \"title\": ")
                append(jsonString(setting.title))
                append(", \"description\": ")
                append(jsonString(setting.description))
                append(", \"defaultValue\": ")
                append(jsonString(setting.defaultValue))
                append(", \"unit\": ")
                append(jsonString(setting.unit))
                append(", \"version\": ")
                append(jsonString(setting.version))
                append(", \"restartRequired\": ")
                append(setting.restartRequired)
                append(", \"important\": ")
                append(setting.important)
                append(", \"min\": ")
                append(setting.min?.toString() ?: "null")
                append(", \"max\": ")
                append(setting.max?.toString() ?: "null")
                append(", \"step\": ")
                append(setting.step?.toString() ?: "null")
                append(", \"options\": ")
                append(jsonRuntimePatchSettingOptions(setting.options))
                append('}')
            }
        }
    }

    private fun jsonRuntimePatchSettingOptions(
        options: List<com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOptionSnapshot>
    ): String {
        return options.joinToString(prefix = "[", postfix = "]") { option ->
            buildString {
                append('{')
                append("\"value\": ")
                append(jsonString(option.value))
                append(", \"label\": ")
                append(jsonString(option.label))
                append(", \"description\": ")
                append(jsonString(option.description))
                append('}')
            }
        }
    }

    private fun jsonRuntimePatchTargetFiles(targetFiles: List<com.jm.sillydroid.domain.bootstrap.RuntimePatchTargetFileSnapshot>): String {
        return targetFiles.joinToString(prefix = "[", postfix = "]") { targetFile ->
            buildString {
                append('{')
                append("\"path\": ")
                append(jsonString(targetFile.path))
                append(", \"sha256\": ")
                append(jsonString(targetFile.sha256))
                append('}')
            }
        }
    }

    private fun formatRuntimePatchSettingOverrides(overrides: Map<String, Map<String, String>>): String {
        return overrides.toSortedMap()
            .flatMap { (moduleId, settings) ->
                settings.toSortedMap().map { (settingKey, value) -> "$moduleId.$settingKey=$value" }
            }
            .joinToString(",")
    }

    private fun jsonRuntimePatchSettingOverrides(overrides: Map<String, Map<String, String>>): String {
        return overrides.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (moduleId, settings) ->
            buildString {
                append(jsonString(moduleId))
                append(": ")
                append(settings.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (settingKey, value) ->
                    "${jsonString(settingKey)}: ${jsonString(value)}"
                })
            }
        }
    }

    private fun jsonRawOrNull(value: String?): String {
        return value?.trim()?.takeIf { rawJson -> rawJson.isNotBlank() } ?: "null"
    }

    private fun jsonFloatingLogBubblePositionOrNull(hostConfigSnapshot: HostConfigSnapshot): String {
        val position = hostConfigSnapshot.floatingLogBubblePosition ?: return "null"
        return buildString {
            append('{')
            append("\"horizontalFraction\": ")
            append(position.horizontalFraction)
            append(", ")
            append("\"verticalFraction\": ")
            append(position.verticalFraction)
            append('}')
        }
    }

    /**
     * 将悬浮浏览器位置写入诊断 bundle；只记录归一化坐标，不记录页面或消息内容。
     */
    private fun jsonFloatingBrowserPositionOrNull(hostConfigSnapshot: HostConfigSnapshot): String {
        val position = hostConfigSnapshot.floatingBrowserPosition ?: return "null"
        return buildString {
            append('{')
            append("\"horizontalFraction\": ")
            append(position.horizontalFraction)
            append(", ")
            append("\"verticalFraction\": ")
            append(position.verticalFraction)
            append('}')
        }
    }

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
            append('"')
        }
    }
}
