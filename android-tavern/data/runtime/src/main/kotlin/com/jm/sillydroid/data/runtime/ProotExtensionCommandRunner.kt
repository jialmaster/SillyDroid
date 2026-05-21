package com.jm.sillydroid.data.runtime
import android.content.Context
import com.jm.sillydroid.domain.extensions.ExtensionCommandRequest
import com.jm.sillydroid.domain.extensions.ExtensionCommandResult
import com.jm.sillydroid.domain.extensions.ExtensionCommandRunner
import com.jm.sillydroid.domain.runtime.RuntimeLogManager
import java.io.File
import java.util.concurrent.TimeUnit

class ProotExtensionCommandRunner(
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
        // 扩展命令同样会直接进入 proot；这里复用统一准备入口，确保 nativeLibraryDir 缺失时也能先安装 host runtime。
        AssetExtractor(appContext).prepareWorkDirectories(paths)

        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance")
        val serverMaintenanceRoot = File(paths.bootstrapRoot, "server/.sillydroid-maintenance")
        maintenanceRoot.mkdirs()
        serverMaintenanceRoot.mkdirs()

        val commandScript = File(serverMaintenanceRoot, request.commandFileName)
        val launchScript = File(maintenanceRoot, "extension-command.sh")
        commandScript.writeText(request.commandContent)
        launchScript.writeText(request.launchScriptContent)

        val resolvedLogFileName = runtimeLogs.runtimeLogFileName(request.requestName)
        val logPath = File(paths.logsDir, resolvedLogFileName).absolutePath
        val progressFile = File(maintenanceRoot, "${request.requestName}.progress.json")
        val guestProgressFile = "/tavern/data/.sillydroid-maintenance/${progressFile.name}"
        progressFile.delete()

        val launchRequest = LaunchRequest(
            name = request.requestName,
            scriptFile = launchScript,
            workingDirectory = paths.bootstrapRoot,
            environment = request.environment + mapOf(
                "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
                "COMMAND_JS" to "/tavern/server/.sillydroid-maintenance/${request.commandFileName}",
                "SILLYDROID_EXTENSION_PROGRESS_FILE" to guestProgressFile
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
