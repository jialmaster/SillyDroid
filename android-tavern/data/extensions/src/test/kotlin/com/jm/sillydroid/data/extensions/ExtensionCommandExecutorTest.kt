package com.jm.sillydroid.data.extensions

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
}

private class CapturingExtensionCommandRunner : ExtensionCommandRunner {
    lateinit var lastRequest: ExtensionCommandRequest

    override fun run(
        request: ExtensionCommandRequest,
        onProgressPayload: ((String) -> Unit)?,
        failureMessage: (String) -> String
    ): ExtensionCommandResult {
        lastRequest = request
        return ExtensionCommandResult(logPath = "/tmp/fake-extension-command.log")
    }
}
