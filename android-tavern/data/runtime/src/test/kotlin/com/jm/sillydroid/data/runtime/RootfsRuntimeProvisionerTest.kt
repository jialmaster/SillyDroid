package com.jm.sillydroid.data.runtime

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsRuntimeProvisionerTest {

    @Test
    fun `ensure runs termux host runtime precheck once`() {
        val rootDirectory = createTempTestDirectory(prefix = "rootfs-runtime-termux-host")
        try {
            val paths = createRootfsTestHostPaths(rootDirectory)
            File(paths.rootfsDir, "rootfs-manifest.json").writeText("""{"runtimeMode":"termux-host"}""")
            val launcher = RecordingLinuxRuntimeLauncher(paths) { request, _ ->
                File(paths.logsDir, request.logFileName).appendText("Termux host runtime already preloaded.\n")
                FakeProcess(exitCode = 0)
            }
            val startupLines = mutableListOf<String>()

            val result = RootfsRuntimeProvisioner(launcher, paths).ensure(
                logFileName = "rootfs-runtime.log",
                onAttemptLog = startupLines::add
            )

            assertEquals("termux-host", result.runtimeMode)
            assertEquals(1, launcher.requests.size)
            assertTrue(launcher.requests.single().environment.isEmpty())
            assertTrue(startupLines.any { line -> line.contains("runtimeMode=termux-host") && line.contains("exitCode=0") })
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `ensure reports termux host precheck failure without compatibility retry`() {
        val rootDirectory = createTempTestDirectory(prefix = "rootfs-runtime-failure")
        try {
            val paths = createRootfsTestHostPaths(rootDirectory)
            val launcher = RecordingLinuxRuntimeLauncher(paths) { request, _ ->
                File(paths.logsDir, request.logFileName).appendText("CANNOT LINK EXECUTABLE libtermux-node.so\n")
                FakeProcess(exitCode = 1)
            }

            val exception = runCatching {
                RootfsRuntimeProvisioner(launcher, paths).ensure(logFileName = "rootfs-runtime.log")
            }.exceptionOrNull()

            val message = requireNotNull(exception).message.orEmpty()
            assertEquals(1, launcher.requests.size)
            assertTrue(message.contains("runtimeMode=termux-host"))
            assertTrue(message.contains("CANNOT LINK EXECUTABLE"))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }
}

private class RecordingLinuxRuntimeLauncher(
    paths: HostPaths,
    private val processFactory: (LaunchRequest, Int) -> Process
) : LinuxRuntimeLauncher(paths) {
    val requests = mutableListOf<LaunchRequest>()

    override fun start(request: LaunchRequest): ManagedProcess {
        val process = processFactory(request, requests.size)
        requests += request
        return ManagedProcess(request.name, process)
    }
}

private class FakeProcess(private val exitCode: Int) : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = exitCode

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = exitCode

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false
}

private fun createTempTestDirectory(prefix: String): File {
    return createTempDirectory(prefix).toFile()
}

private fun createRootfsTestHostPaths(rootDirectory: File): HostPaths {
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
    val hostTermuxNodeBinary = File(hostLibDir, "libtermux-node.so").apply { writeText("node") }
    val hostTermuxGitBinary = File(hostLibDir, "libtermux-git.so").apply { writeText("git") }
    val hostTermuxGitRemoteHttpBinary = File(hostLibDir, "libtermux-git-remote-http.so").apply {
        writeText("git-remote-http")
    }
    val hostTermuxCurlBinary = File(hostLibDir, "libtermux-curl.so").apply { writeText("curl") }
    val hostTermuxShellBinary = File(hostLibDir, "libtermux-sh.so").apply { writeText("shell") }
    val hostTermuxBashBinary = File(hostLibDir, "libtermux-bash.so").apply { writeText("bash") }

    return HostPaths(
        bootstrapRoot = bootstrapRoot,
        scriptsDir = scriptsDir,
        rootfsDir = rootfsDir,
        serverDir = serverDir,
        hostPrefixDir = hostPrefixDir,
        hostLibDir = hostLibDir,
        hostTmpDir = hostTmpDir,
        hostTermuxNodeBinary = hostTermuxNodeBinary,
        hostTermuxGitBinary = hostTermuxGitBinary,
        hostTermuxGitRemoteHttpBinary = hostTermuxGitRemoteHttpBinary,
        hostTermuxCurlBinary = hostTermuxCurlBinary,
        hostTermuxShellBinary = hostTermuxShellBinary,
        hostTermuxBashBinary = hostTermuxBashBinary,
        dataRoot = dataRoot,
        serverDataDir = serverDataDir,
        logsDir = logsDir
    )
}
