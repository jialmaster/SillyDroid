package com.jm.sillydroid.data.logs

import com.jm.sillydroid.core.model.logs.HostLogExportOption
import java.io.File
import java.util.Locale

internal object HostLogExportPlanner {
    private const val startupLogPrefix = "startup-"
    private const val tavernServerLogPrefix = "sillydroid-server-"
    private const val rootfsRuntimeLogPrefix = "rootfs-runtime-"
    private const val hostDiagnosticsLogPrefix = "host-diagnostics-"
    private const val jsErrorLogPrefix = "js-error-"

    private const val exportTypeStartup = "startup"
    private const val exportTypeTavernServer = "tavern_server"
    private const val exportTypeRootfsRuntime = "rootfs_runtime"
    private const val exportTypeHostDiagnostics = "host_diagnostics"
    private const val exportTypeJsError = "js_error"
    private const val exportTypeCrash = "crash"
    private const val exportTypeExitInfo = "exit_info"
    private const val exportTypeRepositoryUpdate = "repository_update"
    private const val exportTypeExtensionPreview = "extension_preview"
    private const val exportTypeExtensionInstall = "extension_install"
    private const val exportTypeExtensionReinstall = "extension_reinstall"
    private const val exportTypeExtensionRuntime = "extension_runtime"
    private const val exportTypePluginPreview = "plugin_preview"
    private const val exportTypePluginInstall = "plugin_install"
    private const val exportTypePluginUpdate = "plugin_update"
    private const val exportTypePluginDependency = "plugin_dependency"
    private const val exportTypeOther = "other"
    // 导出弹窗需要稳定展示一套固定的“日志类型”选择项，避免因为某类日志尚未落盘就把整项隐藏掉，
    // 让用户误以为该类型根本不支持导出。
    private val stableExportTypeKeys = listOf(
        exportTypeStartup,
        exportTypeTavernServer,
        exportTypeRootfsRuntime,
        exportTypeHostDiagnostics,
        exportTypeJsError,
        exportTypeCrash,
        exportTypeExitInfo,
        exportTypeRepositoryUpdate,
        exportTypeExtensionPreview,
        exportTypeExtensionInstall,
        exportTypeExtensionReinstall,
        exportTypeExtensionRuntime,
        exportTypePluginPreview,
        exportTypePluginInstall,
        exportTypePluginUpdate,
        exportTypePluginDependency
    )

    private val logTabVisibleMaintenanceTypeKeys = setOf(
        exportTypeRepositoryUpdate,
        exportTypeExtensionPreview,
        exportTypeExtensionInstall,
        exportTypeExtensionReinstall,
        exportTypeExtensionRuntime,
        exportTypePluginPreview,
        exportTypePluginInstall,
        exportTypePluginUpdate,
        exportTypePluginDependency
    )

    fun resolveDisplayName(fileName: String): String {
        return resolveExportDisplayName(resolveExportTypeKey(fileName))
    }

    fun displayOrder(fileName: String): Int {
        return exportTypeOrder(resolveExportTypeKey(fileName))
    }

    fun isVisibleInLogTab(fileName: String): Boolean {
        // 日志 Tab 默认只看当前启动日志；扩展/插件维护命令没有会话号，必须按文件名前缀单独放行。
        return resolveExportTypeKey(fileName) in logTabVisibleMaintenanceTypeKeys
    }

    fun buildExportOptions(logFiles: List<File>, logsDir: File): List<HostLogExportOption> {
        if (logFiles.isEmpty()) {
            return emptyList()
        }

        val groupedFiles = logFiles.groupBy { file ->
            resolveExportTypeKey(file.relativeTo(logsDir).invariantSeparatorsPath)
        }
        val stableOptions = stableExportTypeKeys.map { typeKey ->
            val files = groupedFiles[typeKey].orEmpty()
            HostLogExportOption(
                typeKey = typeKey,
                displayName = resolveExportDisplayName(typeKey),
                relativePaths = files
                    .map { file -> file.relativeTo(logsDir).invariantSeparatorsPath }
                    .toSortedSet(String.CASE_INSENSITIVE_ORDER),
                selectedByDefault = isSelectedByDefaultExportType(typeKey),
                containsSensitiveContent = isSensitiveExportType(typeKey)
            )
        }
        val dynamicOptions = groupedFiles
            .filterKeys { typeKey -> typeKey !in stableExportTypeKeys }
            .map { (typeKey, files) ->
                HostLogExportOption(
                    typeKey = typeKey,
                    displayName = resolveExportDisplayName(typeKey),
                    relativePaths = files
                        .map { file -> file.relativeTo(logsDir).invariantSeparatorsPath }
                        .toSortedSet(String.CASE_INSENSITIVE_ORDER),
                    selectedByDefault = isSelectedByDefaultExportType(typeKey),
                    containsSensitiveContent = isSensitiveExportType(typeKey)
                )
            }
        return (stableOptions + dynamicOptions)
            .sortedWith(
                compareBy<HostLogExportOption>({ exportTypeOrder(it.typeKey) })
                    .thenBy { it.displayName }
            )
    }

    fun collectLogFiles(logsDir: File, includedRelativePaths: Set<String>? = null): List<File> {
        if (!logsDir.isDirectory) {
            return emptyList()
        }

        val normalizedIncludedPaths = includedRelativePaths
            ?.map { path -> path.replace('\\', '/').trimStart('/') }
            ?.toSet()

        return logsDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    isCollectableLogArtifact(file.relativeTo(logsDir).invariantSeparatorsPath) &&
                    (
                        normalizedIncludedPaths == null ||
                            file.relativeTo(logsDir).invariantSeparatorsPath in normalizedIncludedPaths
                        )
            }
            .sortedBy { file -> file.relativeTo(logsDir).invariantSeparatorsPath.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun resolveExportTypeKey(relativePath: String): String {
        val normalizedPath = relativePath.replace('\\', '/').lowercase(Locale.ROOT)
        val normalizedName = File(normalizedPath).name
        return when {
            normalizedPath.startsWith("${HostLogManager.exitInfoTraceDirectoryName}/") -> exportTypeExitInfo
            normalizedName.startsWith(tavernServerLogPrefix) -> exportTypeTavernServer
            normalizedName.startsWith(startupLogPrefix) -> exportTypeStartup
            normalizedName.startsWith(rootfsRuntimeLogPrefix) -> exportTypeRootfsRuntime
            normalizedName.startsWith(hostDiagnosticsLogPrefix) -> exportTypeHostDiagnostics
            normalizedName.startsWith(jsErrorLogPrefix) -> exportTypeJsError
            normalizedName == HostLogManager.crashLogFileName -> exportTypeCrash
            normalizedName == HostLogManager.exitInfoLogFileName -> exportTypeExitInfo
            normalizedName.startsWith("repository-update-check-") -> exportTypeRepositoryUpdate
            normalizedName.startsWith("extension-install-preview-") -> exportTypeExtensionPreview
            normalizedName.startsWith("extension-install-") -> exportTypeExtensionInstall
            normalizedName.startsWith("extension-reinstall-") -> exportTypeExtensionReinstall
            normalizedName.startsWith("plugin-preview-") -> exportTypePluginPreview
            normalizedName.startsWith("plugin-install-") -> exportTypePluginInstall
            normalizedName.startsWith("plugin-git-update-") || normalizedName.startsWith("plugin-update-") -> exportTypePluginUpdate
            normalizedName.startsWith("server-plugin-npm-install-") -> exportTypePluginDependency
            normalizedName.startsWith("extension-") -> exportTypeExtensionRuntime
            else -> exportTypeOther
        }
    }

    private fun isCollectableLogArtifact(relativePath: String): Boolean {
        val normalizedPath = relativePath.replace('\\', '/').lowercase(Locale.ROOT)
        if (normalizedPath.endsWith(".tmp")) {
            return false
        }

        return normalizedPath.endsWith(".log") ||
            normalizedPath.startsWith("${HostLogManager.exitInfoTraceDirectoryName}/")
    }

    private fun resolveExportDisplayName(typeKey: String): String {
        return when (typeKey) {
            exportTypeTavernServer -> "酒馆服务日志"
            exportTypeStartup -> "启动日志"
            exportTypeRootfsRuntime -> "运行时日志"
            exportTypeHostDiagnostics -> "宿主诊断日志"
            exportTypeJsError -> "WebView JS 报错"
            exportTypeCrash -> "应用崩溃日志"
            exportTypeExitInfo -> "应用退出信息"
            exportTypeRepositoryUpdate -> "仓库更新检测日志"
            exportTypeExtensionPreview -> "扩展预检日志"
            exportTypeExtensionInstall -> "扩展安装日志"
            exportTypeExtensionReinstall -> "扩展重装日志"
            exportTypeExtensionRuntime -> "扩展运行日志"
            exportTypePluginPreview -> "插件预检日志"
            exportTypePluginInstall -> "插件安装日志"
            exportTypePluginUpdate -> "插件更新日志"
            exportTypePluginDependency -> "插件依赖安装日志"
            else -> "其他日志"
        }
    }

    private fun exportTypeOrder(typeKey: String): Int {
        return when (typeKey) {
            exportTypeStartup -> 0
            exportTypeTavernServer -> 1
            exportTypeRootfsRuntime -> 2
            exportTypeHostDiagnostics -> 3
            exportTypeJsError -> 4
            exportTypeCrash -> 5
            exportTypeExitInfo -> 6
            exportTypeRepositoryUpdate -> 7
            exportTypeExtensionPreview -> 8
            exportTypeExtensionInstall -> 9
            exportTypeExtensionReinstall -> 10
            exportTypeExtensionRuntime -> 11
            exportTypePluginPreview -> 12
            exportTypePluginInstall -> 13
            exportTypePluginUpdate -> 14
            exportTypePluginDependency -> 15
            else -> Int.MAX_VALUE
        }
    }

    private fun isSensitiveExportType(typeKey: String): Boolean {
        return typeKey == exportTypeTavernServer
    }

    // 默认把所有宿主诊断类日志勾上，只把可能包含聊天收发内容的酒馆服务日志交给用户显式选择。
    private fun isSelectedByDefaultExportType(typeKey: String): Boolean {
        return typeKey != exportTypeTavernServer
    }
}
