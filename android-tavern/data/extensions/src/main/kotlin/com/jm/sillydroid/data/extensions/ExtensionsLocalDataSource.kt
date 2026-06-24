package com.jm.sillydroid.data.extensions

import com.jm.sillydroid.core.model.extensions.BrokenExtensionDirectory
import com.jm.sillydroid.core.model.extensions.BundledExtension
import com.jm.sillydroid.core.model.extensions.BundledExtensionInstallResult
import com.jm.sillydroid.core.model.extensions.DefaultExtensionRepository
import com.jm.sillydroid.core.model.extensions.ExtensionInventory
import com.jm.sillydroid.core.model.extensions.ExtensionKind
import com.jm.sillydroid.core.model.extensions.ManagedPlugin
import com.jm.sillydroid.core.model.extensions.ManagedExtension
import com.jm.sillydroid.domain.extensions.ExtensionDirectories
import com.jm.sillydroid.domain.extensions.ExtensionDirectoriesProvider
import java.io.File
import org.json.JSONObject

class ExtensionsLocalDataSource(
    private val directoriesProvider: ExtensionDirectoriesProvider
) {
    fun loadInventory(): ExtensionInventory {
        val directories = directoriesProvider.directories()
        val installedExtensions = loadInstalledExtensions(directories)
        val installedPlugins = loadInstalledPlugins(directories)
        val bundledExtensions = loadBundledExtensions(directories)
        return ExtensionInventory(
            installedExtensions = installedExtensions,
            installedPlugins = installedPlugins,
            bundledExtensions = bundledExtensions
        )
    }

    fun defaultRepositories(): List<DefaultExtensionRepository> {
        val configFile = directoriesProvider.directories().defaultExtensionsConfigFile
        if (!configFile.isFile) {
            return emptyList()
        }

        return runCatching {
            val root = JSONObject(configFile.readText())
            val repositories = root.optJSONArray("defaultExtensionRepositories")
                ?: root.optJSONArray("repositories")
                ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until repositories.length()) {
                    val repository = repositories.optJSONObject(index) ?: continue
                    val displayName = repository.optString("displayName").trim()
                    val repositoryUrl = repository.optString("repositoryUrl").trim()
                    if (displayName.isBlank() || repositoryUrl.isBlank()) {
                        continue
                    }

                    add(
                        DefaultExtensionRepository(
                            displayName = displayName,
                            repositoryUrl = repositoryUrl,
                            description = repository.optString("description").trim().ifBlank { null }
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    fun repositoryCount(): Int = defaultRepositories().size

    fun extensionTargetExists(folderName: String): Boolean {
        val directories = directoriesProvider.directories()
        return extensionRoot(directories, ExtensionKind.GLOBAL).resolve(folderName).exists() ||
            extensionRoot(directories, ExtensionKind.USER).resolve(folderName).exists()
    }

    fun deleteExtension(extension: ManagedExtension) {
        val targetDir = extensionRoot(directoriesProvider.directories(), extension.kind).resolve(extension.folderName)
        if (!targetDir.deleteRecursively() && targetDir.exists()) {
            throw IllegalStateException("删除扩展失败：${extension.folderName}")
        }
    }

    fun deleteExtensions(extensions: List<ManagedExtension>): Pair<List<String>, List<String>> {
        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val directories = directoriesProvider.directories()
        extensions.forEach { extension ->
            val targetDir = extensionRoot(directories, extension.kind).resolve(extension.folderName)
            if (!targetDir.exists() || targetDir.deleteRecursively()) {
                removed += extension.displayName
            } else {
                failed += extension.displayName
            }
        }
        return removed to failed
    }

    fun deletePlugin(plugin: ManagedPlugin) {
        val targetDir = directoriesProvider.directories().serverPluginsDir.resolve(plugin.folderName)
        if (!targetDir.deleteRecursively() && targetDir.exists()) {
            throw IllegalStateException("删除后端插件失败：${plugin.folderName}")
        }
    }

    fun installBundledExtension(extension: BundledExtension) {
        val directories = directoriesProvider.directories()
        val extensionsRoot = extensionRoot(directories, ExtensionKind.GLOBAL)
        val sourceDirectory = bundledSourceDirectory(directories, extension)
        val targetDirectory = extensionsRoot.resolve(extension.folderName)
        extensionsRoot.mkdirs()
        if (targetDirectory.exists()) {
            targetDirectory.deleteRecursively()
        }
        if (!sourceDirectory.copyRecursively(targetDirectory, overwrite = true)) {
            throw IllegalStateException("复制内置扩展失败：${extension.folderName}")
        }
    }

    fun reinstallBundledExtension(
        extension: ManagedExtension,
        bundledSource: BundledExtension
    ): BundledExtensionInstallResult {
        val directories = directoriesProvider.directories()
        val extensionsRoot = extensionRoot(directories, extension.kind)
        val sourceDirectory = bundledSourceDirectory(directories, bundledSource)
        val targetDirectory = extensionsRoot.resolve(bundledSource.folderName)
        extensionsRoot.mkdirs()
        if (extension.folderName != bundledSource.folderName) {
            val legacyDirectory = extensionsRoot.resolve(extension.folderName)
            if (legacyDirectory.exists()) {
                legacyDirectory.deleteRecursively()
            }
        }
        if (targetDirectory.exists()) {
            targetDirectory.deleteRecursively()
        }
        if (!sourceDirectory.copyRecursively(targetDirectory, overwrite = true)) {
            throw IllegalStateException("重装内置扩展失败：${bundledSource.folderName}")
        }
        return BundledExtensionInstallResult(
            migratedFromFolderName = extension.folderName.takeIf { it != bundledSource.folderName }
        )
    }

    fun findBrokenExtensionDirectories(): List<BrokenExtensionDirectory> {
        val directories = directoriesProvider.directories()
        return ExtensionKind.values()
            .flatMap { kind ->
                val extensionsRoot = extensionRoot(directories, kind)
                if (!extensionsRoot.isDirectory) {
                    emptyList()
                } else {
                    extensionsRoot.listFiles()
                        .orEmpty()
                        .filter { directory -> directory.isDirectory && !File(directory, "manifest.json").isFile }
                        .map { directory ->
                            BrokenExtensionDirectory(
                                folderName = directory.name,
                                kind = kind
                            )
                        }
                }
            }
            .sortedBy { directory -> directory.folderName.lowercase() }
    }

    fun cleanupBrokenExtensions(directories: List<BrokenExtensionDirectory>): Pair<List<String>, List<String>> {
        val roots = directoriesProvider.directories()
        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        directories.forEach { directory ->
            val targetDir = extensionRoot(roots, directory.kind).resolve(directory.folderName)
            if (!targetDir.exists() || targetDir.deleteRecursively()) {
                removed += directory.folderName
            } else {
                failed += directory.folderName
            }
        }
        return removed to failed
    }

    private fun loadInstalledExtensions(directories: ExtensionDirectories): List<ManagedExtension> {
        val globalExtensions = readExtensionsFromDirectory(
            root = extensionRoot(directories, ExtensionKind.GLOBAL),
            kind = ExtensionKind.GLOBAL
        )
        val userExtensions = readExtensionsFromDirectory(
            root = extensionRoot(directories, ExtensionKind.USER),
            kind = ExtensionKind.USER
        )
        return (globalExtensions + userExtensions)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { extension -> extension.displayName })
    }

    private fun loadInstalledPlugins(directories: ExtensionDirectories): List<ManagedPlugin> {
        val pluginsRoot = directories.serverPluginsDir
        if (!pluginsRoot.exists()) {
            return emptyList()
        }

        return pluginsRoot.listFiles()
            .orEmpty()
            .filter { directory -> directory.isDirectory }
            .map { directory ->
                val packageFile = File(directory, "package.json")
                if (!packageFile.exists()) {
                    return@map ManagedPlugin(
                        folderName = directory.name,
                        displayName = directory.name,
                        version = null,
                        description = null,
                        repositoryUrl = null,
                        packageHealthy = false,
                        packageMessage = "package.json 缺失。"
                    )
                }

                runCatching {
                    val packageJson = JSONObject(packageFile.readText())
                    ManagedPlugin(
                        folderName = directory.name,
                        displayName = packageJson.optString("name").ifBlank { directory.name },
                        version = packageJson.optString("version").ifBlank { null },
                        description = packageJson.optString("description").ifBlank { null },
                        repositoryUrl = resolvePackageRepositoryUrl(packageJson) ?: readGitOriginUrl(directory),
                        packageHealthy = true,
                        packageMessage = null
                    )
                }.getOrElse {
                    ManagedPlugin(
                        folderName = directory.name,
                        displayName = directory.name,
                        version = null,
                        description = null,
                        repositoryUrl = null,
                        packageHealthy = false,
                        packageMessage = "package.json 格式无效。"
                    )
                }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { plugin -> plugin.displayName })
    }

    private fun resolvePackageRepositoryUrl(packageJson: JSONObject): String? {
        val repository = packageJson.opt("repository") ?: return null
        return when (repository) {
            is String -> repository.trim().ifBlank { null }
            is JSONObject -> repository.optString("url").trim().ifBlank { null }
            else -> null
        }?.removePrefix("git+")
    }

    private fun readGitOriginUrl(directory: File): String? {
        val configFile = File(File(directory, ".git"), "config")
        if (!configFile.isFile) {
            return null
        }

        var inOriginBlock = false
        return configFile.readLines().firstNotNullOfOrNull { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[") -> {
                    inOriginBlock = line.equals("[remote \"origin\"]", ignoreCase = true)
                    null
                }

                inOriginBlock && line.startsWith("url", ignoreCase = true) -> {
                    line.substringAfter("=", missingDelimiterValue = "")
                        .trim()
                        .removePrefix("git+")
                        .ifBlank { null }
                }

                else -> null
            }
        }
    }

    private fun readExtensionsFromDirectory(root: File, kind: ExtensionKind): List<ManagedExtension> {
        if (!root.exists()) {
            return emptyList()
        }

        return root.listFiles()
            .orEmpty()
            .filter { directory -> directory.isDirectory }
            .map { directory ->
                val manifestFile = File(directory, "manifest.json")
                if (!manifestFile.exists()) {
                    return@map ManagedExtension(
                        folderName = directory.name,
                        displayName = directory.name,
                        version = null,
                        author = null,
                        homePage = null,
                        manifestHealthy = false,
                        manifestMessage = null,
                        kind = kind
                    )
                }

                runCatching {
                    val manifest = JSONObject(manifestFile.readText())
                    ManagedExtension(
                        folderName = directory.name,
                        displayName = manifest.optString("display_name").ifBlank { directory.name },
                        version = manifest.optString("version").ifBlank { null },
                        author = manifest.optString("author").ifBlank { null },
                        homePage = manifest.optString("homePage").ifBlank { null } ?: readGitOriginUrl(directory),
                        manifestHealthy = true,
                        manifestMessage = null,
                        kind = kind
                    )
                }.getOrElse {
                    ManagedExtension(
                        folderName = directory.name,
                        displayName = directory.name,
                        version = null,
                        author = null,
                        homePage = null,
                        manifestHealthy = false,
                        manifestMessage = null,
                        kind = kind
                    )
                }
            }
    }

    private fun loadBundledExtensions(directories: ExtensionDirectories): List<BundledExtension> {
        val bundledRoot = directories.bundledExtensionsDir
        if (!bundledRoot.isDirectory) {
            return emptyList()
        }

        return bundledRoot.listFiles()
            .orEmpty()
            .filter { directory -> directory.isDirectory }
            .mapNotNull { directory ->
                val manifestFile = File(directory, "manifest.json")
                if (!manifestFile.isFile) {
                    return@mapNotNull null
                }

                runCatching {
                    val manifest = JSONObject(manifestFile.readText())
                    val targetDirectory = directories.globalExtensionsDir.resolve(directory.name)
                    BundledExtension(
                        folderName = directory.name,
                        displayName = manifest.optString("display_name").ifBlank { directory.name },
                        version = manifest.optString("version").ifBlank { null },
                        author = manifest.optString("author").ifBlank { null },
                        category = manifest.optString("sillydroid_bundle_category").ifBlank { "default" },
                        targetExists = targetDirectory.isDirectory && File(targetDirectory, "manifest.json").isFile
                    )
                }.getOrNull()
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { extension -> extension.displayName })
    }

    private fun extensionRoot(directories: ExtensionDirectories, kind: ExtensionKind): File {
        return when (kind) {
            ExtensionKind.GLOBAL -> directories.globalExtensionsDir
            ExtensionKind.USER -> directories.userExtensionsDir
        }
    }

    private fun bundledSourceDirectory(directories: ExtensionDirectories, extension: BundledExtension): File {
        val sourceDirectory = directories.bundledExtensionsDir.resolve(extension.folderName)
        if (!sourceDirectory.isDirectory) {
            throw IllegalStateException("内置扩展目录不存在：${extension.folderName}")
        }
        return sourceDirectory
    }
}
