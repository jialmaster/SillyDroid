package com.jm.sillydroid.data.extensions

import com.jm.sillydroid.core.model.extensions.NormalizedExtensionRepository
import com.jm.sillydroid.domain.extensions.ExtensionCommandRequest
import com.jm.sillydroid.domain.extensions.ExtensionCommandResult
import com.jm.sillydroid.domain.extensions.ExtensionCommandRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCommandExecutorTest {

    @Test
    fun `server plugin dependency install keeps official npm install command semantics`() {
        val runner = CapturingExtensionCommandRunner()
        val executor = ExtensionCommandExecutor(
            commandRunner = runner,
            remoteManifestDataSource = RemoteManifestDataSource()
        )

        executor.installServerPluginDependencies(failureMessage = { it })

        val command = runner.lastRequest.commandContent
        assertTrue(command.contains("const args = [npmCliPath(), 'install'];"))
        assertTrue(command.contains("npm install in"))
        assertTrue(command.contains("const shellBinForNpm = process.env.TERMUX_SH_BIN;"))
        assertTrue(command.contains("npm_config_script_shell: shellBinForNpm"))
        assertTrue(command.contains("NPM_CONFIG_SCRIPT_SHELL: shellBinForNpm"))
        assertFalse(command.contains("'--omit=dev'"))
        assertFalse(command.contains("npm_config_optional"))
        assertFalse(command.contains("await import('node:sqlite')"))
        assertFalse(command.contains("'--omit=optional'"))
        assertFalse(command.contains("'--ignore-scripts'"))
    }

    @Test
    fun `server plugin preview treats package json as optional metadata`() {
        val runner = CapturingExtensionCommandRunner(stopAfterCapture = true)
        val executor = ExtensionCommandExecutor(
            commandRunner = runner,
            remoteManifestDataSource = RemoteManifestDataSource()
        )

        try {
            val probe = executor.previewPluginInstall(
                folderName = "auto-continue",
                repository = NormalizedExtensionRepository(
                    cloneUrl = "https://github.com/example/auto-continue.git",
                    branch = null
                ),
                failureMessage = { it }
            )
            throw AssertionError("Expected command capture to stop before preview result parsing, got $probe")
        } catch (_: StopAfterCaptureException) {
        }

        val command = runner.lastRequest.commandContent
        assertFalse(command.contains("后端插件仓库根目录缺少 package.json"))
        assertTrue(command.contains("let packageJson = null;"))
        assertTrue(command.contains("let manifestJson = null;"))
        assertTrue(command.contains("readString(manifestJson, ['display_name', 'name'])"))
        assertTrue(command.contains("path.basename(repoUrl).replace(/\\.git$/i, '')"))
    }

    @Test
    fun `server plugin install only runs npm install when package json exists`() {
        val runner = CapturingExtensionCommandRunner()
        val executor = ExtensionCommandExecutor(
            commandRunner = runner,
            remoteManifestDataSource = RemoteManifestDataSource()
        )

        executor.installPlugin(
            folderName = "auto-continue",
            repository = NormalizedExtensionRepository(
                cloneUrl = "https://github.com/example/auto-continue.git",
                branch = null
            ),
            failureMessage = { it }
        )

        val command = runner.lastRequest.commandContent
        assertFalse(command.contains("targetKind === 'SERVER_PLUGIN' && !fs.existsSync(packagePath)"))
        assertFalse(command.contains("runNpmInstall && !fs.existsSync(packagePath)"))
        assertTrue(command.contains("if (runNpmInstall && fs.existsSync(path.join(targetDir, 'package.json')))"))
    }
}

private class CapturingExtensionCommandRunner(
    private val resultLogPath: String = "/tmp/fake-extension-command.log",
    private val stopAfterCapture: Boolean = false
) : ExtensionCommandRunner {
    lateinit var lastRequest: ExtensionCommandRequest

    override fun run(
        request: ExtensionCommandRequest,
        onProgressPayload: ((String) -> Unit)?,
        failureMessage: (String) -> String
    ): ExtensionCommandResult {
        lastRequest = request
        if (stopAfterCapture) {
            throw StopAfterCaptureException()
        }
        return ExtensionCommandResult(logPath = resultLogPath)
    }
}

private class StopAfterCaptureException : RuntimeException()
