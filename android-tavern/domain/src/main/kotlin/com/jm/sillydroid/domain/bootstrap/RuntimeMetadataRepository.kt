package com.jm.sillydroid.domain.bootstrap

data class RuntimePatchMetadataSnapshot(
    val schemaVersion: Int?,
    val frameworkId: String,
    val frameworkVersion: String,
    val compatibleTavernVersions: List<String>,
    val defaultPreset: String,
    val manifestRawJson: String,
    val modules: List<RuntimePatchModuleMetadataSnapshot>
)

data class RuntimePatchModuleMetadataSnapshot(
    val id: String,
    val title: String,
    val version: String,
    val description: String,
    val supportedTavernVersions: List<String>,
    val defaultPresets: List<String>,
    val targetFiles: List<RuntimePatchTargetFileSnapshot>,
    val settings: List<RuntimePatchSettingMetadataSnapshot>,
    val manifestIncluded: Boolean
)

data class RuntimePatchTargetFileSnapshot(
    val path: String,
    val sha256: String
)

data class RuntimePatchSettingMetadataSnapshot(
    val key: String,
    val type: String,
    val title: String,
    val description: String,
    val defaultValue: String,
    val unit: String,
    val version: String,
    val restartRequired: Boolean,
    val important: Boolean,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val options: List<RuntimePatchSettingOptionSnapshot>
)

data class RuntimePatchSettingOptionSnapshot(
    val value: String,
    val label: String,
    val description: String
)

interface RuntimeMetadataRepository {
    fun resolveRuntimeVersionLabel(): String?
    fun resolveServerPayloadVersionLabel(
        upstreamVersion: String,
        currentVersionName: String
    ): String?
    fun resolveRuntimePatchMetadataSnapshot(): RuntimePatchMetadataSnapshot?
}
