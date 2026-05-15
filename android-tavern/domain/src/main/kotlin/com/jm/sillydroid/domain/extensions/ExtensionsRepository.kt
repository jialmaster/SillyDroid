package com.jm.sillydroid.domain.extensions

import com.jm.sillydroid.core.model.extensions.BatchExtensionInstallResult
import com.jm.sillydroid.core.model.extensions.BatchExtensionPreview
import com.jm.sillydroid.core.model.extensions.BrokenExtensionDirectory
import com.jm.sillydroid.core.model.extensions.BundledExtension
import com.jm.sillydroid.core.model.extensions.BundledExtensionInstallResult
import com.jm.sillydroid.core.model.extensions.DefaultExtensionRepository
import com.jm.sillydroid.core.model.extensions.ExtensionInventory
import com.jm.sillydroid.core.model.extensions.ExtensionInstallMode
import com.jm.sillydroid.core.model.extensions.ExtensionInstallPreview
import com.jm.sillydroid.core.model.extensions.ExtensionKind
import com.jm.sillydroid.core.model.extensions.ExtensionRuntimeProgress
import com.jm.sillydroid.core.model.extensions.ManagedExtension
import com.jm.sillydroid.core.model.extensions.NormalizedExtensionRepository

interface ExtensionsRepository {
    fun loadInventory(): ExtensionInventory
    fun bundledExtensions(): List<BundledExtension>
    fun defaultRepositories(): List<DefaultExtensionRepository>
    fun repositoryCount(): Int
    fun extensionTargetExists(folderName: String): Boolean
    fun deleteExtension(extension: ManagedExtension)
    fun deleteExtensions(extensions: List<ManagedExtension>): Pair<List<String>, List<String>>
    fun installBundledExtension(extension: BundledExtension)
    fun reinstallBundledExtension(extension: ManagedExtension, bundledSource: BundledExtension): BundledExtensionInstallResult
    fun findBrokenExtensionDirectories(): List<BrokenExtensionDirectory>
    fun cleanupBrokenExtensions(directories: List<BrokenExtensionDirectory>): Pair<List<String>, List<String>>
    fun isSupportedRepositoryUrl(repositoryUrl: String): Boolean
    fun normalizeRepositoryUrl(repositoryUrl: String): NormalizedExtensionRepository?
    fun normalizedRepositoryKey(repository: NormalizedExtensionRepository): String
    fun repositoryDisplayLabel(repositoryUrl: String, normalizedRepository: NormalizedExtensionRepository? = normalizeRepositoryUrl(repositoryUrl)): String
    fun githubReachabilityFailures(normalizedRepositories: List<NormalizedExtensionRepository>): List<String>
    fun requiresGithubReachabilityCheck(repository: NormalizedExtensionRepository): Boolean
    fun validateRemoteManifestBeforeClone(repository: NormalizedExtensionRepository)
    fun buildInstallPreview(repositoryUrl: String, normalizedRepository: NormalizedExtensionRepository): ExtensionInstallPreview
    fun install(
        preview: ExtensionInstallPreview,
        kind: ExtensionKind = ExtensionKind.GLOBAL,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    )
    fun reinstall(
        folderName: String,
        repository: NormalizedExtensionRepository,
        kind: ExtensionKind = ExtensionKind.GLOBAL,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    )
}
