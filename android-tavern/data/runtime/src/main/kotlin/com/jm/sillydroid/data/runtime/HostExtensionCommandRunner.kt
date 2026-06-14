package com.jm.sillydroid.data.runtime

import android.content.Context
import com.jm.sillydroid.domain.extensions.ExtensionCommandRequest
import com.jm.sillydroid.domain.extensions.ExtensionCommandResult
import com.jm.sillydroid.domain.extensions.ExtensionCommandRunner
import com.jm.sillydroid.domain.runtime.RuntimeLogManager
import java.io.File
import java.util.concurrent.TimeUnit

class HostExtensionCommandRunner(
    context: Context,
    private val runtimeLogs: RuntimeLogManager
) : ExtensionCommandRunner {
    private val appContext = context.applicationContext

    override fun run(
        request: ExtensionCommandRequest,
        onProgressPayload: ((String) -> Unit)?,
        failureMessage: (String) -> String
    ): ExtensionCommandResult {
        val paths = HostPaths.from(appContext)
        val launcher = LinuxRuntimeLauncher(paths)
        // 扩展安装必须和主服务复用同一个 Termux host runtime；
        // 每次命令前同步 bootstrap scripts，确保共享的 termux-host-runtime.sh 与当前 APK 一致。
        AssetExtractor(appContext).prepareHostExtensionAssets(paths)

        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance")
        val serverMaintenanceRoot = File(paths.bootstrapRoot, "server/.sillydroid-maintenance")
        maintenanceRoot.mkdirs()
        serverMaintenanceRoot.mkdirs()

        val commandScript = File(serverMaintenanceRoot, request.commandFileName)
        val launchScript = File(maintenanceRoot, "extension-command.sh")
        commandScript.writeText(request.commandContent)
        launchScript.writeText(buildExtensionCommandLaunchScript())

        val resolvedLogFileName = runtimeLogs.runtimeLogFileName(request.requestName)
        val logPath = File(paths.logsDir, resolvedLogFileName).absolutePath
        val progressFile = File(maintenanceRoot, "${request.requestName}.progress.json")
        progressFile.delete()

        val launchRequest = LaunchRequest(
            name = request.requestName,
            scriptFile = launchScript,
            workingDirectory = paths.bootstrapRoot,
            environment = buildExtensionCommandEnvironment(
                paths = paths,
                requestEnvironment = request.environment,
                commandScript = commandScript,
                progressFile = progressFile
            ),
            logFileName = resolvedLogFileName
        )
        val process = launcher.start(launchRequest)
        var lastProgressPayload: String? = null
        val timeoutAtNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (onProgressPayload != null) {
                    val progressPayload = readProgressPayload(progressFile)
                    if (progressPayload != null && progressPayload != lastProgressPayload) {
                        lastProgressPayload = progressPayload
                        onProgressPayload(progressPayload)
                    }
                }

                if (System.nanoTime() >= timeoutAtNanos) {
                    process.stop()
                    throw BootstrapException(failureMessage(logPath))
                }
            }

            if (onProgressPayload != null) {
                val progressPayload = readProgressPayload(progressFile)
                if (progressPayload != null && progressPayload != lastProgressPayload) {
                    onProgressPayload(progressPayload)
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw BootstrapException(failureMessage(logPath))
            }
        } catch (exception: Exception) {
            if (process.isAlive()) {
                process.stop()
            }
            if (exception is BootstrapException) {
                throw exception
            }
            throw exception
        } finally {
            progressFile.delete()
            commandScript.delete()
        }

        return ExtensionCommandResult(logPath = logPath)
    }

    private fun readProgressPayload(progressFile: File): String? {
        if (!progressFile.exists()) {
            return null
        }

        return runCatching {
            progressFile.readText().takeIf { payload -> payload.isNotBlank() }
        }.getOrNull()
    }
}

internal fun buildExtensionCommandEnvironment(
    paths: HostPaths,
    requestEnvironment: Map<String, String>,
    commandScript: File,
    progressFile: File
): Map<String, String> {
    val environment = requestEnvironment.toMutableMap()
    environment["APP_DATA_ROOT"] = paths.serverDataDir.absolutePath
    environment["COMMAND_JS"] = commandScript.absolutePath
    environment["SILLYDROID_EXTENSION_PROGRESS_FILE"] = progressFile.absolutePath
    environment["SILLYDROID_EXTENSION_TARGET_DIR"] = resolveTermuxHostExtensionPath(
        paths = paths,
        path = environment["SILLYDROID_EXTENSION_TARGET_DIR"].orEmpty()
    )
    environment["SILLYDROID_EXTENSION_TEMP_DIR"] = resolveTermuxHostExtensionPath(
        paths = paths,
        path = environment["SILLYDROID_EXTENSION_TEMP_DIR"].orEmpty()
    )

    return environment
}

internal fun resolveTermuxHostExtensionPath(paths: HostPaths, path: String): String {
    return when {
        path == extensionGuestDataRoot -> paths.serverDataDir.absolutePath
        path.startsWith("$extensionGuestDataRoot/") -> File(
            paths.serverDataDir,
            path.removePrefix("$extensionGuestDataRoot/")
        ).absolutePath
        path == extensionGuestServerRoot -> paths.serverDir.absolutePath
        path.startsWith("$extensionGuestServerRoot/") -> File(
            paths.serverDir,
            path.removePrefix("$extensionGuestServerRoot/")
        ).absolutePath
        else -> path
    }
}

internal fun buildExtensionCommandLaunchScript(): String {
    return """
        #!/system/bin/sh
        set -eu

        BOOTSTRAP_ROOT="${'$'}{BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}"
        SERVER_DIR="${'$'}{SERVER_DIR:?SERVER_DIR is required}"
        APP_DATA_ROOT="${'$'}{APP_DATA_ROOT:?APP_DATA_ROOT is required}"
        LOGS_DIR="${'$'}{LOGS_DIR:?LOGS_DIR is required}"
        COMMAND_JS="${'$'}{COMMAND_JS:?COMMAND_JS is required}"
        HOST_PREFIX_DIR="${'$'}{HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
        HOST_TMP_DIR="${'$'}{HOST_TMP_DIR:?HOST_TMP_DIR is required}"
        TERMUX_NODE_BIN="${'$'}{TERMUX_NODE_BIN:?TERMUX_NODE_BIN is required}"
        TERMUX_GIT_BIN="${'$'}{TERMUX_GIT_BIN:?TERMUX_GIT_BIN is required}"
        TERMUX_GIT_REMOTE_HTTP_BIN="${'$'}{TERMUX_GIT_REMOTE_HTTP_BIN:?TERMUX_GIT_REMOTE_HTTP_BIN is required}"
        TERMUX_CURL_BIN="${'$'}{TERMUX_CURL_BIN:?TERMUX_CURL_BIN is required}"
        TERMUX_SH_BIN="${'$'}{TERMUX_SH_BIN:?TERMUX_SH_BIN is required}"
        TERMUX_BASH_BIN="${'$'}{TERMUX_BASH_BIN:-}"
        HOST_NATIVE_LIB_DIR="${'$'}{HOST_NATIVE_LIB_DIR:?HOST_NATIVE_LIB_DIR is required}"

        . "${'$'}BOOTSTRAP_ROOT/scripts/termux-host-runtime.sh"

        assert_dir "${'$'}HOST_PREFIX_DIR" "缺少 host prefix 目录：${'$'}HOST_PREFIX_DIR"
        assert_dir "${'$'}SERVER_DIR" "缺少 Tavern 服务目录：${'$'}SERVER_DIR"
        assert_dir "${'$'}HOST_NATIVE_LIB_DIR" "缺少 host native lib 目录：${'$'}HOST_NATIVE_LIB_DIR"
        assert_file "${'$'}TERMUX_NODE_BIN" "缺少 Termux Node 入口：${'$'}TERMUX_NODE_BIN"
        assert_file "${'$'}TERMUX_GIT_BIN" "缺少 Termux Git 入口：${'$'}TERMUX_GIT_BIN"
        assert_file "${'$'}TERMUX_GIT_REMOTE_HTTP_BIN" "缺少 Termux Git HTTPS helper 入口：${'$'}TERMUX_GIT_REMOTE_HTTP_BIN"
        assert_file "${'$'}TERMUX_CURL_BIN" "缺少 Termux curl 入口：${'$'}TERMUX_CURL_BIN"
        assert_file "${'$'}TERMUX_SH_BIN" "缺少 Termux shell 入口：${'$'}TERMUX_SH_BIN"
        assert_file "${'$'}COMMAND_JS" "缺少扩展命令脚本：${'$'}COMMAND_JS"
        mkdir -p "${'$'}APP_DATA_ROOT" "${'$'}LOGS_DIR" "${'$'}HOST_TMP_DIR"
        prepare_termux_host_runtime

        export HOME="${'$'}APP_DATA_ROOT/.termux-home"
        export GIT_TERMINAL_PROMPT=0
        mkdir -p "${'$'}HOME" "${'$'}TMPDIR"
        cd "${'$'}SERVER_DIR"
        exec "${'$'}TERMUX_NODE_BIN" "${'$'}COMMAND_JS"
    """.trimIndent()
}

private const val extensionGuestDataRoot = "/tavern/data"
private const val extensionGuestServerRoot = "/tavern/server"
