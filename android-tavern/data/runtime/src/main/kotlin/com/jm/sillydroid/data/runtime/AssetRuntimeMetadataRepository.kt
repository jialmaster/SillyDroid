package com.jm.sillydroid.data.runtime

import android.content.Context
import com.jm.sillydroid.domain.bootstrap.RuntimeMetadataRepository
import org.json.JSONObject

class AssetRuntimeMetadataRepository(context: Context) : RuntimeMetadataRepository {
    private val appContext = context.applicationContext

    fun readRootfsManifestRawJson(): String? {
        return readNormalizedJsonAssetOrNull(rootfsManifestAssetPath)
    }

    fun readServerManifestRawJson(): String? {
        return readNormalizedJsonAssetOrNull(serverManifestAssetPath)
    }

    override fun resolveRuntimeVersionLabel(): String? {
        val manifest = readJsonAssetOrNull(rootfsManifestAssetPath) ?: return null

        val directVersion = manifest.optMeaningfulString("runtimeVersion")
        if (directVersion.isNotBlank()) {
            return directVersion
        }

        val baseFlavor = manifest.optMeaningfulString("baseFlavor").ifBlank {
            if (manifest.has("ubuntuBaseVersion") || manifest.has("ubuntuBaseUrl")) {
                "ubuntu"
            } else {
                ""
            }
        }
        val baseVersion = manifest.optMeaningfulString("baseVersion").ifBlank {
            manifest.optMeaningfulString("ubuntuBaseVersion")
        }.ifBlank {
            extractFirstGroup(
                source = manifest.optMeaningfulString("baseSourceUrl").ifBlank {
                    manifest.optMeaningfulString("ubuntuBaseUrl")
                },
                pattern = """ubuntu-base-([0-9][0-9.]+)-base-arm64\.tar\.gz"""
            )
        }
        val prootVersion = manifest.optMeaningfulString("prootVersion").ifBlank {
            extractFirstGroup(
                source = manifest.optMeaningfulString("prootPackageUrl"),
                pattern = """proot_([^_]+)_aarch64\.deb"""
            )
        }

        val baseLabel = when {
            baseVersion.isBlank() -> ""
            baseFlavor.isBlank() || baseFlavor.equals("ubuntu", ignoreCase = true) -> baseVersion
            else -> "$baseFlavor.$baseVersion"
        }

        return when {
            baseLabel.isNotBlank() && prootVersion.isNotBlank() -> "$baseLabel+proot.$prootVersion"
            baseLabel.isNotBlank() -> baseLabel
            prootVersion.isNotBlank() -> "proot.$prootVersion"
            else -> null
        }
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

    private fun extractFirstGroup(source: String, pattern: String): String {
        return Regex(pattern).find(source)?.groupValues?.getOrNull(1).orEmpty().trim()
    }

    private fun JSONObject.optMeaningfulString(key: String): String {
        return optString(key)
            .trim()
            .takeUnless { value -> value.isBlank() || value.equals("null", ignoreCase = true) }
            .orEmpty()
    }

    private companion object {
        private const val rootfsManifestAssetPath = "bootstrap/rootfs/rootfs-manifest.json"
        private const val serverManifestAssetPath = "bootstrap/server/bootstrap-manifest.json"
    }
}
