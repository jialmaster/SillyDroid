package com.jm.sillydroid.data.runtime

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExtensionCommandRunnerTest {

    @Test
    fun `termux host environment maps guest extension paths to app data paths`() {
        val rootDirectory = createTempTestDirectory(prefix = "extension-command-env-host")
        try {
            val paths = createExtensionTestHostPaths(rootDirectory)
            val commandScript = File(paths.serverDir, ".sillydroid-maintenance/install.mjs")
            val progressFile = File(paths.serverDataDir, ".sillydroid-maintenance/install.progress.json")

            val environment = buildExtensionCommandEnvironment(
                paths = paths,
                requestEnvironment = mapOf(
                    "SILLYDROID_EXTENSION_TARGET_DIR" to "/tavern/data/extensions/Foo",
                    "SILLYDROID_EXTENSION_TEMP_DIR" to "/tavern/data/.sillydroid-maintenance/Foo-repo",
                    "SILLYDROID_EXTENSION_REPO_URL" to "https://example.test/Foo.git"
                ),
                commandScript = commandScript,
                progressFile = progressFile
            )

            assertEquals(commandScript.absolutePath, environment["COMMAND_JS"])
            assertEquals(progressFile.absolutePath, environment["SILLYDROID_EXTENSION_PROGRESS_FILE"])
            assertEquals(
                File(paths.serverDataDir, "extensions/Foo").absolutePath,
                environment["SILLYDROID_EXTENSION_TARGET_DIR"]
            )
            assertEquals(
                File(paths.serverDataDir, ".sillydroid-maintenance/Foo-repo").absolutePath,
                environment["SILLYDROID_EXTENSION_TEMP_DIR"]
            )
            assertEquals("https://example.test/Foo.git", environment["SILLYDROID_EXTENSION_REPO_URL"])
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `extension launch script uses shared termux host runtime setup`() {
        val script = buildExtensionCommandLaunchScript()

        assertTrue(script.contains("""BOOTSTRAP_ROOT="${'$'}{BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}""""))
        assertTrue(script.contains("""TERMUX_CURL_BIN="${'$'}{TERMUX_CURL_BIN:?TERMUX_CURL_BIN is required}""""))
        assertTrue(script.contains(""". "${'$'}BOOTSTRAP_ROOT/scripts/termux-host-runtime.sh""""))
        assertTrue(script.contains("""prepare_termux_host_runtime"""))
        assertTrue(script.contains("""export GIT_TERMINAL_PROMPT=0"""))
        assertTrue(script.contains("""exec "${'$'}TERMUX_NODE_BIN" "${'$'}COMMAND_JS""""))
        assertFalse(script.contains("PROOT_BIN"))
        assertFalse(script.contains("SILLYDROID_RUNTIME_MODE"))
    }
}

private fun createTempTestDirectory(prefix: String): File {
    return createTempDirectory(prefix = prefix).toFile()
}

private fun createExtensionTestHostPaths(rootDirectory: File): HostPaths {
    val bootstrapRoot = File(rootDirectory, "bootstrap").apply { mkdirs() }
    val scriptsDir = File(bootstrapRoot, "scripts").apply { mkdirs() }
    val rootfsDir = File(bootstrapRoot, "rootfs").apply { mkdirs() }
    val serverDir = File(bootstrapRoot, "server").apply { mkdirs() }
    val hostPrefixDir = File(rootDirectory, "usr").apply { mkdirs() }
    val hostLibDir = File(rootDirectory, "lib").apply { mkdirs() }
    val hostTmpDir = File(hostPrefixDir, "tmp").apply { mkdirs() }
    val dataRoot = File(rootDirectory, "data").apply { mkdirs() }
    val serverDataDir = File(dataRoot, "server").apply { mkdirs() }
    val logsDir = File(rootDirectory, "logs").apply { mkdirs() }

    return HostPaths(
        bootstrapRoot = bootstrapRoot,
        scriptsDir = scriptsDir,
        rootfsDir = rootfsDir,
        serverDir = serverDir,
        hostPrefixDir = hostPrefixDir,
        hostLibDir = hostLibDir,
        hostTmpDir = hostTmpDir,
        hostTermuxNodeBinary = File(hostLibDir, "libtermux-node.so"),
        hostTermuxGitBinary = File(hostLibDir, "libtermux-git.so"),
        hostTermuxGitRemoteHttpBinary = File(hostLibDir, "libtermux-git-remote-http.so"),
        hostTermuxCurlBinary = File(hostLibDir, "libtermux-curl.so"),
        hostTermuxShellBinary = File(hostLibDir, "libtermux-sh.so"),
        hostTermuxBashBinary = File(hostLibDir, "libtermux-bash.so"),
        dataRoot = dataRoot,
        serverDataDir = serverDataDir,
        logsDir = logsDir
    )
}
