package com.jm.sillydroid.data.runtime

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServerControllerRuntimePatchTest {
    @Test
    fun `start omits runtime patch preset when disabled`() {
        val rootDirectory = createServerControllerTempDirectory(prefix = "runtime-patch-disabled")
        try {
            val paths = createServerControllerHostPaths(rootDirectory)
            val launcher = RuntimePatchRecordingLinuxRuntimeLauncher(paths)

            ServerController(
                launcher = launcher,
                paths = paths,
                servicePort = 8000,
                nodeMaxOldSpaceMb = 3072,
                nodeMaxSemiSpaceMb = 64,
                tavernRuntimePatchEnabled = false,
                tavernRuntimePatchDisabledModuleIds = setOf("character-all-limited-concurrency"),
                tavernRuntimePatchSettingOverrides = mapOf(
                    "character-all-limited-concurrency" to mapOf("concurrency" to "4")
                ),
                logFileName = "server.log"
            ).start()

            val environment = launcher.requests.single().environment
            assertEquals("3072", environment["TAVERN_NODE_MAX_OLD_SPACE_MB"])
            assertEquals("64", environment["TAVERN_NODE_MAX_SEMI_SPACE_MB"])
            assertFalse(environment.containsKey("SILLYDROID_TAVERN_PATCH_PRESET"))
            assertFalse(environment.containsKey("SILLYDROID_TAVERN_PATCH_DISABLED_MODULES"))
            assertFalse(environment.containsKey("SILLYDROID_TAVERN_PATCH_SETTINGS"))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `start adds performance runtime patch preset when enabled`() {
        val rootDirectory = createServerControllerTempDirectory(prefix = "runtime-patch-enabled")
        try {
            val paths = createServerControllerHostPaths(rootDirectory)
            val launcher = RuntimePatchRecordingLinuxRuntimeLauncher(paths)

            ServerController(
                launcher = launcher,
                paths = paths,
                servicePort = 8000,
                nodeMaxOldSpaceMb = 4096,
                nodeMaxSemiSpaceMb = 128,
                tavernRuntimePatchEnabled = true,
                tavernRuntimePatchDisabledModuleIds = setOf("character-all-limited-concurrency"),
                tavernRuntimePatchSettingOverrides = mapOf(
                    "character-all-limited-concurrency" to mapOf("concurrency" to "4")
                ),
                logFileName = "server.log"
            ).start()

            val environment = launcher.requests.single().environment
            assertEquals("4096", environment["TAVERN_NODE_MAX_OLD_SPACE_MB"])
            assertEquals("128", environment["TAVERN_NODE_MAX_SEMI_SPACE_MB"])
            assertEquals("performance", environment["SILLYDROID_TAVERN_PATCH_PRESET"])
            assertEquals("character-all-limited-concurrency", environment["SILLYDROID_TAVERN_PATCH_DISABLED_MODULES"])
            assertEquals(
                """{"character-all-limited-concurrency":{"concurrency":"4"}}""",
                environment["SILLYDROID_TAVERN_PATCH_SETTINGS"]
            )
        } finally {
            rootDirectory.deleteRecursively()
        }
    }
}

private class RuntimePatchRecordingLinuxRuntimeLauncher(paths: HostPaths) : LinuxRuntimeLauncher(paths) {
    val requests = mutableListOf<LaunchRequest>()

    override fun start(request: LaunchRequest): ManagedProcess {
        requests += request
        return ManagedProcess(request.name, RuntimePatchFakeProcess())
    }
}

private class RuntimePatchFakeProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false
}

private fun createServerControllerTempDirectory(prefix: String): File {
    return createTempDirectory(prefix).toFile()
}

private fun createServerControllerHostPaths(rootDirectory: File): HostPaths {
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
        hostTermuxNodeBinary = File(hostLibDir, "libtermux-node.so").apply { writeText("node") },
        hostTermuxGitBinary = File(hostLibDir, "libtermux-git.so").apply { writeText("git") },
        hostTermuxGitRemoteHttpBinary = File(hostLibDir, "libtermux-git-remote-http.so").apply {
            writeText("git-remote-http")
        },
        hostTermuxShellBinary = File(hostLibDir, "libtermux-sh.so").apply { writeText("shell") },
        hostTermuxBashBinary = File(hostLibDir, "libtermux-bash.so").apply { writeText("bash") },
        dataRoot = dataRoot,
        serverDataDir = serverDataDir,
        logsDir = logsDir
    )
}
