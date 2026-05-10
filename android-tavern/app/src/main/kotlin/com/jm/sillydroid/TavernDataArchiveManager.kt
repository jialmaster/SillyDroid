package com.jm.sillydroid

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal enum class TavernDataArchiveKind {
    HOST_FULL_SNAPSHOT,
    USER_BACKUP
}

internal data class TavernDataImportResult(
    val importedFileCount: Int,
    val archiveKind: TavernDataArchiveKind
)

internal data class TavernDataArchivePreview(
    val archiveKind: TavernDataArchiveKind,
    val sourceUserId: String? = null,
    val targetUserId: String? = null
)

internal class TavernDataArchiveManager(context: Context) {
    private enum class ArchiveLayout {
        MANAGED_ROOT,
        PUBLIC_EXTENSIONS_DATA_ROOT,
        UPSTREAM_USER_ROOT
    }

    private data class ArchiveImportPlan(
        val layout: ArchiveLayout,
        val sourceRoot: File
    ) {
        fun managedDirectory(directoryName: String): File = when (layout) {
            ArchiveLayout.MANAGED_ROOT -> File(sourceRoot, directoryName)
            ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> when (directoryName) {
                "extensions" -> File(sourceRoot, "public/scripts/extensions/third-party")
                else -> File(sourceRoot, directoryName)
            }

            ArchiveLayout.UPSTREAM_USER_ROOT -> File(sourceRoot, directoryName)
        }
    }

    companion object {
        private const val defaultUserHandle = "default-user"

        private val upstreamUserBackupDirectories: Set<String> = setOf(
            "assets",
            "backgrounds",
            "backups",
            "characters",
            "chats",
            "context",
            "extensions",
            "group chats",
            "groups",
            "instruct",
            "KoboldAI Settings",
            "movingUI",
            "NovelAI Settings",
            "OpenAI Settings",
            "QuickReplies",
            "reasoning",
            "sysprompt",
            "TextGen Settings",
            "themes",
            "thumbnails",
            "user",
            "User Avatars",
            "vectors",
            "worlds"
        )
    }

    private val appContext = context.applicationContext

    fun exportDataArchive(targetUri: Uri) {
        val paths = HostPaths.from(appContext)
        paths.ensureWorkingDirectories()
        val outputStream = appContext.contentResolver.openOutputStream(targetUri)
            ?: throw BootstrapException("无法写入导出目标。")

        outputStream.use { rawOutput ->
            ZipOutputStream(BufferedOutputStream(rawOutput)).use { zipOutput ->
                for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
                    val sourceDirectory = File(paths.serverDataDir, directoryName)
                    if (!sourceDirectory.exists()) {
                        zipOutput.putNextEntry(ZipEntry("$directoryName/"))
                        zipOutput.closeEntry()
                        continue
                    }

                    val children = sourceDirectory.walkTopDown().toList().sortedBy { it.absolutePath }
                    if (children.size == 1) {
                        zipOutput.putNextEntry(ZipEntry("$directoryName/"))
                        zipOutput.closeEntry()
                    }

                    for (file in children) {
                        if (file == sourceDirectory) {
                            continue
                        }

                        val relativePath = file.relativeTo(paths.serverDataDir).invariantSeparatorsPath
                        if (file.isDirectory) {
                            if (file.listFiles().isNullOrEmpty()) {
                                zipOutput.putNextEntry(ZipEntry("$relativePath/"))
                                zipOutput.closeEntry()
                            }
                            continue
                        }

                        zipOutput.putNextEntry(ZipEntry(relativePath))
                        file.inputStream().use { input ->
                            input.copyTo(zipOutput)
                        }
                        zipOutput.closeEntry()
                    }
                }
            }
        }
    }

    fun inspectDataArchive(sourceUri: Uri): TavernDataArchivePreview {
        return withExtractedArchive(sourceUri, "inspect") { extractRoot ->
            when (resolveImportPlan(extractRoot).layout) {
                ArchiveLayout.MANAGED_ROOT,
                ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> TavernDataArchivePreview(
                    archiveKind = TavernDataArchiveKind.HOST_FULL_SNAPSHOT
                )

                ArchiveLayout.UPSTREAM_USER_ROOT -> TavernDataArchivePreview(
                    archiveKind = TavernDataArchiveKind.USER_BACKUP,
                    sourceUserId = inferSourceUserId(sourceUri),
                    targetUserId = defaultUserHandle
                )
            }
        }
    }

    fun importDataArchive(sourceUri: Uri): TavernDataImportResult {
        val paths = HostPaths.from(appContext)
        paths.ensureWorkingDirectories()
        return withExtractedArchive(sourceUri, "import") { extractRoot ->
            val importPlan = resolveImportPlan(extractRoot)
            when (importPlan.layout) {
                ArchiveLayout.MANAGED_ROOT,
                ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> {
                    replaceManagedData(paths, importPlan)
                    TavernConfigRepository(appContext).syncStoredPortFromFile()
                    TavernDataImportResult(
                        importedFileCount = TavernConfigRepository.managedTopLevelDirectories.sumOf { directoryName ->
                            importPlan.managedDirectory(directoryName)
                                .takeIf { it.exists() }
                                ?.walkTopDown()
                                ?.count { it.isFile }
                                ?: 0
                        },
                        archiveKind = TavernDataArchiveKind.HOST_FULL_SNAPSHOT
                    )
                }

                ArchiveLayout.UPSTREAM_USER_ROOT -> {
                    replaceUpstreamUserBackup(paths, importPlan.sourceRoot)
                    TavernDataImportResult(
                        importedFileCount = importPlan.sourceRoot.walkTopDown().count { it.isFile },
                        archiveKind = TavernDataArchiveKind.USER_BACKUP
                    )
                }
            }
        }
    }

    private fun <T> withExtractedArchive(sourceUri: Uri, operationName: String, block: (File) -> T): T {
        val tempRoot = File(appContext.cacheDir, "bootstrap-settings/${operationName}-${System.currentTimeMillis()}")
        val extractRoot = File(tempRoot, "payload")
        extractRoot.mkdirs()

        try {
            val inputStream = appContext.contentResolver.openInputStream(sourceUri)
                ?: throw BootstrapException("无法读取导入压缩包。")
            inputStream.use { rawInput ->
                unzipInto(BufferedInputStream(rawInput), extractRoot)
            }
            return block(extractRoot)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun unzipInto(inputStream: BufferedInputStream, extractRoot: File) {
        val canonicalExtractRoot = extractRoot.canonicalFile
        val canonicalExtractPrefix = canonicalExtractRoot.path + File.separator

        ZipInputStream(inputStream).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                val normalizedPath = entry.name.removePrefix("./").trimStart('/').replace('\\', '/')
                if (normalizedPath.isBlank()) {
                    zipInput.closeEntry()
                    continue
                }

                val pathSegments = normalizedPath.split('/').filter { it.isNotBlank() }
                if (pathSegments.isEmpty() || pathSegments.first() == "__MACOSX" || pathSegments.last() == ".DS_Store") {
                    zipInput.closeEntry()
                    continue
                }

                val outputFile = File(extractRoot, normalizedPath).canonicalFile
                val outputPath = outputFile.path
                if (outputPath != canonicalExtractRoot.path && !outputPath.startsWith(canonicalExtractPrefix)) {
                    throw BootstrapException("导入包包含非法路径：${entry.name}")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    outputFile.outputStream().use { output ->
                        zipInput.copyTo(output)
                    }
                }
                zipInput.closeEntry()
            }
        }
    }

    private fun resolveImportPlan(extractRoot: File): ArchiveImportPlan {
        detectImportPlan(extractRoot)?.let { return it }

        val candidateChildren = extractRoot.listFiles()
            ?.filterNot { isMetadataEntry(it.name) }
            .orEmpty()

        if (candidateChildren.size == 1) {
            val wrappedRoot = candidateChildren.single()
            if (wrappedRoot.isDirectory) {
                detectImportPlan(wrappedRoot)?.let { return it }
            }
        }

        throw BootstrapException("导入包里没有可恢复的 Tavern 用户数据。")
    }

    private fun detectImportPlan(root: File): ArchiveImportPlan? {
        val topLevelEntries = root.listFiles()
            ?.filterNot { isMetadataEntry(it.name) }
            .orEmpty()
        if (topLevelEntries.isEmpty()) {
            return null
        }

        val managedDirectorySet = TavernConfigRepository.managedTopLevelDirectories.toSet()
        if (topLevelEntries.all { it.isDirectory && it.name in managedDirectorySet }) {
            return ArchiveImportPlan(ArchiveLayout.MANAGED_ROOT, root)
        }

        if (isPublicExtensionsDataRoot(root, topLevelEntries)) {
            return ArchiveImportPlan(ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT, root)
        }

        if (topLevelEntries.all(::isUpstreamUserBackupEntry)) {
            return ArchiveImportPlan(ArchiveLayout.UPSTREAM_USER_ROOT, root)
        }

        return null
    }

    private fun isMetadataEntry(name: String): Boolean {
        return name == "__MACOSX" || name == ".DS_Store"
    }

    private fun isUpstreamUserBackupEntry(entry: File): Boolean {
        return if (entry.isDirectory) {
            entry.name in upstreamUserBackupDirectories
        } else {
            isUpstreamUserBackupFile(entry.name)
        }
    }

    private fun isUpstreamUserBackupFile(name: String): Boolean {
        if (name.startsWith('.')) {
            return false
        }

        return name.endsWith(".json", ignoreCase = true) ||
            name.endsWith(".log", ignoreCase = true) ||
            name.endsWith(".txt", ignoreCase = true)
    }

    private fun inferSourceUserId(sourceUri: Uri): String? {
        val displayName = appContext.contentResolver.query(
            sourceUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        } ?: sourceUri.lastPathSegment?.substringAfterLast('/')

        val baseName = displayName?.substringBeforeLast('.', displayName)?.trim().orEmpty()
        if (baseName.isBlank()) {
            return null
        }

        val normalized = baseName.replace(Regex("-\\d{8}-\\d{6}$"), "").trim()
        return normalized.ifBlank { null }
    }

    private fun isPublicExtensionsDataRoot(root: File, topLevelEntries: List<File>): Boolean {
        val allowedTopLevelNames = setOf("config", "data", "plugins", "public")
        if (!topLevelEntries.all { it.isDirectory && it.name in allowedTopLevelNames }) {
            return false
        }

        if (!File(root, "config").isDirectory || !File(root, "data").isDirectory) {
            return false
        }

        val publicRoot = File(root, "public")
        if (!publicRoot.isDirectory) {
            return false
        }

        val extensionsRoot = File(publicRoot, "scripts/extensions/third-party")
        if (!extensionsRoot.isDirectory) {
            return false
        }

        val normalizedExpectedPaths = setOf(
            publicRoot.invariantSeparatorsPath,
            File(publicRoot, "scripts").invariantSeparatorsPath,
            File(publicRoot, "scripts/extensions").invariantSeparatorsPath,
            extensionsRoot.invariantSeparatorsPath
        )

        return publicRoot.walkTopDown()
            .filter { it != publicRoot }
            .all { child ->
                child.invariantSeparatorsPath in normalizedExpectedPaths || child.toPath().startsWith(extensionsRoot.toPath())
            }
    }

    private fun replaceManagedData(paths: HostPaths, importPlan: ArchiveImportPlan) {
        val targetRoot = paths.serverDataDir
        val backupRoot = File(paths.dataRoot, "server-import-backup-${System.currentTimeMillis()}")
        if (targetRoot.exists()) {
            targetRoot.copyRecursively(backupRoot, overwrite = true)
        }

        try {
            targetRoot.mkdirs()
            for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
                File(targetRoot, directoryName).deleteRecursively()
                val importedDirectory = importPlan.managedDirectory(directoryName)
                if (importedDirectory.exists()) {
                    importedDirectory.copyRecursively(File(targetRoot, directoryName), overwrite = true)
                } else {
                    File(targetRoot, directoryName).mkdirs()
                }
            }
        } catch (exception: Exception) {
            for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
                File(targetRoot, directoryName).deleteRecursively()
            }
            if (backupRoot.exists()) {
                backupRoot.copyRecursively(targetRoot, overwrite = true)
            }
            throw exception
        } finally {
            backupRoot.deleteRecursively()
        }
    }

    private fun replaceUpstreamUserBackup(paths: HostPaths, extractRoot: File) {
        val targetRoot = File(File(paths.serverDataDir, "data"), defaultUserHandle)
        val backupRoot = File(paths.dataRoot, "user-import-backup-${System.currentTimeMillis()}")
        val hadExistingTarget = targetRoot.exists()

        if (hadExistingTarget) {
            targetRoot.copyRecursively(backupRoot, overwrite = true)
        }

        try {
            targetRoot.deleteRecursively()
            targetRoot.mkdirs()
            for (entry in extractRoot.listFiles().orEmpty()) {
                val targetEntry = File(targetRoot, entry.name)
                if (entry.isDirectory) {
                    entry.copyRecursively(targetEntry, overwrite = true)
                } else {
                    entry.copyTo(targetEntry, overwrite = true)
                }
            }
        } catch (exception: Exception) {
            targetRoot.deleteRecursively()
            if (backupRoot.exists()) {
                backupRoot.copyRecursively(targetRoot, overwrite = true)
            }
            throw exception
        } finally {
            backupRoot.deleteRecursively()
        }
    }
}
