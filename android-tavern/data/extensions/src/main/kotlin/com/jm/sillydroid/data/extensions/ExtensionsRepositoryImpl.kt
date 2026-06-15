package com.jm.sillydroid.data.extensions

import com.jm.sillydroid.core.model.extensions.BrokenExtensionDirectory
import com.jm.sillydroid.core.model.extensions.BundledExtension
import com.jm.sillydroid.core.model.extensions.BundledExtensionInstallResult
import com.jm.sillydroid.core.model.extensions.DefaultExtensionRepository
import com.jm.sillydroid.core.model.extensions.ExtensionInstallPreview
import com.jm.sillydroid.core.model.extensions.ExtensionInventory
import com.jm.sillydroid.core.model.extensions.ExtensionKind
import com.jm.sillydroid.core.model.extensions.ExtensionRuntimeProgress
import com.jm.sillydroid.core.model.extensions.ManagedExtension
import com.jm.sillydroid.core.model.extensions.NormalizedExtensionRepository
import com.jm.sillydroid.domain.extensions.ExtensionsRepository

class ExtensionsRepositoryImpl(
    private val localDataSource: ExtensionsLocalDataSource,
    private val remoteManifestDataSource: RemoteManifestDataSource,
    private val extensionCommandExecutor: ExtensionCommandExecutor
) : ExtensionsRepository {
    override fun loadInventory(): ExtensionInventory {
        return localDataSource.loadInventory()
    }

    override fun bundledExtensions(): List<BundledExtension> {
        return localDataSource.loadInventory().bundledExtensions
    }

    override fun defaultRepositories(): List<DefaultExtensionRepository> {
        return localDataSource.defaultRepositories()
    }

    override fun repositoryCount(): Int {
        return localDataSource.repositoryCount()
    }

    override fun extensionTargetExists(folderName: String): Boolean {
        return localDataSource.extensionTargetExists(folderName)
    }

    override fun deleteExtension(extension: ManagedExtension) {
        localDataSource.deleteExtension(extension)
    }

    override fun deleteExtensions(extensions: List<ManagedExtension>): Pair<List<String>, List<String>> {
        return localDataSource.deleteExtensions(extensions)
    }

    override fun installBundledExtension(extension: BundledExtension) {
        localDataSource.installBundledExtension(extension)
    }

    override fun reinstallBundledExtension(
        extension: ManagedExtension,
        bundledSource: BundledExtension
    ): BundledExtensionInstallResult {
        return localDataSource.reinstallBundledExtension(extension, bundledSource)
    }

    override fun findBrokenExtensionDirectories(): List<BrokenExtensionDirectory> {
        return localDataSource.findBrokenExtensionDirectories()
    }

    override fun cleanupBrokenExtensions(
        directories: List<BrokenExtensionDirectory>
    ): Pair<List<String>, List<String>> {
        return localDataSource.cleanupBrokenExtensions(directories)
    }

    override fun isSupportedRepositoryUrl(repositoryUrl: String): Boolean {
        return remoteManifestDataSource.isSupportedRepositoryUrl(repositoryUrl)
    }

    override fun normalizeRepositoryUrl(repositoryUrl: String): NormalizedExtensionRepository? {
        return remoteManifestDataSource.normalizeRepositoryUrl(repositoryUrl)
    }

    override fun normalizedRepositoryKey(repository: NormalizedExtensionRepository): String {
        val branchKey = repository.branch?.trim().orEmpty()
        return "${repository.cloneUrl.trim().lowercase()}#$branchKey"
    }

    override fun repositoryDisplayLabel(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository?
    ): String {
        return remoteManifestDataSource.repositoryDisplayLabel(repositoryUrl, normalizedRepository)
    }

    override fun githubReachabilityFailures(
        normalizedRepositories: List<NormalizedExtensionRepository>
    ): List<String> {
        return remoteManifestDataSource.githubReachabilityFailures(normalizedRepositories)
    }

    override fun requiresGithubReachabilityCheck(repository: NormalizedExtensionRepository): Boolean {
        return remoteManifestDataSource.requiresGithubReachabilityCheck(repository)
    }

    override fun validateRemoteManifestBeforeClone(repository: NormalizedExtensionRepository) {
        remoteManifestDataSource.fetchResolvedRemoteManifest(repository)
    }

    override fun buildInstallPreview(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository
    ): ExtensionInstallPreview {
        val resolvedManifest = remoteManifestDataSource.fetchResolvedRemoteManifest(normalizedRepository)
        val folderName = resolveExtensionFolderName(resolvedManifest.repository)
        if (folderName.isBlank()) {
            throw IllegalStateException("扩展仓库地址无法解析扩展目录名。")
        }
        val manifest = resolvedManifest.payload
        return ExtensionInstallPreview(
            repositoryUrl = repositoryUrl,
            normalizedRepository = resolvedManifest.repository,
            folderName = folderName,
            displayName = manifest.optString("display_name").trim().ifBlank { folderName },
            version = manifest.optString("version").trim().ifBlank { null },
            author = manifest.optString("author").trim().ifBlank { null },
            homePage = manifest.optString("homePage").trim().ifBlank { null },
            targetExists = extensionTargetExists(folderName)
        )
    }

    override fun install(
        preview: ExtensionInstallPreview,
        kind: ExtensionKind,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)?,
        failureMessage: (String) -> String
    ) {
        extensionCommandExecutor.install(
            folderName = preview.folderName,
            repository = preview.normalizedRepository,
            kind = kind.toExtensionTargetKind(),
            onProgress = onProgress,
            failureMessage = failureMessage
        )
    }

    override fun reinstall(
        folderName: String,
        repository: NormalizedExtensionRepository,
        kind: ExtensionKind,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)?,
        failureMessage: (String) -> String
    ) {
        extensionCommandExecutor.reinstall(
            folderName = folderName,
            repository = repository,
            kind = kind.toExtensionTargetKind(),
            onProgress = onProgress,
            failureMessage = failureMessage
        )
    }

    override fun installServerPluginDependencies(
        onProgress: ((ExtensionRuntimeProgress) -> Unit)?,
        failureMessage: (String) -> String
    ) {
        extensionCommandExecutor.installServerPluginDependencies(
            onProgress = onProgress,
            failureMessage = failureMessage
        )
    }

    private fun resolveExtensionFolderName(repository: NormalizedExtensionRepository): String {
        return extensionCommandExecutor.resolveExtensionFolderName(repository)
    }

    private fun ExtensionKind.toExtensionTargetKind(): ExtensionTargetKind {
        return when (this) {
            ExtensionKind.GLOBAL -> ExtensionTargetKind.GLOBAL
            ExtensionKind.USER -> ExtensionTargetKind.USER
        }
    }
}
