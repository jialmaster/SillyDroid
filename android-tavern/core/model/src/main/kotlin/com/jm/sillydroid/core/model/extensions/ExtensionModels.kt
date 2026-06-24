package com.jm.sillydroid.core.model.extensions

enum class ExtensionKind {
    GLOBAL,
    USER
}

enum class ExtensionInstallMode {
    OVERWRITE,
    SKIP
}

data class ManagedExtension(
    val folderName: String,
    val displayName: String,
    val version: String?,
    val author: String?,
    val homePage: String?,
    val manifestHealthy: Boolean,
    val manifestMessage: String?,
    val kind: ExtensionKind = ExtensionKind.GLOBAL
)

data class ManagedPlugin(
    val folderName: String,
    val displayName: String,
    val version: String?,
    val description: String?,
    val repositoryUrl: String?,
    val packageHealthy: Boolean,
    val packageMessage: String?
)

data class BundledExtension(
    val folderName: String,
    val displayName: String,
    val version: String?,
    val author: String?,
    val category: String,
    val targetExists: Boolean
)

data class DefaultExtensionRepository(
    val displayName: String,
    val repositoryUrl: String,
    val description: String?
)

data class BrokenExtensionDirectory(
    val folderName: String,
    val kind: ExtensionKind
)

data class ExtensionInventory(
    val installedExtensions: List<ManagedExtension>,
    val installedPlugins: List<ManagedPlugin>,
    val bundledExtensions: List<BundledExtension>
)

data class ExtensionInstallPreview(
    val repositoryUrl: String,
    val normalizedRepository: NormalizedExtensionRepository,
    val folderName: String,
    val displayName: String,
    val version: String?,
    val author: String?,
    val homePage: String?,
    val targetExists: Boolean
)

data class PluginInstallPreview(
    val repositoryUrl: String,
    val normalizedRepository: NormalizedExtensionRepository,
    val folderName: String,
    val displayName: String,
    val version: String?,
    val description: String?,
    val targetExists: Boolean
)

data class ManagedRepositoryUpdate(
    val hasNewVersion: Boolean,
    val localRevision: String?,
    val remoteRevision: String?,
    val localVersion: String? = null,
    val remoteVersion: String? = null,
    val versionSourceFile: String? = null
)

data class BatchExtensionFailure(
    val input: String,
    val message: String,
    val logPath: String?
)

data class BatchExtensionPreview(
    val previews: List<ExtensionInstallPreview>,
    val failures: List<BatchExtensionFailure>,
    val skipped: List<ExtensionInstallPreview> = emptyList()
)

data class BatchExtensionInstallResult(
    val successes: List<ExtensionInstallPreview>,
    val failures: List<BatchExtensionFailure>,
    val skipped: List<ExtensionInstallPreview> = emptyList()
)

data class NormalizedExtensionRepository(
    val cloneUrl: String,
    val branch: String?
)

data class ExtensionRuntimeProgress(
    val step: String?,
    val phase: String?,
    val loaded: Int?,
    val total: Int?,
    val indeterminate: Boolean,
    val message: String?
)

data class BundledExtensionInstallResult(
    val migratedFromFolderName: String?
)
