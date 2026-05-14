package com.jm.sillydroid

import android.content.Context
import android.net.ConnectivityManager
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.io.copyRecursively
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal object BootConfig {
    const val defaultServicePort = 8000
    const val readinessProbeAttempts = 300
    const val readinessPath = "/"
    const val bootstrapAssetRoot = "bootstrap"
    const val guestRuntimePrefix = "/data/data/com.termux/files/usr"
    const val notificationChannelId = "android-tavern-bootstrap"
    const val systemNotificationChannelId = "android-tavern-system-notification"
    const val notificationId = 1101

    val localServiceUrl: String
        get() = "http://127.0.0.1:$defaultServicePort"

    fun servicePort(context: Context): Int {
        return BootstrapHostConfigStore(context).servicePort
    }

    fun localServiceUrl(context: Context): String {
        return "http://127.0.0.1:${servicePort(context)}"
    }

    val readinessUrl: String
        get() = "$localServiceUrl$readinessPath"

    fun readinessUrl(context: Context): String {
        return "${localServiceUrl(context)}$readinessPath"
    }
}

internal enum class StartupPhase {
    IDLE,
    PAUSING,
    CONFIGURING,
    EXTRACTING,
    VALIDATING,
    STARTING_SERVER,
    WAITING_READY,
    READY,
    BLOCKED,
    ERROR
}

private val StartupPhase.defaultProgressPercent: Int
    get() = when (this) {
        StartupPhase.IDLE -> 0
        StartupPhase.PAUSING -> 0
        StartupPhase.CONFIGURING -> 0
        StartupPhase.EXTRACTING -> 8
        StartupPhase.VALIDATING -> 84
        StartupPhase.STARTING_SERVER -> 94
        StartupPhase.WAITING_READY -> 96
        StartupPhase.READY -> 100
        StartupPhase.BLOCKED -> 0
        StartupPhase.ERROR -> 0
    }

internal data class StartupState(
    val phase: StartupPhase = StartupPhase.IDLE,
    val message: String = "正在准备 SillyDroid 宿主环境。",
    val details: String = "",
    val localUrl: String = BootConfig.localServiceUrl,
    val progressPercent: Int = phase.defaultProgressPercent,
    val phaseStartedAtMillis: Long = 0L
) {
    val isReady: Boolean
        get() = phase == StartupPhase.READY

    val canRetry: Boolean
        get() = phase == StartupPhase.BLOCKED || phase == StartupPhase.ERROR
}

internal val StartupState.shouldPreferTavernServerLog: Boolean
    get() = phase == StartupPhase.STARTING_SERVER ||
        phase == StartupPhase.WAITING_READY ||
        phase == StartupPhase.READY

internal object StartupRuntimeStore {
    private val mutableState = MutableStateFlow(StartupState())
    val state = mutableState.asStateFlow()

    fun update(state: StartupState) {
        val previousState = mutableState.value
        val nowMillis = System.currentTimeMillis()
        val resolvedPhaseStartedAtMillis = when {
            state.phaseStartedAtMillis > 0L -> state.phaseStartedAtMillis
            previousState.phase == state.phase && previousState.phaseStartedAtMillis > 0L ->
                previousState.phaseStartedAtMillis
            else -> nowMillis
        }

        mutableState.value = state.copy(phaseStartedAtMillis = resolvedPhaseStartedAtMillis)
    }
}

internal data class HostPaths(
    val bootstrapRoot: File,
    val scriptsDir: File,
    val rootfsDir: File,
    val serverDir: File,
    val hostPrefixDir: File,
    val hostLibDir: File,
    val hostTmpDir: File,
    val hostProotBinary: File,
    val hostProotLoader: File,
    val hostProotLoader32: File,
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
            return HostPaths(
                bootstrapRoot = bootstrapRoot,
                scriptsDir = File(bootstrapRoot, "scripts"),
                rootfsDir = rootfsDir,
                serverDir = File(bootstrapRoot, "server"),
                hostPrefixDir = hostPrefixDir,
                hostLibDir = hostNativeLibDir,
                hostTmpDir = File(hostPrefixDir, "tmp"),
                hostProotBinary = File(hostNativeLibDir, "libproot.so"),
                hostProotLoader = File(hostNativeLibDir, "libproot-loader.so"),
                hostProotLoader32 = File(hostNativeLibDir, "libproot-loader32.so"),
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

internal fun resolveHostLogsDir(context: Context): File {
    return File(context.applicationContext.filesDir, "android-tavern/logs")
}

internal class AssetExtractor(private val context: Context) {
    companion object {
        // extractBootstrap 可能被多个协程并发调用（设置页 loadConfiguration + extensionsCoordinator.initialize），
        // 必须串行化以避免对同一目录的并发写入导致 AssertionError / IOException。
        private val extractLock = Any()
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
            requiredRelativePaths = listOf(
                "fs/bin/sh",
                "fs/etc/ssl/certs/ca-certificates.crt",
                "rootfs-manifest.json"
            )
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
            "正在比较当前设备上的 Tavern payload manifest。",
            45
        )
        val serverDirectoryRefreshed = refreshAssetDirectoryIfNeeded(
            manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/server/bootstrap-manifest.json",
            installedManifestFile = File(paths.serverDir, "bootstrap-manifest.json"),
            targetDirectory = paths.serverDir,
            replaceOnIncomplete = true,
            requiredRelativePaths = listOf(
                "bootstrap-manifest.json",
                "tavern-entrypoint.sh",
                "dependency-post-extract.sh",
                "server.js",
                "package.json",
                "package-lock.json",
                "node_modules/archiver-utils/node_modules/glob/dist/commonjs/walker.js",
                "node_modules/archiver-utils/node_modules/glob/dist/commonjs/ignore.js"
            )
        )
        if (serverDirectoryRefreshed) {
            onProgress(
                "正在解包 Tavern server 与 Node runtime。",
                "首次启动时这里会继续占用一些时间。",
                48
            )
            var lastServerProgress = 48
            val serverArchiveAssetPath = resolveServerArchiveAssetPath()
            extractArchiveAsset(
                assetPath = serverArchiveAssetPath,
                targetDirectory = paths.serverDir,
                shouldSetExecutable = { relativePath -> relativePath.endsWith(".sh", ignoreCase = true) },
                onProgress = { processedEntries, totalEntries ->
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
            )
            copyFile(
                AssetCopySpec(
                    assetPath = "${BootConfig.bootstrapAssetRoot}/server/bootstrap-manifest.json",
                    targetPath = File(paths.serverDir, "bootstrap-manifest.json")
                )
            )
        } else {
            onProgress(
                "Tavern server 资产已是最新。",
                "跳过 Tavern payload 解包，继续校正依赖包权限。",
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
            "server payload manifest 与依赖包权限已同步完成。",
            72
        )

        onProgress(
            "正在同步启动脚本与内置扩展资产。",
            "正在写入 bootstrap 脚本、配置与内置扩展目录。",
            74
        )
        copyNode(AssetCopySpec(BootConfig.bootstrapAssetRoot, paths.bootstrapRoot), skippedAssetRoots)
        onProgress(
            "正在同步宿主内置扩展。",
            "正在把 APK 内置的 host 扩展安装到用户 extensions 目录。",
            76
        )
        installBundledHostExtensions(paths, replaceExisting = true)
        onProgress(
            "正在刷新 Linux 宿主目录。",
            "正在准备 usr、tmp 与运行时依赖目录。",
            80
        )
        refreshHostPrefixDirectory(paths, rootfsDirectoryRefreshed)
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

        if (targetDirectory.exists()) {
            if (targetDirectory.isDirectory) {
                targetDirectory.deleteRecursively()
            } else {
                targetDirectory.delete()
            }
        }
        targetDirectory.mkdirs()
        return true
    }

    private fun assetContentMatchesFile(assetPath: String, file: File): Boolean {
        return runCatching {
            context.assets.open(assetPath).use { assetInput ->
                digestOf(assetInput) == digestOf(file)
            }
        }.getOrDefault(false)
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

    private fun resolveServerArchiveAssetPath(): String {
        val manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/server/bootstrap-manifest.json"
        val archiveFile = runCatching {
            context.assets.open(manifestAssetPath).bufferedReader().use { reader ->
                JSONObject(reader.readText()).optString("archiveFile").trim()
            }
        }.getOrDefault("")

        return if (archiveFile.isBlank()) {
            "${BootConfig.bootstrapAssetRoot}/server/server-payload.zip"
        } else {
            "${BootConfig.bootstrapAssetRoot}/server/$archiveFile"
        }
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
        onProgress: (processedEntries: Int, totalEntries: Int) -> Unit = { _, _ -> }
    ) {
        if (targetDirectory.exists()) {
            if (targetDirectory.isDirectory) {
                targetDirectory.deleteRecursively()
            } else {
                targetDirectory.delete()
            }
        }
        targetDirectory.mkdirs()

        val canonicalTargetDirectory = targetDirectory.canonicalFile
        val canonicalTargetPrefix = canonicalTargetDirectory.path + File.separator
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

                    val outputFile = File(targetDirectory, relativePath).canonicalFile
                    val outputPath = outputFile.path
                    if (outputPath != canonicalTargetDirectory.path && !outputPath.startsWith(canonicalTargetPrefix)) {
                        throw BootstrapException("bootstrap 归档包含非法路径：$relativePath")
                    }

                    if (entry.isDirectory) {
                        if (outputFile.exists() && !outputFile.isDirectory) {
                            outputFile.deleteRecursively()
                        }
                        if (!outputFile.exists() && !outputFile.mkdirs()) {
                            throw BootstrapException("创建目录失败：$relativePath")
                        }
                    } else {
                        val parentDirectory = outputFile.parentFile
                        if (parentDirectory != null) {
                            if (parentDirectory.exists() && !parentDirectory.isDirectory) {
                                parentDirectory.deleteRecursively()
                            }
                            if (!parentDirectory.exists() && !parentDirectory.mkdirs()) {
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
                    throw BootstrapException("bootstrap symlink manifest 格式非法：$line")
                }

                val linkTarget = parts[0].trim()
                val relativePath = parts[1].trim().removePrefix("./").trimStart('/')
                if (linkTarget.isBlank() || relativePath.isBlank()) {
                    throw BootstrapException("bootstrap symlink manifest 包含空路径：$line")
                }

                val linkFile = File(targetDirectory, relativePath)
                linkFile.parentFile?.mkdirs()

                val canonicalLinkPath = linkFile.canonicalFile.path
                if (canonicalLinkPath != canonicalTargetDirectory.path && !canonicalLinkPath.startsWith(canonicalTargetPrefix)) {
                    throw BootstrapException("bootstrap symlink 路径非法：$relativePath")
                }

                if (linkFile.exists()) {
                    if (linkFile.isDirectory) {
                        linkFile.deleteRecursively()
                    } else {
                        linkFile.delete()
                    }
                }

                try {
                    Os.symlink(linkTarget, linkFile.absolutePath)
                } catch (error: ErrnoException) {
                    throw BootstrapException("创建 bootstrap symlink 失败：$relativePath -> $linkTarget (${error.message})")
                }
            }
        }

        manifestFile.delete()
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
            throw BootstrapException("dependency post-extract hook 执行超时：${hookFile.absolutePath}")
        }

        if (process.exitValue() != 0) {
            val details = if (output.isBlank()) {
                "退出码 ${process.exitValue()}"
            } else {
                output
            }
            throw BootstrapException("dependency post-extract hook 执行失败：$details")
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
                if (targetDirectory.exists() && !replaceExisting) {
                    return@forEach
                }

                if (targetDirectory.exists()) {
                    targetDirectory.deleteRecursively()
                }

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
                stageDirectory.deleteRecursively()
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

                targetDirectory.deleteRecursively()
                if (!stageDirectory.renameTo(targetDirectory)) {
                    if (!stageDirectory.copyRecursively(targetDirectory, overwrite = true)) {
                        throw IOException("failed to promote staged extension")
                    }
                    stageDirectory.deleteRecursively()
                }
                return
            } catch (error: Throwable) {
                lastError = error
                stageDirectory.deleteRecursively()
                targetDirectory.deleteRecursively()
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
            "bin/proot",
            "bin/sh",
            "etc/tls/cert.pem",
            "libexec/proot/loader"
        )
        val hostPrefixExecutableFiles = listOf(
            "bin/proot",
            "bin/sh",
            "libexec/proot/loader"
        )
        val shouldRefreshHostPrefix = rootfsDirectoryRefreshed ||
            !paths.hostPrefixDir.containsRequiredFiles(hostPrefixRequiredFiles) ||
            !paths.hostPrefixDir.containsExecutableFiles(hostPrefixExecutableFiles)
        if (!shouldRefreshHostPrefix) {
            paths.hostTmpDir.mkdirs()
            return
        }

        if (paths.hostPrefixDir.exists()) {
            paths.hostPrefixDir.deleteRecursively()
        }

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

internal data class AssetCopySpec(
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
            targetPath.name == "proot" ||
            targetPath.name == "loader" ||
            targetPath.name == "loader32" ||
            targetPath.name == "tavern-entrypoint.sh"
    }
}

internal class BootstrapLayoutVerifier(private val paths: HostPaths) {
    fun verify() {
        val missingEntries = requiredEntries()
            .filterNot { (_, file) -> file.exists() }
            .map { (displayPath, _) -> displayPath }

        if (missingEntries.isNotEmpty()) {
            throw BootstrapException(
                "bootstrap 资产缺少关键文件：${missingEntries.joinToString()}。请先同步 android-tavern 下的离线运行时与 payload 产物。"
            )
        }
    }

    private fun requiredEntries(): List<Pair<String, File>> {
        return listOf(
            "scripts/ensure-rootfs-runtime.sh" to File(paths.scriptsDir, "ensure-rootfs-runtime.sh"),
            "scripts/start-server.sh" to File(paths.scriptsDir, "start-server.sh"),
            "rootfs/fs/bin/sh" to File(paths.rootfsDir, "fs/bin/sh"),
            "rootfs/rootfs-manifest.json" to File(paths.rootfsDir, "rootfs-manifest.json"),
            "nativeLibraryDir/libtalloc_2.so" to File(paths.hostLibDir, "libtalloc_2.so"),
            "nativeLibraryDir/libproot.so" to paths.hostProotBinary,
            "nativeLibraryDir/libproot-loader.so" to paths.hostProotLoader,
            "server/bootstrap-manifest.json" to File(paths.serverDir, "bootstrap-manifest.json"),
            "server/tavern-entrypoint.sh" to File(paths.serverDir, "tavern-entrypoint.sh")
        )
    }
}

internal class AndroidDnsConfigWriter(private val context: Context) {
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

internal class BootstrapException(message: String) : IllegalStateException(message)

internal data class LaunchRequest(
    val name: String,
    val scriptFile: File,
    val workingDirectory: File,
    val environment: Map<String, String>,
    val logFileName: String = HostLogManager.runtimeLogFileName(name)
)

internal class ManagedProcess(
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

internal object ServerProcessJanitor {
    private data class ProcInfo(
        val pid: Int,
        val ppid: Int,
        val name: String,
        val cmdline: String
    ) {
        fun isBootstrapServerProcess(): Boolean {
            return name == "libproot.so" ||
                cmdline.contains("libproot.so") ||
                cmdline.contains("server.js") ||
                cmdline.contains("start-server.sh") ||
                cmdline.contains("tavern-entrypoint.sh") ||
                cmdline.contains("/tavern/server")
        }
    }

    fun cleanupLingeringServerProcesses(): Int {
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
        Thread.sleep(150)

        val remainingPids = pidsToKill.filter { pid -> File("/proc/$pid").exists() }
        if (remainingPids.isNotEmpty()) {
            sendSignal(remainingPids, "KILL")
            Thread.sleep(100)
        }

        return pidsToKill.size
    }

    private fun listOwnedProcesses(): List<ProcInfo> {
        val currentPid = android.os.Process.myPid()
        val currentUid = android.os.Process.myUid().toString()
        return File("/proc").listFiles().orEmpty().mapNotNull { procDir ->
            val pid = procDir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == currentPid) {
                return@mapNotNull null
            }

            val statusLines = runCatching {
                File(procDir, "status").readLines()
            }.getOrNull() ?: return@mapNotNull null

            val uidLine = statusLines.firstOrNull { line -> line.startsWith("Uid:") } ?: return@mapNotNull null
            val uid = uidLine.substringAfter(':').trim().substringBefore('\t').substringBefore(' ')
            if (uid != currentUid) {
                return@mapNotNull null
            }

            val name = statusLines.firstOrNull { line -> line.startsWith("Name:") }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
            val ppid = statusLines.firstOrNull { line -> line.startsWith("PPid:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
            val cmdline = readCmdline(File(procDir, "cmdline"))

            ProcInfo(pid = pid, ppid = ppid, name = name, cmdline = cmdline)
        }
    }

    private fun readCmdline(cmdlineFile: File): String {
        return runCatching {
            cmdlineFile.readBytes()
                .toString(Charsets.UTF_8)
                .replace('\u0000', ' ')
                .trim()
        }.getOrDefault("")
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

internal class LinuxRuntimeLauncher(private val paths: HostPaths) {
    fun start(request: LaunchRequest): ManagedProcess {
        if (!request.scriptFile.exists()) {
            throw BootstrapException("启动脚本不存在：${request.scriptFile.absolutePath}")
        }

        if (!paths.hostProotBinary.exists()) {
            throw BootstrapException("缺少 host proot：${paths.hostProotBinary.absolutePath}")
        }

        if (!paths.hostProotLoader.exists()) {
            throw BootstrapException("缺少 host proot loader：${paths.hostProotLoader.absolutePath}")
        }

        if (!paths.hostLibDir.exists()) {
            throw BootstrapException("缺少 host proot 依赖目录：${paths.hostLibDir.absolutePath}")
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
        environment["BOOTSTRAP_ROOT"] = paths.bootstrapRoot.absolutePath
        environment["ROOTFS_DIR"] = paths.rootfsDir.absolutePath
        environment["SERVER_DIR"] = paths.serverDir.absolutePath
        environment["LOGS_DIR"] = paths.logsDir.absolutePath
        environment["HOST_PROOT_BIN"] = paths.hostProotBinary.absolutePath
        environment["HOST_PROOT_LIB_DIR"] = paths.hostLibDir.absolutePath
        environment["HOST_PROOT_LOADER"] = paths.hostProotLoader.absolutePath
        environment["HOST_PREFIX_DIR"] = paths.hostPrefixDir.absolutePath
        environment["HOST_RUNTIME_PREFIX"] = BootConfig.guestRuntimePrefix
        if (paths.hostProotLoader32.exists()) {
            environment["HOST_PROOT_LOADER_32"] = paths.hostProotLoader32.absolutePath
        }
        environment["HOST_TMP_DIR"] = paths.hostTmpDir.absolutePath

        return ManagedProcess(request.name, processBuilder.start())
    }
}

internal class RootfsRuntimeProvisioner(
    private val launcher: LinuxRuntimeLauncher,
    private val paths: HostPaths
) {
    fun ensure(logFileName: String, onHeartbeat: (elapsedSeconds: Int) -> Unit = {}) {
        val request = LaunchRequest(
            name = "rootfs-runtime",
            scriptFile = File(paths.scriptsDir, "ensure-rootfs-runtime.sh"),
            workingDirectory = paths.bootstrapRoot,
            environment = emptyMap(),
            logFileName = logFileName
        )
        val process = launcher.start(request)
        var elapsedSeconds = 0
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            elapsedSeconds += 1
            onHeartbeat(elapsedSeconds)
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Linux 离线运行时校验失败，请查看当前 app 启动对应的 rootfs-runtime 日志。")
        }
    }
}

internal class ServerController(
    private val launcher: LinuxRuntimeLauncher,
    private val paths: HostPaths,
    private val servicePort: Int,
    private val logFileName: String
) {
    fun start(): ManagedProcess {
        val request = LaunchRequest(
            name = "sillydroid-server",
            scriptFile = File(paths.scriptsDir, "start-server.sh"),
            workingDirectory = paths.serverDir,
            environment = mapOf(
                "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
                "TAVERN_PORT" to servicePort.toString()
            ),
            logFileName = logFileName
        )
        return launcher.start(request)
    }
}

internal object HealthProbe {
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
            // 3000ms 比 1000ms 对手机端更宽容：允许偶发 GC、调度抖动以及 WebView 抢占 CPU 的瞬时延迟。
            connectTimeout = 3_000
            readTimeout = 3_000
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


