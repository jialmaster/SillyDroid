package com.jm.sillydroid.data.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jm.sillydroid.core.model.settings.TavernDataArchiveKind
import com.jm.sillydroid.core.model.settings.TavernDataArchivePreview
import com.jm.sillydroid.core.model.settings.TavernDataImportResult
import com.jm.sillydroid.domain.settings.DataArchiveRepository
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TavernDataArchiveManager(context: Context) : DataArchiveRepository {
    private enum class ArchiveLayout {
        MANAGED_ROOT,
        PUBLIC_EXTENSIONS_DATA_ROOT,
        UPSTREAM_USER_ROOT
    }

    private data class UserContentRoot(val root: File)

    private data class UserSettingsSnapshot(
        val connectionProfileCount: Int
    )

    private data class ArchiveImportPlan(
        val layout: ArchiveLayout,
        val sourceRoot: File
    ) {
        fun managedDirectory(directoryName: String): File = when (layout) {
            ArchiveLayout.MANAGED_ROOT -> File(sourceRoot, directoryName)
            ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> when (directoryName) {
                "extensions" -> File(sourceRoot, "public/scripts/extensions")
                else -> File(sourceRoot, directoryName)
            }

            ArchiveLayout.UPSTREAM_USER_ROOT -> File(sourceRoot, directoryName)
        }
    }

    companion object {
        private const val defaultUserHandle = "default-user"
        private const val layoutLabelManaged = "Docker 四目录（根目录为 config,data,plugins,extensions；Android 宿主导出同构）"
        private const val layoutLabelPublic = "Linux/Termux public 结构（根目录为 config,data,plugins,public；第三方扩展位于 public/scripts/extensions/third-party）"
        private val apiPresetDirectoryNames: Set<String> = setOf(
            "KoboldAI Settings",
            "NovelAI Settings",
            "TextGen Settings"
        )

        private val presetDirectoryNames: Set<String> = setOf(
            "OpenAI Settings",
            *apiPresetDirectoryNames.toTypedArray(),
            "context",
            "instruct",
            "QuickReplies",
            "sysprompt",
            "reasoning"
        )

        private val userContentMarkerDirectories: Set<String> = presetDirectoryNames + setOf(
            "characters",
            "chats",
            "group chats",
            "worlds"
        )

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

    override fun exportDataArchive(targetUri: Uri, kind: TavernDataArchiveKind) {
        val paths = TavernStoragePaths.from(appContext)
        paths.ensureWorkingDirectories()
        val outputStream = appContext.contentResolver.openOutputStream(targetUri)
            ?: throw SettingsDataException("无法写入导出目标。")

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

    override fun inspectDataArchive(sourceUri: Uri): TavernDataArchivePreview {
        return withExtractedArchive(sourceUri, "inspect") { extractRoot ->
            val importPlan = resolveImportPlan(extractRoot)
            when (importPlan.layout) {
                ArchiveLayout.MANAGED_ROOT,
                ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> TavernDataArchivePreview(
                    archiveKind = TavernDataArchiveKind.HOST_FULL_SNAPSHOT,
                    sourceLayoutLabel = when (importPlan.layout) {
                        ArchiveLayout.MANAGED_ROOT -> layoutLabelManaged
                        ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> layoutLabelPublic
                        ArchiveLayout.UPSTREAM_USER_ROOT -> null
                    },
                    writeTargets = when (importPlan.layout) {
                        ArchiveLayout.MANAGED_ROOT -> listOf(
                            "serverDataDir/config",
                            "serverDataDir/data",
                            "serverDataDir/plugins",
                            "serverDataDir/extensions（第三方扩展）",
                            "serverDir/public/scripts/extensions（仅内置扩展覆盖文件，排除 third-party）"
                        )

                        ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> listOf(
                            "serverDataDir/config",
                            "serverDataDir/data",
                            "serverDataDir/plugins",
                            "serverDataDir/extensions（第三方扩展）",
                            "serverDir/public/scripts/extensions（仅内置扩展覆盖文件，排除 third-party）"
                        )

                        ArchiveLayout.UPSTREAM_USER_ROOT -> emptyList()
                    },
                    contentStats = buildContentStats(importPlan)
                )

                ArchiveLayout.UPSTREAM_USER_ROOT -> TavernDataArchivePreview(
                    archiveKind = TavernDataArchiveKind.USER_BACKUP,
                    sourceUserId = inferSourceUserId(sourceUri),
                    targetUserId = defaultUserHandle,
                    writeTargets = listOf("serverDataDir/data/$defaultUserHandle"),
                    contentStats = buildContentStats(importPlan)
                )
            }
        }
    }

    private fun buildContentStats(importPlan: ArchiveImportPlan): List<String> {
        val userContentRoots = resolveUserContentRoots(importPlan)

        val roleCardCount = countManagedContentFiles(
            userContentRoots,
            setOf("characters"),
            setOf("png"),
            recursive = false
        )
        val chatCompletionPresetCount = countChatCompletionPresetFiles(userContentRoots)
        val apiPresetCount = countApiPresetFiles(userContentRoots)
        val apiConnectionConfigCount = countApiConnectionProfiles(userContentRoots)
        val dialogueCount = countManagedContentFiles(
            userContentRoots,
            setOf("chats", "group chats"),
            setOf("jsonl"),
            recursive = true
        )
        val worldsCount = countManagedContentFiles(
            userContentRoots,
            setOf("worlds"),
            setOf("json"),
            recursive = false
        )
        val thirdPartyExtensionsCount = countThirdPartyExtensions(importPlan)

        return listOf(
            "角色卡：$roleCardCount",
            "聊天补全预设：$chatCompletionPresetCount",
            "API预设：$apiPresetCount",
            "API连接配置：$apiConnectionConfigCount",
            "第三方扩展：$thirdPartyExtensionsCount",
            "对话：$dialogueCount",
            "世界书：$worldsCount"
        )
    }

    private fun countThirdPartyExtensions(importPlan: ArchiveImportPlan): Int {
        val thirdPartyRoot = when (importPlan.layout) {
            ArchiveLayout.MANAGED_ROOT -> {
                val extensionsRoot = importPlan.managedDirectory("extensions")
                val structuredThirdParty = File(extensionsRoot, "third-party")
                if (structuredThirdParty.isDirectory) structuredThirdParty else extensionsRoot
            }

            ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> File(importPlan.sourceRoot, "public/scripts/extensions/third-party")
            ArchiveLayout.UPSTREAM_USER_ROOT -> File(importPlan.sourceRoot, "extensions")
        }

        return thirdPartyRoot.listFiles()
            .orEmpty()
            .count { it.isDirectory }
    }

    private fun countChatCompletionPresetFiles(contentRoots: List<UserContentRoot>): Int {
        // 这里单独统计“聊天补全预设”。
        // 按 1.18.0 上游实现，聊天补全预设下拉固定来自 OpenAI Settings，
        // 它和 context / instruct / sysprompt / reasoning 这些独立模板不是同一类数据。
        return contentRoots.sumOf { contentRoot ->
            countManagedContentFiles(
                contentRoots = listOf(contentRoot),
                directoryNames = setOf("OpenAI Settings"),
                allowedExtensions = setOf("json"),
                recursive = false
            )
        }
    }

    private fun countApiPresetFiles(contentRoots: List<UserContentRoot>): Int {
        // 这里单独统计“API 预设”。
        // 按 1.18.0 上游 settings 接口，常规 API 预设分别从
        // KoboldAI Settings / NovelAI Settings / TextGen Settings 三个目录独立读取；
        // OpenAI Settings 已经在“聊天补全预设”中单列，这里不再重复计入。
        return countManagedContentFiles(
            contentRoots = contentRoots,
            directoryNames = apiPresetDirectoryNames,
            allowedExtensions = setOf("json"),
            recursive = false
        )
    }

    private fun countApiConnectionProfiles(contentRoots: List<UserContentRoot>): Int {
        // API 连接配置来自 connection-manager 扩展写入的 settings.json：
        // extension_settings.connectionManager.profiles
        // 导入预览里单独显示这个数组长度，避免和聊天补全预设文件数混淆。
        return contentRoots.sumOf { contentRoot ->
            loadUserSettingsSnapshot(contentRoot.root)?.connectionProfileCount ?: 0
        }
    }

    private fun loadUserSettingsSnapshot(userRoot: File): UserSettingsSnapshot? {
        val settingsFile = File(userRoot, "settings.json")
        if (!settingsFile.isFile) {
            return null
        }

        val settingsJson = runCatching {
            JSONObject(settingsFile.readText())
        }.getOrNull() ?: return null

        val connectionProfiles = settingsJson
            .optJSONObject("extension_settings")
            ?.optJSONObject("connectionManager")
            ?.optJSONArray("profiles")
            ?.length()
            ?: 0

        return UserSettingsSnapshot(
            connectionProfileCount = connectionProfiles
        )
    }

    private fun countManagedContentFiles(
        contentRoots: List<UserContentRoot>,
        directoryNames: Set<String>,
        allowedExtensions: Set<String>,
        recursive: Boolean
    ): Int {
        // 统计规则需要和酒馆当前用户目录模型对齐：
        // - characters 目录里真正被酒馆识别为角色卡的是根层 .png 文件；
        //   角色目录下的 sprites/backgrounds 等附属资源不能再被算成“角色卡数量”。
        // - worlds、各类 presets 目录由酒馆按目录根层文件读取；
        //   这里只统计酒馆实际会读取的文件类型，避免把旁路文件或旧格式误算进去。
        // - chats 仍保留递归统计，因为角色聊天会落在 chats/<character>/*.jsonl。
        return contentRoots.sumOf { contentRoot ->
            directoryNames.sumOf { directoryName ->
                val directory = File(contentRoot.root, directoryName)
                if (!directory.isDirectory) {
                    return@sumOf 0
                }

                val candidateFiles = if (recursive) {
                    directory.walkTopDown().asSequence().filter { it.isFile }
                } else {
                    directory.listFiles()
                        .orEmpty()
                        .asSequence()
                        .filter { it.isFile }
                }

                candidateFiles.count { file ->
                    file.extension.lowercase() in allowedExtensions
                }
            }
        }
    }

    private fun resolveUserContentRoots(importPlan: ArchiveImportPlan): List<UserContentRoot> {
        if (importPlan.layout == ArchiveLayout.UPSTREAM_USER_ROOT) {
            return listOf(createUserContentRoot(importPlan.sourceRoot))
        }

        val dataRoot = importPlan.managedDirectory("data")
        if (!dataRoot.isDirectory) {
            return emptyList()
        }

        val directChildren = dataRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
        val nestedUserRoots = directChildren.filter(::looksLikeNestedUserContentRoot)
        val resolvedRoots = if (nestedUserRoots.isNotEmpty()) {
            nestedUserRoots
        } else if (looksLikeUserContentRoot(dataRoot)) {
            listOf(dataRoot)
        } else {
            emptyList()
        }

        return resolvedRoots.map(::createUserContentRoot)
    }

    private fun looksLikeUserContentRoot(directory: File): Boolean {
        return userContentMarkerDirectories.any { File(directory, it).exists() }
    }

    private fun looksLikeNestedUserContentRoot(directory: File): Boolean {
        // data/ 下除了真正用户目录，还会出现 _cache、_storage 之类宿主/上游内部目录。
        // 这些目录可能碰巧含有 characters 等子目录，但并不代表一个完整用户。
        // 这里要求嵌套用户根必须带 settings.json，避免把内部缓存算进导入预览。
        if (directory.name.startsWith("_") || directory.name.startsWith(".")) {
            return false
        }

        return File(directory, "settings.json").isFile && looksLikeUserContentRoot(directory)
    }

    private fun createUserContentRoot(root: File): UserContentRoot {
        return UserContentRoot(root = root)
    }

    private fun countImportedFiles(importPlan: ArchiveImportPlan): Int {
        return when (importPlan.layout) {
            ArchiveLayout.MANAGED_ROOT,
            ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> TavernConfigRepository.managedTopLevelDirectories.sumOf { directoryName ->
                importPlan.managedDirectory(directoryName)
                    .takeIf { it.exists() }
                    ?.walkTopDown()
                    ?.count { it.isFile }
                    ?: 0
            }

            ArchiveLayout.UPSTREAM_USER_ROOT -> importPlan.sourceRoot.walkTopDown().count { it.isFile }
        }
    }

    override fun importDataArchive(sourceUri: Uri): TavernDataImportResult {
        val paths = TavernStoragePaths.from(appContext)
        paths.ensureWorkingDirectories()
        return withExtractedArchive(sourceUri, "import") { extractRoot ->
            val importPlan = resolveImportPlan(extractRoot)
            val importedFileCount = countImportedFiles(importPlan)
            when (importPlan.layout) {
                ArchiveLayout.MANAGED_ROOT,
                ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> {
                    replaceManagedData(paths, importPlan)
                    TavernConfigRepository(appContext).syncStoredPortFromFile()
                    TavernDataImportResult(
                        importedFileCount = importedFileCount,
                        archiveKind = TavernDataArchiveKind.HOST_FULL_SNAPSHOT
                    )
                }

                ArchiveLayout.UPSTREAM_USER_ROOT -> {
                    replaceUpstreamUserBackup(paths, importPlan.sourceRoot)
                    TavernDataImportResult(
                        importedFileCount = importedFileCount,
                        archiveKind = TavernDataArchiveKind.USER_BACKUP
                    )
                }
            }
        }
    }

    override fun clearManagedBootstrapState() {
        val paths = TavernStoragePaths.from(appContext)
        paths.ensureWorkingDirectories()
        File(paths.serverDataDir, ".sillydroid-maintenance").deleteRecursively()
        paths.serverDir.deleteRecursively()
        for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
            File(paths.serverDataDir, directoryName).deleteRecursively()
        }
    }

    private fun <T> withExtractedArchive(sourceUri: Uri, operationName: String, block: (File) -> T): T {
        val tempRoot = File(appContext.cacheDir, "bootstrap-settings/${operationName}-${System.currentTimeMillis()}")
        val extractRoot = File(tempRoot, "payload")
        extractRoot.mkdirs()

        try {
            val inputStream = appContext.contentResolver.openInputStream(sourceUri)
                ?: throw SettingsDataException("无法读取导入压缩包。")
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
                    throw SettingsDataException("导入包包含非法路径：${entry.name}")
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

        throw SettingsDataException("导入包里没有可恢复的 Tavern 用户数据。")
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

        val fullExtensionsRoot = File(publicRoot, "scripts/extensions")
        val thirdPartyRoot = File(fullExtensionsRoot, "third-party")
        if (!fullExtensionsRoot.isDirectory) {
            return false
        }

        val normalizedExpectedPaths = setOf(
            publicRoot.invariantSeparatorsPath,
            File(publicRoot, "scripts").invariantSeparatorsPath,
            fullExtensionsRoot.invariantSeparatorsPath,
            thirdPartyRoot.invariantSeparatorsPath
        )

        return publicRoot.walkTopDown()
            .filter { it != publicRoot }
            .all { child ->
                child.invariantSeparatorsPath in normalizedExpectedPaths ||
                    child.toPath().startsWith(fullExtensionsRoot.toPath())
            }
    }

    private fun replaceManagedData(paths: TavernStoragePaths, importPlan: ArchiveImportPlan) {
        val targetRoot = paths.serverDataDir
        val backupRoot = File(paths.dataRoot, "server-import-backup-${System.currentTimeMillis()}")
        val builtinTargetRoot = File(paths.serverDir, "public/scripts/extensions")
        val builtinBackupRoot = File(paths.dataRoot, "server-import-builtin-extensions-backup-${System.currentTimeMillis()}")

        targetRoot.mkdirs()
        backupRoot.mkdirs()
        backupBuiltinExtensionsOverlay(builtinTargetRoot, builtinBackupRoot)

        try {
            for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
                val targetDirectory = File(targetRoot, directoryName)
                if (targetDirectory.exists()) {
                    movePath(targetDirectory, File(backupRoot, directoryName))
                }
                val importedDirectory = importPlan.managedDirectory(directoryName)
                if (directoryName == "extensions") {
                    restoreExtensionsWithLayout(paths, importedDirectory, importPlan.layout)
                } else if (importedDirectory.exists()) {
                    movePath(importedDirectory, targetDirectory)
                } else {
                    targetDirectory.mkdirs()
                }
            }
        } catch (exception: Exception) {
            for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
                File(targetRoot, directoryName).deleteRecursively()
            }
            for (directoryName in TavernConfigRepository.managedTopLevelDirectories) {
                val backupDirectory = File(backupRoot, directoryName)
                if (backupDirectory.exists()) {
                    movePath(backupDirectory, File(targetRoot, directoryName))
                }
            }
            restoreBuiltinExtensionsOverlayFromBackup(builtinBackupRoot, builtinTargetRoot)
            throw exception
        } finally {
            backupRoot.deleteRecursively()
            builtinBackupRoot.deleteRecursively()
        }
    }

    private fun restoreExtensionsWithLayout(paths: TavernStoragePaths, importedDirectory: File, layout: ArchiveLayout) {
        val thirdPartyTargetRoot = File(paths.serverDataDir, "extensions")
        val builtinTargetRoot = File(paths.serverDir, "public/scripts/extensions")

        thirdPartyTargetRoot.deleteRecursively()
        thirdPartyTargetRoot.mkdirs()

        when (layout) {
            ArchiveLayout.MANAGED_ROOT -> restoreManagedRootExtensions(importedDirectory, thirdPartyTargetRoot, builtinTargetRoot)
            ArchiveLayout.PUBLIC_EXTENSIONS_DATA_ROOT -> restorePublicRootExtensions(importedDirectory, thirdPartyTargetRoot, builtinTargetRoot)
            ArchiveLayout.UPSTREAM_USER_ROOT -> Unit
        }
    }

    private fun restoreManagedRootExtensions(importedDirectory: File, thirdPartyTargetRoot: File, builtinTargetRoot: File) {
        if (!importedDirectory.exists()) {
            return
        }

        val thirdPartySource = File(importedDirectory, "third-party")
        val hasStructuredLayout = thirdPartySource.isDirectory || File(importedDirectory, "builtin").isDirectory

        if (!hasStructuredLayout) {
            // 旧式平铺结构：整个 importedDirectory 就是 third-party 扩展
            importedDirectory.copyRecursively(thirdPartyTargetRoot, overwrite = true)
            return
        }

        if (thirdPartySource.isDirectory) {
            copyDirectoryChildren(thirdPartySource, thirdPartyTargetRoot)
        }

        // 仅覆盖内置扩展目录中的文件，不触碰 third-party（它是 symlink）。
        val builtinSource = File(importedDirectory, "builtin")
        if (builtinSource.isDirectory) {
            restoreBuiltinExtensionsOverlay(builtinSource, builtinTargetRoot)
        }
    }

    private fun restorePublicRootExtensions(importedPublicExtensionsRoot: File, thirdPartyTargetRoot: File, builtinTargetRoot: File) {
        if (!importedPublicExtensionsRoot.isDirectory) {
            return
        }

        val thirdPartySource = File(importedPublicExtensionsRoot, "third-party")
        if (thirdPartySource.isDirectory) {
            copyDirectoryChildren(thirdPartySource, thirdPartyTargetRoot)
        }

        // public/scripts/extensions 下除 third-party 外的目录，按覆盖方式恢复到程序层。
        restoreBuiltinExtensionsOverlay(importedPublicExtensionsRoot, builtinTargetRoot)
    }

    private fun restoreBuiltinExtensionsOverlay(sourceRoot: File, builtinTargetRoot: File) {
        if (!sourceRoot.isDirectory || !builtinTargetRoot.isDirectory) {
            return
        }

        for (child in sourceRoot.listFiles().orEmpty()) {
            if (child.name == "third-party") {
                continue
            }

            val targetChild = File(builtinTargetRoot, child.name)
            if (targetChild.exists()) {
                targetChild.deleteRecursively()
            }

            if (child.isDirectory) {
                child.copyRecursively(targetChild, overwrite = true)
            } else {
                targetChild.parentFile?.mkdirs()
                child.copyTo(targetChild, overwrite = true)
            }
        }
    }

    private fun copyDirectoryChildren(sourceRoot: File, targetRoot: File, shouldSkipName: (String) -> Boolean = { false }) {
        if (!sourceRoot.isDirectory) {
            return
        }

        targetRoot.mkdirs()
        for (child in sourceRoot.listFiles().orEmpty()) {
            if (shouldSkipName(child.name)) {
                continue
            }

            val targetChild = File(targetRoot, child.name)
            if (child.isDirectory) {
                child.copyRecursively(targetChild, overwrite = true)
            } else {
                targetChild.parentFile?.mkdirs()
                child.copyTo(targetChild, overwrite = true)
            }
        }
    }

    private fun backupBuiltinExtensionsOverlay(sourceRoot: File, backupRoot: File) {
        backupRoot.deleteRecursively()
        backupRoot.mkdirs()
        copyDirectoryChildren(sourceRoot, backupRoot) { it == "third-party" }
    }

    private fun restoreBuiltinExtensionsOverlayFromBackup(backupRoot: File, targetRoot: File) {
        if (!backupRoot.exists()) {
            return
        }

        targetRoot.mkdirs()
        for (child in targetRoot.listFiles().orEmpty()) {
            if (child.name == "third-party") {
                continue
            }
            child.deleteRecursively()
        }
        copyDirectoryChildren(backupRoot, targetRoot)
    }

    private fun movePath(source: File, target: File) {
        if (!source.exists()) {
            return
        }

        target.parentFile?.mkdirs()
        if (target.exists()) {
            target.deleteRecursively()
        }
        if (source.renameTo(target)) {
            return
        }

        val copied = if (source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
        } else {
            source.copyTo(target, overwrite = true)
            true
        }
        if (!copied) {
            throw IOException("无法移动 ${source.absolutePath} 到 ${target.absolutePath}")
        }

        val deleted = if (source.isDirectory) {
            source.deleteRecursively()
        } else {
            source.delete()
        }
        if (!deleted && source.exists()) {
            throw IOException("无法删除已迁移的源路径：${source.absolutePath}")
        }
    }

    private fun replaceUpstreamUserBackup(paths: TavernStoragePaths, extractRoot: File) {
        val targetRoot = File(File(paths.serverDataDir, "data"), defaultUserHandle)
        val backupRoot = File(paths.dataRoot, "user-import-backup-${System.currentTimeMillis()}")

        try {
            if (targetRoot.exists()) {
                movePath(targetRoot, backupRoot)
            }
            targetRoot.mkdirs()
            for (entry in extractRoot.listFiles().orEmpty()) {
                val targetEntry = File(targetRoot, entry.name)
                movePath(entry, targetEntry)
            }
        } catch (exception: Exception) {
            targetRoot.deleteRecursively()
            if (backupRoot.exists()) {
                movePath(backupRoot, targetRoot)
            }
            throw exception
        } finally {
            backupRoot.deleteRecursively()
        }
    }
}
