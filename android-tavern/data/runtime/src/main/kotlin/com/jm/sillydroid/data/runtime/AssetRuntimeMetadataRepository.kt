package com.jm.sillydroid.data.runtime

import android.content.Context
import com.jm.sillydroid.domain.bootstrap.RuntimePatchMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchModuleMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOptionSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchTargetFileSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimeMetadataRepository
import org.json.JSONArray
import org.json.JSONObject

class AssetRuntimeMetadataRepository(context: Context) : RuntimeMetadataRepository {
    private val appContext = context.applicationContext

    fun readRootfsManifestRawJson(): String? {
        return readNormalizedJsonAssetOrNull(rootfsManifestAssetPath)
    }

    fun readServerManifestRawJson(): String? {
        return readNormalizedJsonAssetOrNull(serverManifestAssetPath)
    }

    override fun resolveRuntimePatchMetadataSnapshot(): RuntimePatchMetadataSnapshot? {
        val manifest = readJsonAssetOrNull(runtimePatchManifestAssetPath) ?: return null
        val modules = manifest.optJSONArray("modules")
            ?.toJsonObjects()
            .orEmpty()
            .map { moduleEntry -> readRuntimePatchModuleSnapshot(moduleEntry) }

        return RuntimePatchMetadataSnapshot(
            schemaVersion = manifest.optIntOrNull("schemaVersion"),
            frameworkId = manifest.optMeaningfulString("frameworkId"),
            frameworkVersion = manifest.optMeaningfulString("frameworkVersion"),
            compatibleTavernVersions = manifest.optStringList("compatibleTavernVersions"),
            defaultPreset = manifest.optMeaningfulString("defaultPreset"),
            manifestRawJson = manifest.toString(),
            modules = modules
        )
    }

    override fun resolveRuntimeVersionLabel(): String? {
        val manifest = readJsonAssetOrNull(rootfsManifestAssetPath) ?: return null
        return resolveRootfsRuntimeVersionLabel(
            RootfsRuntimeVersionMetadata(
                runtimeVersion = manifest.optMeaningfulString("runtimeVersion"),
                runtimeMode = manifest.optMeaningfulString("runtimeMode"),
                baseFlavor = manifest.optMeaningfulString("baseFlavor"),
                baseVersion = manifest.optMeaningfulString("baseVersion"),
                usrArchiveSha256 = manifest.optJSONObject("archiveSha256")
                    ?.optMeaningfulString("usr")
                    .orEmpty()
            )
        )
    }

    override fun resolveServerPayloadVersionLabel(
        upstreamVersion: String,
        currentVersionName: String
    ): String? {
        val manifest = readJsonAssetOrNull(serverManifestAssetPath) ?: return null

        val directVersion = manifest.optMeaningfulString("payloadVersion")
        if (directVersion.isNotBlank()) {
            return directVersion
        }

        val tag = manifest.optMeaningfulString("tag")
        val nodeVersion = manifest.optMeaningfulString("nodeVersion")
        val resolvedUpstreamVersion = upstreamVersion.trim().ifBlank {
            extractFirstGroup(
                source = currentVersionName,
                pattern = """\+tavern\.([0-9A-Za-z._-]+)"""
            )
        }
        return when {
            tag.isNotBlank() && nodeVersion.isNotBlank() -> "$tag+node.$nodeVersion"
            tag.isNotBlank() -> tag
            resolvedUpstreamVersion.isNotBlank() -> resolvedUpstreamVersion
            nodeVersion.isNotBlank() -> "node.$nodeVersion"
            else -> null
        }
    }

    private fun readJsonAssetOrNull(assetPath: String): JSONObject? {
        return runCatching {
            appContext.assets.open(assetPath).bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
        }.getOrNull()
    }

    private fun readNormalizedJsonAssetOrNull(assetPath: String): String? {
        return readJsonAssetOrNull(assetPath)?.toString()
    }

    private fun readRuntimePatchModuleSnapshot(moduleEntry: JSONObject): RuntimePatchModuleMetadataSnapshot {
        val entryId = moduleEntry.optMeaningfulString("id")
        val modulePath = moduleEntry.optMeaningfulString("path")
        val manifestName = moduleEntry.optMeaningfulString("manifest").ifBlank { "manifest.json" }
        val moduleManifestPath = listOf(runtimePatchAssetRoot, modulePath, manifestName)
            .filter { part -> part.isNotBlank() }
            .joinToString("/")

        val moduleManifest = readJsonAssetOrNull(moduleManifestPath)
        return RuntimePatchModuleMetadataSnapshot(
            id = moduleManifest?.optMeaningfulString("id").orEmpty().ifBlank { entryId },
            title = moduleManifest?.optMeaningfulString("title").orEmpty(),
            version = moduleManifest?.optMeaningfulString("version").orEmpty(),
            description = moduleManifest?.optMeaningfulString("description").orEmpty(),
            supportedTavernVersions = moduleManifest?.optStringList("supportedTavernVersions").orEmpty(),
            defaultPresets = moduleManifest?.optStringList("defaultPresets").orEmpty(),
            targetFiles = moduleManifest?.optJSONArray("targetFiles")
                ?.toJsonObjects()
                .orEmpty()
                .mapNotNull { targetFile -> targetFile.toRuntimePatchTargetFileSnapshotOrNull() },
            settings = moduleManifest?.optJSONArray("settings")
                ?.toJsonObjects()
                .orEmpty()
                .mapNotNull { setting -> setting.toRuntimePatchSettingMetadataSnapshotOrNull() },
            manifestIncluded = moduleManifest != null
        )
    }

    private fun JSONObject.toRuntimePatchTargetFileSnapshotOrNull(): RuntimePatchTargetFileSnapshot? {
        val path = optMeaningfulString("path")
        val sha256 = optMeaningfulString("sha256")
        return if (path.isBlank() && sha256.isBlank()) {
            null
        } else {
            RuntimePatchTargetFileSnapshot(path = path, sha256 = sha256)
        }
    }

    private fun JSONObject.toRuntimePatchSettingMetadataSnapshotOrNull(): RuntimePatchSettingMetadataSnapshot? {
        val key = optMeaningfulString("key")
        if (key.isBlank()) {
            return null
        }
        return RuntimePatchSettingMetadataSnapshot(
            key = key,
            type = optMeaningfulString("type").ifBlank { "string" },
            title = optMeaningfulString("title").ifBlank { key },
            description = optMeaningfulString("description"),
            defaultValue = optRuntimePatchSettingValueString("defaultValue"),
            unit = optMeaningfulString("unit"),
            version = optMeaningfulString("version"),
            restartRequired = optBoolean("restartRequired", true),
            important = optBoolean("important", false),
            min = optFiniteDoubleOrNull("min"),
            max = optFiniteDoubleOrNull("max"),
            step = optFiniteDoubleOrNull("step"),
            options = optJSONArray("options")
                ?.toJsonObjects()
                .orEmpty()
                .mapNotNull { option -> option.toRuntimePatchSettingOptionSnapshotOrNull() }
        )
    }

    private fun JSONObject.toRuntimePatchSettingOptionSnapshotOrNull(): RuntimePatchSettingOptionSnapshot? {
        val value = optRuntimePatchSettingValueString("value")
        if (value.isBlank()) {
            return null
        }
        return RuntimePatchSettingOptionSnapshot(
            value = value,
            label = optMeaningfulString("label").ifBlank { value },
            description = optMeaningfulString("description")
        )
    }

    private fun JSONObject.optRuntimePatchSettingValueString(key: String): String {
        if (!has(key) || isNull(key)) {
            return ""
        }
        val value = opt(key)
        return when (value) {
            is Boolean -> value.toString()
            is Number -> value.toString()
            else -> value?.toString().orEmpty()
        }.trim()
    }

    private fun JSONObject.optFiniteDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) {
            return null
        }
        val value = optDouble(key, Double.NaN)
        return value.takeIf { candidate -> candidate.isFinite() }
    }

    private fun extractFirstGroup(source: String, pattern: String): String {
        return Regex(pattern).find(source)?.groupValues?.getOrNull(1).orEmpty().trim()
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (has(key) && !isNull(key)) {
            optInt(key)
        } else {
            null
        }
    }

    private fun JSONObject.optMeaningfulString(key: String): String {
        return optString(key)
            .trim()
            .takeUnless { value -> value.isBlank() || value.equals("null", ignoreCase = true) }
            .orEmpty()
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        return optJSONArray(key)
            ?.toStringList()
            .orEmpty()
    }

    private fun JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }
    }

    private fun JSONArray.toJsonObjects(): List<JSONObject> {
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
    }

    private companion object {
        private const val rootfsManifestAssetPath = "bootstrap/rootfs/rootfs-manifest.json"
        private const val serverManifestAssetPath = "bootstrap/server/bootstrap-manifest.json"
        private const val runtimePatchAssetRoot = "bootstrap/runtime-patches"
        private const val runtimePatchManifestAssetPath = "$runtimePatchAssetRoot/manifest.json"
    }
}

internal data class RootfsRuntimeVersionMetadata(
    val runtimeVersion: String,
    val runtimeMode: String,
    val baseFlavor: String,
    val baseVersion: String,
    val usrArchiveSha256: String
)

internal fun resolveRootfsRuntimeVersionLabel(metadata: RootfsRuntimeVersionMetadata): String? {
    // rootfs 的 runtimeVersion 是布局版本，可能长期保持 1.0.0；关于页需要展示可随资产内容变化的标签。
    // 因此优先组合 runtimeMode/baseVersion 与 rootfs-usr 归档指纹，避免 runtime 更新后仍显示固定版本。
    val runtimeMode = metadata.runtimeMode.trim()
    val baseFlavor = metadata.baseFlavor.trim()
    val baseVersion = metadata.baseVersion.trim()
    val usrArchiveSha = metadata.usrArchiveSha256.trim()
        .take(8)

    val runtimeLabel = when {
        runtimeMode.isNotBlank() -> runtimeMode
        baseFlavor.isBlank() -> baseVersion
        else -> "$baseFlavor.$baseVersion"
    }
    val baseLabel = when {
        runtimeMode.isNotBlank() && baseVersion.isNotBlank() -> baseVersion
        runtimeMode.isNotBlank() && baseFlavor.isNotBlank() -> baseFlavor
        else -> ""
    }
    val archiveLabel = usrArchiveSha.takeIf { value -> value.isNotBlank() }?.let { value -> "usr $value" }.orEmpty()
    val contentLabel = listOf(runtimeLabel, baseLabel, archiveLabel)
        .filter { value -> value.isNotBlank() }
        .distinct()
        .joinToString(separator = " · ")
    if (contentLabel.isNotBlank()) {
        return contentLabel
    }

    val directVersion = metadata.runtimeVersion.trim()
    if (directVersion.isNotBlank()) {
        return directVersion
    }

    return when {
        baseVersion.isNotBlank() && baseFlavor.isNotBlank() -> "$baseFlavor.$baseVersion"
        baseVersion.isNotBlank() -> baseVersion
        else -> null
    }
}
