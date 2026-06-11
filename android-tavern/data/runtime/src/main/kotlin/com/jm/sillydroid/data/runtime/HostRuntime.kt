package com.jm.sillydroid.data.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepDetection
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverrides
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverridesCodec
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import org.json.JSONObject

object BootConfig {
    const val defaultServicePort = 8000
    const val readinessProbeAttempts = 300
    const val readinessPath = "/"
    const val bootstrapAssetRoot = "bootstrap"
    const val guestRuntimePrefix = "/data/data/com.termux/files/usr"
    const val notificationChannelId = "android-tavern-bootstrap"
    const val systemNotificationChannelId = "android-tavern-system-notification"
    const val notificationId = 1101
}

private const val runtimeModeTermuxHost = "termux-host"
private val requiredHostRuntimeFileNames = listOf(
    "libtermux-node.so",
    "libtermux-git.so",
    "libtermux-git-remote-http.so",
    "libtermux-sh.so"
)
private val optionalHostRuntimeFileNames = listOf("libtermux-bash.so")
private val executableHostRuntimeFileNames = listOf(
    "libtermux-node.so",
    "libtermux-git.so",
    "libtermux-git-remote-http.so",
    "libtermux-sh.so",
    "libtermux-bash.so"
)
private const val forcePackageHostRuntimeMarkerFileName = ".force-package-host-runtime"

internal fun isHostRuntimeDirectoryReady(hostLibDir: File): Boolean {
    val requiredFilesReady = hostLibDir.containsNamedFiles(requiredHostRuntimeFileNames)
    val requiredExecutablesReady = hostLibDir.containsExecutableNamedFiles(
        executableHostRuntimeFileNames - optionalHostRuntimeFileNames
    )
    val optionalExecutablesReady = optionalHostRuntimeFileNames.all { fileName ->
        val file = File(hostLibDir, fileName)
        !file.exists() || file.canExecute()
    }
    return requiredFilesReady && requiredExecutablesReady && optionalExecutablesReady
}

internal fun selectHostRuntimeDirectory(
    nativeHostLibDir: File,
    packageHostLibDirs: List<File>,
    forcePackageHostRuntime: Boolean
): File {
    val candidates = if (forcePackageHostRuntime) {
        packageHostLibDirs
    } else {
        listOf(nativeHostLibDir) + packageHostLibDirs
    }.distinctBy { it.absolutePath }

    // host runtime 不能从 app 私有 files/ 目录兜底执行：Android 正常 App 进程会拒绝执行可写数据目录中的 ELF。
    // 因此这里只在包管理器解压出的安装目录中查找可执行 so，避免 debug/run-as 能跑但真实用户进程失败。
    return candidates.firstOrNull(::isHostRuntimeDirectoryReady) ?: nativeHostLibDir
}

internal fun shouldForcePackageHostRuntime(appFlags: Int, bootstrapRoot: File): Boolean {
    val isDebuggable = (appFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // 这个 marker 只服务真机复现：debug 包可以强制绕过 nativeLibraryDir，
    // 直接验证安装目录 lib/arm64 下的 host runtime 是否可作为 nativeLibraryDir 缺失时的兜底；release 包不会响应该开关。
    return isDebuggable && File(bootstrapRoot, forcePackageHostRuntimeMarkerFileName).isFile
}

internal fun deleteRecursivelyWithoutFollowingSymlinks(target: File) {
    val rootPath = target.toPath()
    if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        return
    }

    // serverDir 里 third-party 是指向持久 extensions 的 symlink；重解包时只能删除链接本身。
    Files.walkFileTree(
        rootPath,
        emptySet<FileVisitOption>(),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    throw exc
                }
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        }
    )
}

private fun File.existsWithoutFollowingSymlinks(): Boolean {
    return Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)
}

private fun File.containsNamedFiles(fileNames: List<String>): Boolean {
    return fileNames.all { fileName -> File(this, fileName).isFile }
}

private fun File.containsExecutableNamedFiles(fileNames: List<String>): Boolean {
    return fileNames.all { fileName -> File(this, fileName).canExecute() }
}

internal fun resolvePackageHostRuntimeDirectories(sourceDirs: List<File>, nativeHostLibDir: File): List<File> {
    val nativeLibRoot = nativeHostLibDir.parentFile?.parentFile
    val installRoots = (sourceDirs.mapNotNull { it.parentFile } + listOfNotNull(nativeLibRoot))
        .distinctBy { it.absolutePath }
    val abiDirectories = resolveRuntimeAbiDirectoryNames()

    return installRoots
        .flatMap { installRoot ->
            abiDirectories.map { abiDirectory -> File(File(installRoot, "lib"), abiDirectory) }
        }
        .distinctBy { it.absolutePath }
}

internal fun resolveRuntimeAbiDirectoryNames(): List<String> {
    val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
    val abiNames = supportedAbis.flatMap { abi ->
        when (abi) {
            "arm64-v8a" -> listOf("arm64", "arm64-v8a")
            "armeabi-v7a" -> listOf("arm", "armeabi-v7a")
            else -> listOf(abi)
        }
    }
    return (abiNames + listOf("arm64", "arm64-v8a")).distinct()
}

data class HostPaths(
    val bootstrapRoot: File,
    val scriptsDir: File,
    val rootfsDir: File,
    val serverDir: File,
    val hostPrefixDir: File,
    val hostLibDir: File,
    val hostTmpDir: File,
    val hostTermuxNodeBinary: File,
    val hostTermuxGitBinary: File,
    val hostTermuxGitRemoteHttpBinary: File,
    val hostTermuxShellBinary: File,
    val hostTermuxBashBinary: File,
    val dataRoot: File,
    val serverDataDir: File,
    val logsDir: File
) {
    companion object {
        fun from(context: Context): HostPaths {
            val bootstrapRoot = File(context.filesDir, "android-tavern/bootstrap")
            val dataRoot = File(context.filesDir, "android-tavern/data")
            val rootfsDir = File(bootstrapRoot, "rootfs")
            val hostPrefixDir = File(context.filesDir, "usr")
            val hostNativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val forcePackageHostRuntime = shouldForcePackageHostRuntime(context.applicationInfo.flags, bootstrapRoot)
            val packageHostLibDirs = resolvePackageHostRuntimeDirectories(
                sourceDirs = listOfNotNull(
                    context.applicationInfo.sourceDir,
                    context.applicationInfo.publicSourceDir,
                    *context.applicationInfo.splitSourceDirs.orEmpty()
                ).map(::File),
                nativeHostLibDir = hostNativeLibDir
            )
            val selectedHostLibDir = selectHostRuntimeDirectory(
                nativeHostLibDir = hostNativeLibDir,
                packageHostLibDirs = packageHostLibDirs,
                forcePackageHostRuntime = forcePackageHostRuntime
            )
            return HostPaths(
                bootstrapRoot = bootstrapRoot,
                scriptsDir = File(bootstrapRoot, "scripts"),
                rootfsDir = rootfsDir,
                serverDir = File(bootstrapRoot, "server"),
                hostPrefixDir = hostPrefixDir,
                hostLibDir = selectedHostLibDir,
                hostTmpDir = File(hostPrefixDir, "tmp"),
                hostTermuxNodeBinary = File(selectedHostLibDir, "libtermux-node.so"),
                hostTermuxGitBinary = File(selectedHostLibDir, "libtermux-git.so"),
                hostTermuxGitRemoteHttpBinary = File(selectedHostLibDir, "libtermux-git-remote-http.so"),
                hostTermuxShellBinary = File(selectedHostLibDir, "libtermux-sh.so"),
                hostTermuxBashBinary = File(selectedHostLibDir, "libtermux-bash.so"),
                dataRoot = dataRoot,
                serverDataDir = File(dataRoot, "server"),
                logsDir = resolveHostLogsDir(context)
            )
        }
    }

    fun ensureWorkingDirectories() {
        listOf(bootstrapRoot, scriptsDir, rootfsDir, serverDir, hostPrefixDir, hostTmpDir, dataRoot, serverDataDir, logsDir)
            .forEach { directory ->
                if (!directory.exists()) {
                    directory.mkdirs()
                }
            }
    }
}

fun describeHostRuntimeSelection(context: Context, paths: HostPaths): String {
    val packageHostLibDirs = resolvePackageHostRuntimeDirectories(
        sourceDirs = listOfNotNull(
            context.applicationInfo.sourceDir,
            context.applicationInfo.publicSourceDir,
            *context.applicationInfo.splitSourceDirs.orEmpty()
        ).map(::File),
        nativeHostLibDir = File(context.applicationInfo.nativeLibraryDir)
    )
    val selectedPath = paths.hostLibDir.absolutePath
    val packageCandidates = packageHostLibDirs.joinToString(separator = ",") { candidate ->
        describeRuntimeDirectory(candidate, selectedPath)
    }.ifBlank { "none" }

    // 三星/Android 14 这类 ROM 差异通常只在真实 native exec 阶段暴露；
    // 启动日志必须保留安装目录、ABI、SELinux 与可执行位，方便用户截图时直接定位失败层。
    return buildString {
        append("host_runtime ")
        append("manufacturer=${Build.MANUFACTURER.orEmpty()} ")
        append("model=${Build.MODEL.orEmpty()} ")
        append("sdk=${Build.VERSION.SDK_INT} ")
        append("release=${Build.VERSION.RELEASE.orEmpty()} ")
        append("supportedAbis=${Build.SUPPORTED_ABIS?.joinToString(separator = ",").orEmpty()} ")
        append("selinux=${normalizeSelinuxDiagnosticState(readFirstCommandLine("getenforce"))} ")
        append("nativeLibraryDir=${context.applicationInfo.nativeLibraryDir} ")
        append("selected=${describeRuntimeDirectory(paths.hostLibDir, selectedPath)} ")
        append("packageCandidates=[$packageCandidates]")
    }
}

private fun describeRuntimeDirectory(directory: File, selectedPath: String): String {
    val fileStates = (requiredHostRuntimeFileNames + optionalHostRuntimeFileNames)
        .distinct()
        .joinToString(separator = ",") { fileName ->
            describeRuntimeFile(File(directory, fileName))
        }
    return buildString {
        append(directory.absolutePath)
        if (directory.absolutePath == selectedPath) {
            append("(selected)")
        }
        append("{exists=")
        append(directory.exists())
        append(",canRead=")
        append(directory.canRead())
        append(",canExecute=")
        append(directory.canExecute())
        append(",files=[")
        append(fileStates)
        append("]}")
    }
}

private fun describeRuntimeFile(file: File): String {
    val mode = readFileMode(file)
    return buildString {
        append(file.name)
        append(":exists=")
        append(file.exists())
        append(",file=")
        append(file.isFile)
        append(",canRead=")
        append(file.canRead())
        append(",canExecute=")
        append(file.canExecute())
        append(",size=")
        append(file.length())
        if (mode.isNotBlank()) {
            append(",mode=")
            append(mode)
        }
    }
}

private fun readFileMode(file: File): String {
    return runCatching {
        val mode = Os.stat(file.absolutePath).st_mode and 0x1FF
        mode.toString(radix = 8).padStart(3, '0')
    }.getOrDefault("")
}

internal fun normalizeSelinuxDiagnosticState(rawState: String): String {
    val firstLine = rawState
        .lineSequence()
        .map { line -> line.trim() }
        .firstOrNull { line -> line.isNotBlank() }
        .orEmpty()

    // Some Android sandboxes deny getenforce even though startup can continue normally.
    return listOf("Enforcing", "Permissive", "Disabled")
        .firstOrNull { state -> state.equals(firstLine, ignoreCase = true) }
        ?: "unavailable"
}

private fun readFirstCommandLine(command: String): String {
    return runCatching {
        ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val output = process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
                process.waitFor(1, TimeUnit.SECONDS)
                output.lineSequence().firstOrNull().orEmpty()
            }
    }.getOrDefault("")
}

fun resolveHostLogsDir(context: Context): File {
    return File(context.applicationContext.filesDir, "android-tavern/logs")
}

fun readInstalledRootfsGuestShellPath(paths: HostPaths): String {
    val manifestFile = File(paths.rootfsDir, "rootfs-manifest.json")
    if (!manifestFile.isFile) {
        return "/bin/sh"
    }

    return runCatching {
        JSONObject(manifestFile.readText())
            .optString("guestShellPath")
            .trim()
            .ifBlank { "/bin/sh" }
    }.getOrDefault("/bin/sh")
}

fun readInstalledRuntimeMode(paths: HostPaths): String {
    val manifestFile = File(paths.rootfsDir, "rootfs-manifest.json")
    if (!manifestFile.isFile) {
        return runtimeModeTermuxHost
    }

    // rootfs manifest 由本仓库脚本生成，runtimeMode 只用于选择启动链路；
    // 用文本读取避免 JVM 单测和 Android 端 JSONObject 行为差异导致误判运行时入口。
    return Regex(""""runtimeMode"\s*:\s*"([^"]*)"""")
        .find(runCatching { manifestFile.readText() }.getOrDefault(""))
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { mode -> mode == runtimeModeTermuxHost }
        ?: runtimeModeTermuxHost
}

/**
 * rootfs/server manifest 是项目脚本固定生成的 JSON 文本；
 * 这里仅去掉会随每次打包变化的字段，保留其他内容参与比较，避免“同内容重打包”触发整目录重解压。
 */
internal fun normalizeBootstrapManifestForComparison(content: String): String {
    return content
        .replace("\r\n", "\n")
        .lineSequence()
        .filterNot { line ->
            line.trimStart().startsWith("\"syncedAtUtc\":")
        }
        .joinToString(separator = "\n")
        .trim()
}

/**
 * 所有进入 Termux host runtime 的入口都必须共享同一套 host 侧环境变量，
 * 避免设置页终端、扩展命令和正式服务各自拼环境后出现 loader、native lib 或 prefix 契约分叉。
 */
internal fun buildHostRuntimeEnvironment(paths: HostPaths): Map<String, String> {
    return buildMap {
        put("SILLYDROID_RUNTIME_MODE", readInstalledRuntimeMode(paths))
        put("BOOTSTRAP_ROOT", paths.bootstrapRoot.absolutePath)
        put("ROOTFS_DIR", paths.rootfsDir.absolutePath)
        put("SERVER_DIR", paths.serverDir.absolutePath)
        put("LOGS_DIR", paths.logsDir.absolutePath)
        put("HOST_NATIVE_LIB_DIR", paths.hostLibDir.absolutePath)
        put("HOST_PREFIX_DIR", paths.hostPrefixDir.absolutePath)
        put("HOST_RUNTIME_PREFIX", BootConfig.guestRuntimePrefix)
        put("HOST_TMP_DIR", paths.hostTmpDir.absolutePath)
        put("TERMUX_NODE_BIN", paths.hostTermuxNodeBinary.absolutePath)
        put("TERMUX_GIT_BIN", paths.hostTermuxGitBinary.absolutePath)
        put("TERMUX_GIT_REMOTE_HTTP_BIN", paths.hostTermuxGitRemoteHttpBinary.absolutePath)
        put("TERMUX_SH_BIN", paths.hostTermuxShellBinary.absolutePath)
        if (paths.hostTermuxBashBinary.exists()) {
            put("TERMUX_BASH_BIN", paths.hostTermuxBashBinary.absolutePath)
        }
    }
}

data class AssetPreparationInspection(
    val detection: BootstrapStepDetection,
    val details: String
)

private data class ServerAssetRefreshPlan(
    val sourceChanged: Boolean,
    val dependenciesChanged: Boolean,
    val detection: BootstrapStepDetection
) {
    val anyChanged: Boolean
        get() = sourceChanged || dependenciesChanged
}

class AssetExtractor(private val context: Context) {
    companion object {
        private val rootfsRequiredRelativePaths = listOf(
            "fs/bin/sh",
            "fs/etc/ssl/certs/ca-certificates.crt",
            "rootfs-manifest.json"
        )
        private val serverRequiredRelativePaths = listOf(
            "bootstrap-manifest.json",
            "server-source-manifest.json",
            "default/config.yaml",
            "dependency-env.sh",
            "dependency-post-extract.sh",
            "server.js",
            "package.json",
            "package-lock.json",
            "node_modules/archiver-utils/node_modules/glob/dist/commonjs/walker.js",
            "node_modules/archiver-utils/node_modules/glob/dist/commonjs/ignore.js"
        )
        // extractBootstrap 可能被多个协程并发调用（设置页 loadConfiguration + extensionsCoordinator.initialize），
        // 必须串行化以避免对同一目录的并发写入导致 AssertionError / IOException。
        private val extractLock = Any()
    }

    fun prepareWorkDirectories(paths: HostPaths) {
        synchronized(extractLock) {
            paths.ensureWorkingDirectories()
        }
    }

    fun prepareHostExtensionAssets(
        paths: HostPaths,
        onProgress: (details: String, progressPercent: Int) -> Unit = { _, _ -> }
    ) = synchronized(extractLock) {
        paths.ensureWorkingDirectories()
        val skippedAssetRoots = mutableSetOf(
            "${BootConfig.bootstrapAssetRoot}/rootfs",
            "${BootConfig.bootstrapAssetRoot}/server"
        )
        onProgress("正在写入 bootstrap 脚本、配置与内置扩展目录。", 50)
        copyNode(AssetCopySpec(BootConfig.bootstrapAssetRoot, paths.bootstrapRoot), skippedAssetRoots)
        onProgress("正在把 APK 内置的 host 扩展安装到用户 extensions 目录。", 80)
        // 即使本地 Tavern 服务已经在运行，APK 升级后的 host 扩展也必须覆盖到数据目录，否则 Web 会继续服务旧 JS/CSS。
        installBundledHostExtensions(paths, replaceExisting = true)
        onProgress("host 扩展资产已同步完成。", 100)
    }

    fun inspectRootfsAssets(paths: HostPaths): AssetPreparationInspection {
        return synchronized(extractLock) {
            inspectAssetDirectory(
                manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-manifest.json",
                installedManifestFile = File(paths.rootfsDir, "rootfs-manifest.json"),
                targetDirectory = paths.rootfsDir,
                requiredRelativePaths = rootfsRequiredRelativePaths
            )
        }
    }

    fun prepareRootfsAssets(
        paths: HostPaths,
        onProgress: (details: String, progressPercent: Int) -> Unit = { _, _ -> }
    ): Boolean = synchronized(extractLock) {
        paths.ensureWorkingDirectories()
        val rootfsDirectoryRefreshed = refreshAssetDirectoryIfNeeded(
            manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-manifest.json",
            installedManifestFile = File(paths.rootfsDir, "rootfs-manifest.json"),
            targetDirectory = paths.rootfsDir,
            replaceOnIncomplete = false,
            requiredRelativePaths = rootfsRequiredRelativePaths
        )
        if (!rootfsDirectoryRefreshed) {
            return@synchronized false
        }

        onProgress("首次启动时这里通常最慢，请稍等。", 10)
        var lastProgress = 10
        extractArchiveAsset(
            assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-fs.zip",
            targetDirectory = File(paths.rootfsDir, "fs"),
            shouldSetExecutable = { true },
            onProgress = { processedEntries, totalEntries ->
                val nextProgress = (10 + ((processedEntries.toDouble() / totalEntries.toDouble()) * 80.0).toInt())
                    .coerceIn(lastProgress, 90)
                if (nextProgress > lastProgress || processedEntries == totalEntries) {
                    lastProgress = nextProgress
                    onProgress("已处理 $processedEntries/$totalEntries 个 rootfs 条目。", nextProgress)
                }
            }
        )
        copyFile(
            AssetCopySpec(
                assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-manifest.json",
                targetPath = File(paths.rootfsDir, "rootfs-manifest.json")
            )
        )
        validatePreparedAssetDirectory(
            label = "rootfs 资产",
            targetDirectory = paths.rootfsDir,
            requiredRelativePaths = rootfsRequiredRelativePaths
        )
        onProgress("rootfs manifest 已同步完成。", 100)
        true
    }

    fun inspectServerAssets(paths: HostPaths): AssetPreparationInspection {
        return synchronized(extractLock) {
            val plan = inspectServerAssetRefreshPlan(paths)
            if (plan.anyChanged) {
                AssetPreparationInspection(
                    detection = plan.detection,
                    details = "Tavern source 或 dependency pack 需要覆盖同步。"
                )
            } else {
                AssetPreparationInspection(
                    detection = BootstrapStepDetection.UP_TO_DATE,
                    details = "Tavern source 与 dependency pack 均已是最新。"
                )
            }
        }
    }

    fun prepareServerAssets(
        paths: HostPaths,
        rootfsAssetsRefreshed: Boolean,
        onProgress: (details: String, progressPercent: Int) -> Unit = { _, _ -> }
    ): Boolean = synchronized(extractLock) {
        paths.ensureWorkingDirectories()
        val skippedAssetRoots = mutableSetOf(
            "${BootConfig.bootstrapAssetRoot}/rootfs",
            "${BootConfig.bootstrapAssetRoot}/server"
        )
        val serverRefreshPlan = inspectServerAssetRefreshPlan(paths)
        if (serverRefreshPlan.anyChanged) {
            onProgress("首次启动时这里会继续占用一些时间。", 10)
            var lastProgress = 10
            prepareServerArchives(paths, serverRefreshPlan) { processedEntries, totalEntries ->
                val nextProgress = (10 + ((processedEntries.toDouble() / totalEntries.toDouble()) * 55.0).toInt())
                    .coerceIn(lastProgress, 65)
                if (nextProgress > lastProgress || processedEntries == totalEntries) {
                    lastProgress = nextProgress
                    onProgress("已处理 $processedEntries/$totalEntries 个 Tavern 条目。", nextProgress)
                }
            }
        } else {
            onProgress("Tavern source 与 dependency pack 已是最新，继续校正依赖包权限。", 65)
        }

        onProgress("正在执行通用 dependency post-extract hook。", 72)
        runServerPostExtractHook(paths)
        onProgress("正在写入 bootstrap 脚本、配置与内置扩展目录。", 80)
        prepareHostExtensionAssets(paths) { details, progressPercent ->
            onProgress(details, 80 + ((progressPercent.coerceIn(0, 100) * 8) / 100))
        }
        onProgress("正在准备 usr、tmp 与运行时依赖目录。", 94)
        refreshHostPrefixDirectory(paths, rootfsAssetsRefreshed)
        validatePreparedAssetDirectory(
            label = "Tavern 资产",
            targetDirectory = paths.serverDir,
            requiredRelativePaths = serverRequiredRelativePaths
        )
        onProgress("server source、依赖包、内置扩展与宿主目录已同步完成。", 100)
        serverRefreshPlan.anyChanged
    }

    /**
     * 设置页终端只需要进入现有 Termux host 环境并附着一个交互 shell，
     * 不能为了懒初始化 console 而复用 bootstrap service 的启动/重启语义。
     */
    fun prepareConsoleAssets(
        paths: HostPaths,
        onProgress: (message: String, details: String, progressPercent: Int) -> Unit = { _, _, _ -> }
    ) = synchronized(extractLock) {
        onProgress(
            "正在准备终端运行时目录。",
            "正在创建 bootstrap、data 和日志目录。",
            5
        )
        prepareWorkDirectories(paths)

        onProgress(
            "正在检查 Linux rootfs 资产。",
            "终端首次进入时需要先确认 rootfs 是否已解包完成。",
            12
        )
        val rootfsDirectoryRefreshed = prepareRootfsAssets(paths) { details, progressPercent ->
            onProgress(
                "正在准备 Linux rootfs。",
                details,
                12 + ((progressPercent.coerceIn(0, 100) * 28) / 100)
            )
        }

        onProgress(
            "正在检查 Tavern server 资产。",
            "终端默认工作目录固定为 /tavern/server，需要先保证 server source 与依赖包就绪。",
            44
        )
        prepareServerAssets(paths, rootfsDirectoryRefreshed) { details, progressPercent ->
            onProgress(
                "正在准备 Tavern server 资产。",
                details,
                44 + ((progressPercent.coerceIn(0, 100) * 44) / 100)
            )
        }

        BootstrapLayoutVerifier(paths).verify()
        AndroidDnsConfigWriter(context).write(paths)
        onProgress(
            "终端运行时已准备完成。",
            "rootfs、server source、host prefix 与 DNS 配置已经同步完成。",
            100
        )
    }

    fun extractBootstrap(
        paths: HostPaths,
        onProgress: (message: String, details: String, progressPercent: Int) -> Unit = { _, _, _ -> }
    ) = synchronized(extractLock) {
        onProgress(
            "正在准备宿主工作目录。",
            "正在创建 bootstrap、data 和日志目录。",
            5
        )
        paths.ensureWorkingDirectories()
        val skippedAssetRoots = mutableSetOf(
            "${BootConfig.bootstrapAssetRoot}/rootfs",
            "${BootConfig.bootstrapAssetRoot}/server"
        )
        onProgress(
            "正在检查 Linux rootfs 资产。",
            "正在比较当前设备上的 rootfs manifest。",
            10
        )
        val rootfsDirectoryRefreshed = refreshAssetDirectoryIfNeeded(
            manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-manifest.json",
            installedManifestFile = File(paths.rootfsDir, "rootfs-manifest.json"),
            targetDirectory = paths.rootfsDir,
            replaceOnIncomplete = false,
            requiredRelativePaths = rootfsRequiredRelativePaths
        )
        if (rootfsDirectoryRefreshed) {
            onProgress(
                "正在解包 Linux rootfs。",
                "首次启动时这里通常最慢，请稍等。",
                18
            )
            var lastRootfsProgress = 18
            extractArchiveAsset(
                assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-fs.zip",
                targetDirectory = File(paths.rootfsDir, "fs"),
                shouldSetExecutable = { true },
                onProgress = { processedEntries, totalEntries ->
                    val nextProgress = (18 + ((processedEntries.toDouble() / totalEntries.toDouble()) * 18.0).toInt())
                        .coerceIn(lastRootfsProgress, 36)
                    if (nextProgress > lastRootfsProgress || processedEntries == totalEntries) {
                        lastRootfsProgress = nextProgress
                        onProgress(
                            "正在解包 Linux rootfs。",
                            "已处理 $processedEntries/$totalEntries 个 rootfs 条目。",
                            nextProgress
                        )
                    }
                }
            )
            copyFile(
                AssetCopySpec(
                    assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-manifest.json",
                    targetPath = File(paths.rootfsDir, "rootfs-manifest.json")
                )
            )
            onProgress(
                "Linux rootfs 已准备完成。",
                "rootfs manifest 已同步完成。",
                40
            )
        } else {
            onProgress(
                "Linux rootfs 已是最新。",
                "跳过 rootfs 解包。",
                40
            )
        }

        onProgress(
            "正在检查 Tavern server 资产。",
            "正在分别比较当前设备上的 Tavern source 与 dependency manifest。",
            45
        )
        val serverRefreshPlan = inspectServerAssetRefreshPlan(paths)
        if (serverRefreshPlan.anyChanged) {
            onProgress(
                "正在解包 Tavern server 与 Node runtime。",
                "首次启动时这里会继续占用一些时间。",
                48
            )
            var lastServerProgress = 48
            prepareServerArchives(paths, serverRefreshPlan) { processedEntries, totalEntries ->
                val nextProgress = (48 + ((processedEntries.toDouble() / totalEntries.toDouble()) * 20.0).toInt())
                    .coerceIn(lastServerProgress, 68)
                if (nextProgress > lastServerProgress || processedEntries == totalEntries) {
                    lastServerProgress = nextProgress
                    onProgress(
                        "正在解包 Tavern server 与 Node runtime。",
                        "已处理 $processedEntries/$totalEntries 个 Tavern 条目。",
                        nextProgress
                    )
                }
            }
        } else {
            onProgress(
                "Tavern server 资产已是最新。",
                "跳过 Tavern source 与 dependency pack 解包，继续校正依赖包权限。",
                68
            )
        }

        onProgress(
            "正在校正 Tavern server 权限。",
            "正在执行通用 dependency post-extract hook。",
            70
        )
        runServerPostExtractHook(paths)
        onProgress(
            "Tavern server 资产已准备完成。",
            "server source manifest 与依赖包权限已同步完成。",
            72
        )

        onProgress(
            "正在同步启动脚本与内置扩展资产。",
            "正在写入 bootstrap 脚本、配置与内置扩展目录。",
            74
        )
        prepareHostExtensionAssets(paths) { details, progressPercent ->
            onProgress(
                "正在同步宿主内置扩展。",
                details,
                74 + ((progressPercent.coerceIn(0, 100) * 2) / 100)
            )
        }
        onProgress(
            "正在刷新 Linux 宿主目录。",
            "正在准备 usr、tmp 与运行时依赖目录。",
            80
        )
        refreshHostPrefixDirectory(paths, rootfsDirectoryRefreshed)
        validatePreparedAssetDirectory(
            label = "rootfs 资产",
            targetDirectory = paths.rootfsDir,
            requiredRelativePaths = rootfsRequiredRelativePaths
        )
        validatePreparedAssetDirectory(
            label = "Tavern 资产",
            targetDirectory = paths.serverDir,
            requiredRelativePaths = serverRequiredRelativePaths
        )
    }

    private fun inspectAssetDirectory(
        manifestAssetPath: String,
        installedManifestFile: File,
        targetDirectory: File,
        requiredRelativePaths: List<String>,
        requiredExecutableRelativePaths: List<String> = emptyList()
    ): AssetPreparationInspection {
        if (!installedManifestFile.isFile) {
            return AssetPreparationInspection(
                detection = BootstrapStepDetection.MISSING,
                details = "未检测到已安装 manifest，需要重新准备资产。"
            )
        }

        if (!assetContentMatchesFile(manifestAssetPath, installedManifestFile)) {
            return AssetPreparationInspection(
                detection = BootstrapStepDetection.OUTDATED,
                details = "已安装 manifest 与 APK 内资产不一致，需要重新同步。"
            )
        }

        if (!targetDirectory.containsRequiredFiles(requiredRelativePaths)) {
            return AssetPreparationInspection(
                detection = BootstrapStepDetection.INCOMPLETE,
                details = "目标目录缺少关键文件，需要补齐。"
            )
        }

        if (!targetDirectory.containsExecutableFiles(requiredExecutableRelativePaths)) {
            return AssetPreparationInspection(
                detection = BootstrapStepDetection.INCOMPLETE,
                details = "目标目录缺少可执行权限，需要补齐。"
            )
        }

        return AssetPreparationInspection(
            detection = BootstrapStepDetection.UP_TO_DATE,
            details = "当前设备上的离线资产已是最新。"
        )
    }

    private fun refreshAssetDirectoryIfNeeded(
        manifestAssetPath: String,
        installedManifestFile: File,
        targetDirectory: File,
        replaceOnIncomplete: Boolean,
        requiredRelativePaths: List<String>,
        requiredExecutableRelativePaths: List<String> = emptyList()
    ): Boolean {
        if (installedManifestFile.isFile && assetContentMatchesFile(manifestAssetPath, installedManifestFile)) {
            if (
                targetDirectory.containsRequiredFiles(requiredRelativePaths) &&
                targetDirectory.containsExecutableFiles(requiredExecutableRelativePaths)
            ) {
                return false
            }

            if (!replaceOnIncomplete) {
                targetDirectory.mkdirs()
                return true
            }
        }

        deleteRecursivelyWithoutFollowingSymlinks(targetDirectory)
        targetDirectory.mkdirs()
        return true
    }

    private fun inspectServerAssetRefreshPlan(paths: HostPaths): ServerAssetRefreshPlan {
        val sourceManifestAssetPath = "${BootConfig.bootstrapAssetRoot}/server/server-source-manifest.json"
        val dependencySelectionAssetPath = "${BootConfig.bootstrapAssetRoot}/server/dependency-selection.json"
        val installedSourceManifestFile = File(paths.serverDir, "server-source-manifest.json")
        val installedDependencySelectionFile = File(paths.serverDir, "dependency-selection.json")
        val sourceMissing = !installedSourceManifestFile.isFile
        val dependencyMissing = !installedDependencySelectionFile.isFile
        val sourceChanged = sourceMissing || !assetContentMatchesFile(sourceManifestAssetPath, installedSourceManifestFile)
        val dependenciesChanged = dependencyMissing ||
            !assetContentMatchesFile(dependencySelectionAssetPath, installedDependencySelectionFile)
        val incomplete = !paths.serverDir.containsRequiredFiles(serverRequiredRelativePaths)
        val detection = when {
            sourceMissing || dependencyMissing -> BootstrapStepDetection.MISSING
            sourceChanged || dependenciesChanged -> BootstrapStepDetection.OUTDATED
            incomplete -> BootstrapStepDetection.INCOMPLETE
            else -> BootstrapStepDetection.UP_TO_DATE
        }

        return ServerAssetRefreshPlan(
            sourceChanged = sourceChanged || incomplete,
            dependenciesChanged = dependenciesChanged || incomplete,
            detection = detection
        )
    }

    private fun prepareServerArchives(
        paths: HostPaths,
        refreshPlan: ServerAssetRefreshPlan,
        onProgress: (processedEntries: Int, totalEntries: Int) -> Unit
    ) {
        paths.serverDir.mkdirs()
        var processedOffset = 0
        val sourceEntries = if (refreshPlan.sourceChanged) {
            countArchiveEntries(resolveServerSourceArchiveAssetPath())
        } else {
            0
        }
        val dependencyArchives = if (refreshPlan.dependenciesChanged) {
            resolveDependencyPackArchiveAssetPaths()
        } else {
            emptyList()
        }
        val dependencyEntryCounts = dependencyArchives.associateWith { assetPath -> countArchiveEntries(assetPath) }
        val totalEntries = (sourceEntries + dependencyEntryCounts.values.sum()).coerceAtLeast(1)

        if (refreshPlan.sourceChanged) {
            // Tavern 官方升级是覆盖式更新；这里不清 serverDir，避免误删用户缓存、插件自建目录或持久数据入口。
            extractArchiveAsset(
                assetPath = resolveServerSourceArchiveAssetPath(),
                targetDirectory = paths.serverDir,
                shouldSetExecutable = { relativePath -> relativePath.endsWith(".sh", ignoreCase = true) },
                clearTargetDirectory = false,
                onProgress = { processedEntries, _ ->
                    onProgress(processedOffset + processedEntries, totalEntries)
                }
            )
            processedOffset += sourceEntries
            patchAndroidDefaultServerConfig(paths.serverDir)
        }

        dependencyArchives.forEach { assetPath ->
            extractArchiveAsset(
                assetPath = assetPath,
                targetDirectory = paths.serverDir,
                shouldSetExecutable = { relativePath ->
                    relativePath.endsWith(".sh", ignoreCase = true) ||
                        relativePath.startsWith("bin/") ||
                        relativePath.startsWith("libexec/")
                },
                clearTargetDirectory = false,
                onProgress = { processedEntries, _ ->
                    onProgress(processedOffset + processedEntries, totalEntries)
                }
            )
            processedOffset += dependencyEntryCounts.getValue(assetPath)
        }

        copyServerBootstrapFiles(paths)
    }

    private fun copyServerBootstrapFiles(paths: HostPaths) {
        listOf(
            "bootstrap-manifest.json",
            "server-source-manifest.json",
            "dependency-selection.json",
            "dependency-env.sh",
            "dependency-post-extract.sh"
        ).forEach { fileName ->
            copyFile(
                AssetCopySpec(
                    assetPath = "${BootConfig.bootstrapAssetRoot}/server/$fileName",
                    targetPath = File(paths.serverDir, fileName)
                )
            )
        }
    }

    private fun assetContentMatchesFile(assetPath: String, file: File): Boolean {
        return runCatching {
            if (shouldIgnoreVolatileManifestFields(assetPath, file)) {
                context.assets.open(assetPath).bufferedReader().use { assetReader ->
                    val assetManifest = normalizeBootstrapManifestForComparison(assetReader.readText())
                    val installedManifest = normalizeBootstrapManifestForComparison(file.readText())
                    assetManifest == installedManifest
                }
            } else {
                context.assets.open(assetPath).use { assetInput ->
                    digestOf(assetInput) == digestOf(file)
                }
            }
        }.getOrDefault(false)
    }

    /**
     * APK 里的 rootfs/server manifest 会带 syncedAtUtc 这种每次打包都变化的时间戳。
     * 如果直接按整文件 hash 对比，就会把“内容没变、只是重打了一个包”误判成资产过期，
     * 进而删掉整套 rootfs/server 目录重新解包，破坏用户在 guest 环境里手动做过的修改。
     */
    private fun shouldIgnoreVolatileManifestFields(assetPath: String, file: File): Boolean {
        if (!file.name.equals("rootfs-manifest.json", ignoreCase = true) &&
            !file.name.equals("bootstrap-manifest.json", ignoreCase = true)
        ) {
            return false
        }

        return assetPath.endsWith("rootfs-manifest.json") || assetPath.endsWith("bootstrap-manifest.json")
    }

    private fun digestOf(file: File): String {
        file.inputStream().buffered().use { fileInput ->
            return digestOf(fileInput)
        }
    }

    private fun digestOf(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read)
            }
            read = input.read(buffer)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun File.containsRequiredFiles(requiredRelativePaths: List<String>): Boolean {
        return requiredRelativePaths.all { relativePath ->
            File(this, relativePath).exists()
        }
    }

    private fun resolveServerSourceArchiveAssetPath(): String {
        val manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/server/bootstrap-manifest.json"
        val archiveFile = runCatching {
            context.assets.open(manifestAssetPath).bufferedReader().use { reader ->
                JSONObject(reader.readText())
                    .optJSONObject("serverSource")
                    ?.optString("archiveFile")
                    ?.trim()
                    .orEmpty()
            }
        }.getOrDefault("")

        return if (archiveFile.isBlank()) {
            "${BootConfig.bootstrapAssetRoot}/server/server-source.zip"
        } else {
            "${BootConfig.bootstrapAssetRoot}/server/$archiveFile"
        }
    }

    private fun resolveDependencyPackArchiveAssetPaths(): List<String> {
        val selectionAssetPath = "${BootConfig.bootstrapAssetRoot}/server/dependency-selection.json"
        return runCatching {
            context.assets.open(selectionAssetPath).bufferedReader().use { reader ->
                val selected = JSONObject(reader.readText()).optJSONArray("selected") ?: return@use emptyList()
                buildList {
                    for (index in 0 until selected.length()) {
                        val archiveFile = selected.optJSONObject(index)
                            ?.optString("archiveFile")
                            ?.trim()
                            .orEmpty()
                        if (archiveFile.isNotBlank()) {
                            add("${BootConfig.bootstrapAssetRoot}/server/dependency-packs/$archiveFile")
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun patchAndroidDefaultServerConfig(serverDir: File) {
        val configFile = File(serverDir, "default/config.yaml")
        if (!configFile.isFile) {
            return
        }

        val patchedLines = configFile.readLines().toMutableList()
        var section: String? = null
        patchedLines.indices.forEach { index ->
            val line = patchedLines[index]
            val stripped = line.trim()
            when {
                stripped == "browserLaunch:" -> section = "browserLaunch"
                stripped == "git:" -> section = "git"
                section == "browserLaunch" && stripped.matches(Regex("""enabled:\s*(true|false)\b.*""")) -> {
                    patchedLines[index] = line.replace(Regex("""(enabled:\s*)(true|false)\b"""), "\$1false")
                    section = null
                }
                section == "git" && stripped.matches(Regex("""backend:\s*\S+.*""")) -> {
                    patchedLines[index] = line.replace(Regex("""(backend:\s*)\S+"""), "\$1builtin")
                    section = null
                }
                stripped.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("#") -> {
                    section = null
                }
            }
        }
        configFile.writeText(patchedLines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun File.containsExecutableFiles(requiredRelativePaths: List<String>): Boolean {
        return requiredRelativePaths.all { relativePath ->
            File(this, relativePath).canExecute()
        }
    }

    private fun extractArchiveAsset(
        assetPath: String,
        targetDirectory: File,
        shouldSetExecutable: (String) -> Boolean,
        symlinkManifestRelativePath: String? = null,
        clearTargetDirectory: Boolean = true,
        onProgress: (processedEntries: Int, totalEntries: Int) -> Unit = { _, _ -> }
    ) {
        if (clearTargetDirectory) {
            deleteRecursivelyWithoutFollowingSymlinks(targetDirectory)
        }
        targetDirectory.mkdirs()

        val targetRootPath = targetDirectory.toPath().toAbsolutePath().normalize()
        val totalEntries = countArchiveEntries(assetPath)
        var processedEntries = 0

        context.assets.open(assetPath).use { input ->
            ZipInputStream(input.buffered()).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val relativePath = entry.name.removePrefix("./").trimStart('/')
                    if (relativePath.isBlank()) {
                        zipInput.closeEntry()
                        continue
                    }

                    val outputPath = targetRootPath.resolve(relativePath).normalize()
                    if (outputPath != targetRootPath && !outputPath.startsWith(targetRootPath)) {
                        throw BootstrapException(BootstrapError.ArchiveCorrupted("bootstrap 归档包含非法路径：$relativePath"))
                    }
                    val outputFile = outputPath.toFile()

                    if (entry.isDirectory) {
                        if (outputFile.existsWithoutFollowingSymlinks() && !outputFile.isDirectory) {
                            deleteRecursivelyWithoutFollowingSymlinks(outputFile)
                        }
                        if (!outputFile.existsWithoutFollowingSymlinks() && !outputFile.mkdirs()) {
                            throw BootstrapException("创建目录失败：$relativePath")
                        }
                    } else {
                        val parentDirectory = outputFile.parentFile
                        if (parentDirectory != null) {
                            if (parentDirectory.existsWithoutFollowingSymlinks() && !parentDirectory.isDirectory) {
                                deleteRecursivelyWithoutFollowingSymlinks(parentDirectory)
                            }
                            if (!parentDirectory.existsWithoutFollowingSymlinks() && !parentDirectory.mkdirs()) {
                                throw BootstrapException("创建文件父目录失败：$relativePath")
                            }
                        }
                        outputFile.outputStream().use { output ->
                            zipInput.copyTo(output)
                        }
                        if (shouldSetExecutable(relativePath)) {
                            outputFile.setExecutable(true)
                        }
                    }

                    processedEntries += 1
                    onProgress(processedEntries, totalEntries)

                    zipInput.closeEntry()
                }
            }
        }

        if (processedEntries <= 0) {
            throw BootstrapException(BootstrapError.ArchiveCorrupted("bootstrap 归档为空或格式无效：$assetPath"))
        }

        if (symlinkManifestRelativePath != null) {
            applySymlinkManifest(targetDirectory, File(targetDirectory, symlinkManifestRelativePath))
        }
    }

    private fun countArchiveEntries(assetPath: String): Int {
        var totalEntries = 0
        context.assets.open(assetPath).use { input ->
            ZipInputStream(input.buffered()).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val relativePath = entry.name.removePrefix("./").trimStart('/')
                    if (relativePath.isNotBlank()) {
                        totalEntries += 1
                    }
                    zipInput.closeEntry()
                }
            }
        }
        return totalEntries.coerceAtLeast(1)
    }

    private fun applySymlinkManifest(targetDirectory: File, manifestFile: File) {
        if (!manifestFile.isFile) {
            return
        }

        val canonicalTargetDirectory = targetDirectory.canonicalFile
        val canonicalTargetPrefix = canonicalTargetDirectory.path + File.separator

        manifestFile.bufferedReader().useLines { lines ->
            lines.filter { line -> line.isNotBlank() }.forEach { line ->
                val parts = line.split('←', limit = 2)
                if (parts.size != 2) {
                    throw BootstrapException(BootstrapError.ArchiveCorrupted("bootstrap symlink manifest 格式非法：$line"))
                }

                val linkTarget = parts[0].trim()
                val relativePath = parts[1].trim().removePrefix("./").trimStart('/')
                if (linkTarget.isBlank() || relativePath.isBlank()) {
                    throw BootstrapException(BootstrapError.ArchiveCorrupted("bootstrap symlink manifest 包含空路径：$line"))
                }

                val linkFile = File(targetDirectory, relativePath)
                linkFile.parentFile?.mkdirs()

                val canonicalLinkPath = linkFile.canonicalFile.path
                if (canonicalLinkPath != canonicalTargetDirectory.path && !canonicalLinkPath.startsWith(canonicalTargetPrefix)) {
                    throw BootstrapException(BootstrapError.ArchiveCorrupted("bootstrap symlink 路径非法：$relativePath"))
                }

                deleteRecursivelyWithoutFollowingSymlinks(linkFile)

                try {
                    Os.symlink(linkTarget, linkFile.absolutePath)
                } catch (error: ErrnoException) {
                    throw BootstrapException("创建 bootstrap symlink 失败：$relativePath -> $linkTarget (${error.message})")
                }
            }
        }

        manifestFile.delete()
    }

    private fun validatePreparedAssetDirectory(
        label: String,
        targetDirectory: File,
        requiredRelativePaths: List<String>
    ) {
        val missingRelativePaths = requiredRelativePaths.filter { relativePath ->
            !File(targetDirectory, relativePath).exists()
        }
        if (missingRelativePaths.isNotEmpty()) {
            throw BootstrapException(
                "$label 缺少必要文件：${missingRelativePaths.joinToString(separator = ", ")}"
            )
        }
    }

    private fun runServerPostExtractHook(paths: HostPaths) {
        val hookFile = File(paths.serverDir, "dependency-post-extract.sh")
        if (!hookFile.isFile) {
            return
        }

        val process = ProcessBuilder("/system/bin/sh", hookFile.absolutePath)
            .directory(paths.serverDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { reader ->
            reader.readText().trim()
        }

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw BootstrapException(BootstrapError.PostExtractHookFailed("dependency post-extract hook 执行超时：${hookFile.absolutePath}"))
        }

        if (process.exitValue() != 0) {
            val details = if (output.isBlank()) {
                "退出码 ${process.exitValue()}"
            } else {
                output
            }
            throw BootstrapException(BootstrapError.PostExtractHookFailed("dependency post-extract hook 执行失败：$details"))
        }
    }

    private fun installBundledHostExtensions(paths: HostPaths, replaceExisting: Boolean) {
        val bundledExtensionsRoot = File(paths.bootstrapRoot, "bundled-extensions")
        if (!bundledExtensionsRoot.isDirectory) {
            return
        }

        val targetExtensionsRoot = File(paths.serverDataDir, "extensions")
        targetExtensionsRoot.mkdirs()

        bundledExtensionsRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .filter { sourceDirectory ->
                val manifestFile = File(sourceDirectory, "manifest.json")
                if (!manifestFile.isFile) {
                    return@filter false
                }

                runCatching {
                    JSONObject(manifestFile.readText()).optString("sillydroid_bundle_category")
                }.getOrNull().equals("host", ignoreCase = true)
            }
            .forEach { sourceDirectory ->
                val targetDirectory = File(targetExtensionsRoot, sourceDirectory.name)
                if (targetDirectory.existsWithoutFollowingSymlinks() && !replaceExisting) {
                    return@forEach
                }

                deleteRecursivelyWithoutFollowingSymlinks(targetDirectory)
                copyBundledHostExtensionSafely(sourceDirectory, targetDirectory)
            }
    }

    private fun copyBundledHostExtensionSafely(sourceDirectory: File, targetDirectory: File) {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val stageDirectory = File(
                targetDirectory.parentFile,
                "${targetDirectory.name}.sillydroid-stage-${System.currentTimeMillis()}-$attempt"
            )
            try {
                deleteRecursivelyWithoutFollowingSymlinks(stageDirectory)
                if (!sourceDirectory.copyRecursively(stageDirectory, overwrite = true)) {
                    throw IOException("copyRecursively returned false")
                }

                val sourceManifest = File(sourceDirectory, "manifest.json")
                val stageManifest = File(stageDirectory, "manifest.json")
                if (!stageManifest.isFile) {
                    throw IOException("staged manifest.json missing")
                }
                if (sourceManifest.isFile && sourceManifest.length() != stageManifest.length()) {
                    throw IOException("staged manifest size mismatch")
                }

                deleteRecursivelyWithoutFollowingSymlinks(targetDirectory)
                if (!stageDirectory.renameTo(targetDirectory)) {
                    if (!stageDirectory.copyRecursively(targetDirectory, overwrite = true)) {
                        throw IOException("failed to promote staged extension")
                    }
                    deleteRecursivelyWithoutFollowingSymlinks(stageDirectory)
                }
                return
            } catch (error: Throwable) {
                lastError = error
                deleteRecursivelyWithoutFollowingSymlinks(stageDirectory)
            }
        }

        throw IOException("install bundled host extension failed", lastError)
    }

    private fun copyNode(spec: AssetCopySpec, skippedAssetRoots: Set<String>) {
        if (spec.assetPath in skippedAssetRoots) {
            return
        }

        val entries = context.assets.list(spec.assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            copyFile(spec)
            return
        }

        if (!spec.targetPath.exists()) {
            spec.targetPath.mkdirs()
        }

        entries.forEach { child ->
            copyNode(spec.child(child), skippedAssetRoots)
        }
    }

    private fun copyFile(spec: AssetCopySpec) {
        if (spec.shouldPreserveExistingRootfsFile() && spec.targetPath.exists()) {
            return
        }

        spec.targetPath.parentFile?.mkdirs()
        context.assets.open(spec.assetPath).use { input ->
            spec.targetPath.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (spec.shouldSetExecutable()) {
            spec.targetPath.setExecutable(true)
        }
    }

    private fun refreshHostPrefixDirectory(paths: HostPaths, rootfsDirectoryRefreshed: Boolean) {
        val hostPrefixRequiredFiles = listOf(
            "etc/tls/cert.pem",
            "lib/node_modules/corepack/dist/npm.js",
            "libexec/git-core/git",
            "libexec/git-core/git-remote-http"
        )
        val hostPrefixExecutableFiles = listOf(
            "libexec/git-core/git"
        )
        val shouldRefreshHostPrefix = rootfsDirectoryRefreshed ||
            !paths.hostPrefixDir.containsRequiredFiles(hostPrefixRequiredFiles) ||
            !paths.hostPrefixDir.containsExecutableFiles(hostPrefixExecutableFiles)
        if (!shouldRefreshHostPrefix) {
            paths.hostTmpDir.mkdirs()
            return
        }

        deleteRecursivelyWithoutFollowingSymlinks(paths.hostPrefixDir)

        extractArchiveAsset(
            assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-usr.zip",
            targetDirectory = paths.hostPrefixDir,
            shouldSetExecutable = { relativePath ->
                relativePath.endsWith(".sh", ignoreCase = true) ||
                    relativePath.startsWith("bin/") ||
                    relativePath.startsWith("libexec/")
            },
            symlinkManifestRelativePath = "SYMLINKS.txt"
        )
        paths.hostTmpDir.mkdirs()
    }
}

data class AssetCopySpec(
    val assetPath: String,
    val targetPath: File
) {
    fun child(name: String): AssetCopySpec {
        return AssetCopySpec(
            assetPath = "$assetPath/$name",
            targetPath = File(targetPath, name)
        )
    }

    fun shouldPreserveExistingRootfsFile(): Boolean {
        return assetPath.startsWith("${BootConfig.bootstrapAssetRoot}/rootfs/fs/")
    }

    fun shouldSetExecutable(): Boolean {
        return shouldPreserveExistingRootfsFile() ||
            targetPath.extension.equals("sh", ignoreCase = true) ||
            targetPath.name == "tavern-entrypoint.sh"
    }
}

class BootstrapLayoutVerifier(private val paths: HostPaths) {
    fun verify() {
        val missingEntries = requiredEntries()
            .filterNot { (_, file) -> file.exists() }
            .map { (displayPath, _) -> displayPath }

        if (missingEntries.isNotEmpty()) {
            throw BootstrapException(
                "bootstrap 资产缺少关键文件：${missingEntries.joinToString()}。请先同步 android-tavern 下的离线运行时、server source 与依赖包产物。"
            )
        }
    }

    private fun requiredEntries(): List<Pair<String, File>> {
        return listOf(
            "scripts/ensure-rootfs-runtime.sh" to File(paths.scriptsDir, "ensure-rootfs-runtime.sh"),
            "scripts/start-server.sh" to File(paths.scriptsDir, "start-server.sh"),
            "scripts/tavern-entrypoint.sh" to File(paths.scriptsDir, "tavern-entrypoint.sh"),
            "scripts/termux-host-runtime.sh" to File(paths.scriptsDir, "termux-host-runtime.sh"),
            "rootfs/fs/bin/sh" to File(paths.rootfsDir, "fs/bin/sh"),
            "rootfs/rootfs-manifest.json" to File(paths.rootfsDir, "rootfs-manifest.json"),
            "hostRuntimeDir/libtermux-node.so" to paths.hostTermuxNodeBinary,
            "hostRuntimeDir/libtermux-git.so" to paths.hostTermuxGitBinary,
            "hostRuntimeDir/libtermux-git-remote-http.so" to paths.hostTermuxGitRemoteHttpBinary,
            "hostRuntimeDir/libtermux-sh.so" to paths.hostTermuxShellBinary,
            "server/bootstrap-manifest.json" to File(paths.serverDir, "bootstrap-manifest.json"),
            "server/dependency-env.sh" to File(paths.serverDir, "dependency-env.sh"),
            "server/dependency-post-extract.sh" to File(paths.serverDir, "dependency-post-extract.sh")
        )
    }
}

class AndroidDnsConfigWriter(private val context: Context) {
    fun write(paths: HostPaths) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("Android 无法获取 ConnectivityManager，无法为 rootfs 生成 DNS 配置。")
        val dnsServers = connectivityManager.activeNetwork
            ?.let { activeNetwork ->
                connectivityManager.getLinkProperties(activeNetwork)
                    ?.dnsServers
                    ?.mapNotNull { address -> address.hostAddress }
                    ?.distinct()
            }
            .orEmpty()

        val resolvConf = File(paths.rootfsDir, "android-resolv.conf")
        val resolvConfContent = if (dnsServers.isEmpty()) {
            ""
        } else {
            dnsServers.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()) { dnsServer ->
                "nameserver $dnsServer"
            }
        }
        resolvConf.writeText(resolvConfContent)
    }
}

class BootstrapException(
    val error: BootstrapError,
    cause: Throwable? = null
) : IllegalStateException(error.message, cause) {
    constructor(message: String) : this(BootstrapError.Generic(message))
}

data class LaunchRequest(
    val name: String,
    val scriptFile: File,
    val workingDirectory: File,
    val environment: Map<String, String>,
    val logFileName: String = "$name.log"
)

class ManagedProcess(
    val name: String,
    private val process: Process
) {
    fun isAlive(): Boolean {
        return process.isAlive
    }

    fun waitFor(): Int {
        return process.waitFor()
    }

    fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        return process.waitFor(timeout, unit)
    }

    fun stop() {
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
    }
}

object ServerProcessJanitor {
    suspend fun cleanupLingeringServerProcesses(): Int {
        val processes = listOwnedProcesses()
        if (processes.isEmpty()) {
            return 0
        }

        val byParentPid = processes.groupBy { it.ppid }
        val pidsToKill = linkedSetOf<Int>()

        fun collectProcessTree(rootPid: Int) {
            if (!pidsToKill.add(rootPid)) {
                return
            }

            for (child in byParentPid[rootPid].orEmpty()) {
                collectProcessTree(child.pid)
            }
        }

        for (process in processes) {
            if (process.isBootstrapServerProcess()) {
                collectProcessTree(process.pid)
            }
        }

        if (pidsToKill.isEmpty()) {
            return 0
        }

        sendSignal(pidsToKill, "TERM")
        delay(150)

        val remainingPids = pidsToKill.filter { pid -> File("/proc/$pid").exists() }
        if (remainingPids.isNotEmpty()) {
            sendSignal(remainingPids, "KILL")
            delay(100)
        }

        return pidsToKill.size
    }

    private fun sendSignal(pids: Iterable<Int>, signal: String) {
        for (pid in pids.toSet().sortedDescending()) {
            runCatching {
                ProcessBuilder("/system/bin/sh", "-c", "kill -$signal $pid")
                    .start()
                    .waitFor(1, TimeUnit.SECONDS)
            }
        }
    }
}

open class LinuxRuntimeLauncher(private val paths: HostPaths) {
    open fun start(request: LaunchRequest): ManagedProcess {
        if (!request.scriptFile.exists()) {
            throw BootstrapException("启动脚本不存在：${request.scriptFile.absolutePath}")
        }

        if (!paths.hostTermuxNodeBinary.exists()) {
            throw BootstrapException("缺少 Termux Node 入口：${paths.hostTermuxNodeBinary.absolutePath}")
        }

        if (!paths.hostTermuxGitBinary.exists()) {
            throw BootstrapException("缺少 Termux Git 入口：${paths.hostTermuxGitBinary.absolutePath}")
        }

        if (!paths.hostTermuxGitRemoteHttpBinary.exists()) {
            throw BootstrapException("缺少 Termux Git HTTPS helper 入口：${paths.hostTermuxGitRemoteHttpBinary.absolutePath}")
        }

        if (!paths.hostTermuxShellBinary.exists()) {
            throw BootstrapException("缺少 Termux shell 入口：${paths.hostTermuxShellBinary.absolutePath}")
        }

        if (!paths.hostLibDir.exists()) {
            throw BootstrapException("缺少 host runtime 依赖目录：${paths.hostLibDir.absolutePath}")
        }

        request.scriptFile.setExecutable(true)

        val logFile = File(paths.logsDir, request.logFileName)
        logFile.parentFile?.mkdirs()

        val processBuilder = ProcessBuilder("/system/bin/sh", request.scriptFile.absolutePath)
            .directory(request.workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        val environment = processBuilder.environment()
        environment.putAll(request.environment)
        environment.putAll(buildHostRuntimeEnvironment(paths))

        return ManagedProcess(request.name, processBuilder.start())
    }
}

class RootfsRuntimeProvisioner(
    private val launcher: LinuxRuntimeLauncher,
    private val paths: HostPaths
) {
    fun ensure(
        logFileName: String,
        onAttemptLog: (String) -> Unit = {},
        onHeartbeat: (elapsedSeconds: Int) -> Unit = {}
    ): RootfsRuntimeEnsureResult {
        val runtimeMode = readInstalledRuntimeMode(paths)
        val attempt = runAttempt(
            logFileName = logFileName,
            onHeartbeat = onHeartbeat
        )
        onAttemptLog(attempt.toStartupLogLine(runtimeMode))
        if (attempt.succeeded) {
            onAttemptLog("rootfs-runtime selected runtimeMode=$runtimeMode for this bootstrap session.")
            return RootfsRuntimeEnsureResult(
                runtimeMode = runtimeMode
            )
        }

        throw IllegalStateException(buildRootfsRuntimeFailureMessage(attempt, runtimeMode))
    }

    private fun runAttempt(
        logFileName: String,
        onHeartbeat: (elapsedSeconds: Int) -> Unit
    ): RootfsRuntimeAttemptResult {
        val request = LaunchRequest(
            name = "rootfs-runtime",
            scriptFile = File(paths.scriptsDir, "ensure-rootfs-runtime.sh"),
            workingDirectory = paths.bootstrapRoot,
            environment = emptyMap(),
            logFileName = logFileName
        )
        val logFile = File(paths.logsDir, logFileName)
        val logStartOffset = logFile.length().coerceAtLeast(0L)
        val process = launcher.start(request)
        var elapsedSeconds = 0
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            elapsedSeconds += 1
            onHeartbeat(elapsedSeconds)
        }
        val exitCode = process.waitFor()
        return RootfsRuntimeAttemptResult(
            exitCode = exitCode,
            logExcerpt = readLogExcerpt(logFile, startOffset = logStartOffset)
        )
    }

    private fun buildRootfsRuntimeFailureMessage(attempt: RootfsRuntimeAttemptResult, runtimeMode: String): String {
        return buildString {
            append("Linux 离线运行时校验失败。")
            append("\n\n")
            append(attempt.toFailureMessageSection(runtimeMode))
        }
    }

    private fun readLogExcerpt(logFile: File, startOffset: Long = 0L, maxLines: Int = 32, maxChars: Int = 2200): String {
        val excerpt = runCatching {
            if (!logFile.exists()) {
                return@runCatching ""
            }

            val normalizedOffset = startOffset.coerceIn(0L, logFile.length())
            val readStartOffset = maxOf(normalizedOffset, logFile.length() - (maxChars.toLong() * 4L))
            java.io.RandomAccessFile(logFile, "r").use { reader ->
                reader.seek(readStartOffset)
                val bytes = ByteArray((reader.length() - readStartOffset).coerceAtMost(maxChars.toLong() * 4L).toInt())
                reader.readFully(bytes)
                String(bytes)
            }
                .lines()
                .takeLast(maxLines)
                .joinToString("\n") { line -> line.trimEnd() }
                .trim()
        }.getOrDefault("")

        if (excerpt.length <= maxChars) {
            return excerpt
        }

        return excerpt.takeLast(maxChars).trimStart()
    }
}

data class RootfsRuntimeEnsureResult(
    val runtimeMode: String = runtimeModeTermuxHost
)

data class RootfsRuntimeAttemptResult(
    val exitCode: Int,
    val logExcerpt: String
) {
    val succeeded: Boolean
        get() = exitCode == 0

    fun toStartupLogLine(runtimeMode: String = runtimeModeTermuxHost): String {
        return buildString {
            append("rootfs-runtime attempt runtimeMode=")
            append(runtimeMode)
            append(" exitCode=")
            append(exitCode)
            if (logExcerpt.isBlank()) {
                append(" logTail=<empty>")
            } else {
                append(" logTail=")
                append(logExcerpt.replace('\n', ' ').trim())
            }
        }
    }

    fun toFailureMessageSection(runtimeMode: String = runtimeModeTermuxHost): String {
        return buildString {
            append("runtimeMode=")
            append(runtimeMode)
            append("，退出码：")
            append(exitCode)
            if (logExcerpt.isBlank()) {
                append("。rootfs-runtime 日志为空。")
            } else {
                append("\n最近 rootfs-runtime 日志：\n")
                append(logExcerpt)
            }
        }
    }
}

class ServerController(
    private val launcher: LinuxRuntimeLauncher,
    private val paths: HostPaths,
    private val servicePort: Int,
    private val nodeMaxOldSpaceMb: Int,
    private val nodeMaxSemiSpaceMb: Int,
    private val tavernRuntimePatchEnabled: Boolean,
    private val tavernRuntimePatchDisabledModuleIds: Set<String>,
    private val tavernRuntimePatchSettingOverrides: RuntimePatchSettingOverrides,
    private val logFileName: String
) {
    fun start(): ManagedProcess {
        val environment = mutableMapOf(
            "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
            "TAVERN_PORT" to servicePort.toString(),
            // 0 表示自动；入口脚本仅在正数时才追加 --max-old-space-size。
            "TAVERN_NODE_MAX_OLD_SPACE_MB" to nodeMaxOldSpaceMb.toString(),
            // 0 表示自动；入口脚本仅在正数时才追加 --max-semi-space-size。
            "TAVERN_NODE_MAX_SEMI_SPACE_MB" to nodeMaxSemiSpaceMb.toString()
        )
        if (tavernRuntimePatchEnabled) {
            // 首版只暴露总开关；内部使用 performance 预设加载模块化 runtime patch。
            environment["SILLYDROID_TAVERN_PATCH_PRESET"] = "performance"
            val disabledModules = tavernRuntimePatchDisabledModuleIds
                .map { id -> id.trim() }
                .filter { id -> id.isNotBlank() }
                .sorted()
            if (disabledModules.isNotEmpty()) {
                environment["SILLYDROID_TAVERN_PATCH_DISABLED_MODULES"] = disabledModules.joinToString(",")
            }
            RuntimePatchSettingOverridesCodec.encode(tavernRuntimePatchSettingOverrides)
                .takeIf { encodedSettings -> encodedSettings.isNotBlank() }
                ?.let { encodedSettings ->
                    environment["SILLYDROID_TAVERN_PATCH_SETTINGS"] = encodedSettings
                }
        }

        val request = LaunchRequest(
            name = "sillydroid-server",
            scriptFile = File(paths.scriptsDir, "start-server.sh"),
            workingDirectory = paths.serverDir,
            environment = environment,
            logFileName = logFileName
        )
        return launcher.start(request)
    }
}

object HealthProbe {
    // 手机端偶发 IO/GC/WebView 初始化会拖慢本地 HTTP 响应，探活超时放宽到 10 秒避免把短暂卡顿判定为服务掉线。
    private const val readinessProbeTimeoutMillis = 10_000

    // watchdog/awaitReady 仅用来判断"本地 Tavern 进程是否在监听并响应 HTTP"。
    // 任何能完成 HTTP 握手的响应（含 3xx 重定向、4xx 认证挑战、5xx 服务自身错误）
    // 都说明 Node 进程仍然存活；只有 IOException/超时这类网络层失败才算掉线。
    // 不再依赖 2xx 白名单，避免开启 basicAuth 后探针永远失败、触发持续假阳性自动重启。
    suspend fun awaitReady(targetUrl: String, onAttempt: (attempt: Int, totalAttempts: Int) -> Unit = { _, _ -> }): Boolean {
        repeat(BootConfig.readinessProbeAttempts) { attempt ->
            onAttempt(attempt + 1, BootConfig.readinessProbeAttempts)
            if (checkOnce(targetUrl)) {
                return true
            }
            delay(1000)
        }
        return false
    }

    fun isReady(targetUrl: String): Boolean {
        return checkOnce(targetUrl)
    }

    private fun checkOnce(targetUrl: String): Boolean {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = readinessProbeTimeoutMillis
            readTimeout = readinessProbeTimeoutMillis
            instanceFollowRedirects = false
        }

        return try {
            connection.connect()
            // responseCode 能取到值就意味着对端完成了 HTTP 状态行的回复，进程活着。
            connection.responseCode > 0
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }
}


