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
    fun `ensure retries with no-seccomp when default proot attempt crashes with signal 11`() {
        val rootDirectory = createTempTestDirectory(prefix = "rootfs-runtime-retry")
        try {
            val paths = createRootfsTestHostPaths(rootDirectory)
            val logFileName = "rootfs-runtime.log"
            val launcher = RecordingLinuxRuntimeLauncher(paths) { request, index ->
                val logFile = File(paths.logsDir, request.logFileName)
                if (index == 0) {
                    logFile.appendText("proot info: vpid 1: terminated with signal 11\n")
                    FakeProcess(exitCode = 1)
                } else {
                    logFile.appendText("Linux runtime already preloaded.\n")
                    FakeProcess(exitCode = 0)
                }
            }
            val startupLines = mutableListOf<String>()

            val result = RootfsRuntimeProvisioner(launcher, paths).ensure(
                logFileName = logFileName,
                onAttemptLog = startupLines::add
            )

            assertEquals(ProotLaunchMode.NoSeccomp, result.mode)
            assertEquals(2, launcher.requests.size)
            assertTrue(launcher.requests[0].environment.isEmpty())
            assertEquals("1", launcher.requests[1].environment["PROOT_NO_SECCOMP"])
            assertTrue(startupLines.any { line -> line.contains("prootMode=default") && line.contains("signal 11") })
            assertTrue(startupLines.any { line -> line.contains("retrying with PROOT_NO_SECCOMP=1") })
            assertTrue(startupLines.any { line -> line.contains("prootMode=no-seccomp") && line.contains("exitCode=0") })
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `ensure reports both attempts when no-seccomp retry also fails`() {
        val rootDirectory = createTempTestDirectory(prefix = "rootfs-runtime-failure")
        try {
            val paths = createRootfsTestHostPaths(rootDirectory)
            val launcher = RecordingLinuxRuntimeLauncher(paths) { request, index ->
                val logFile = File(paths.logsDir, request.logFileName)
                if (index == 0) {
                    logFile.appendText("proot info: vpid 1: terminated with signal 11\n")
                } else {
                    logFile.appendText("ptrace(TRACEME): Operation not permitted\n")
                }
                FakeProcess(exitCode = 1)
            }

            val exception = runCatching {
                RootfsRuntimeProvisioner(launcher, paths).ensure(logFileName = "rootfs-runtime.log")
            }.exceptionOrNull()

            val message = requireNotNull(exception).message.orEmpty()
            assertTrue(message.contains("prootMode=default"))
            assertTrue(message.contains("signal 11"))
            assertTrue(message.contains("prootMode=no-seccomp"))
            assertTrue(message.contains("Operation not permitted"))
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
    val hostProotBinary = File(hostLibDir, "libproot.so").apply { writeText("proot") }
    val hostProotLoader = File(hostLibDir, "libproot-loader.so").apply { writeText("loader") }
    val hostProotLoader32 = File(hostLibDir, "libproot-loader32.so")

    return HostPaths(
        bootstrapRoot = bootstrapRoot,
        scriptsDir = scriptsDir,
        rootfsDir = rootfsDir,
        serverDir = serverDir,
        hostPrefixDir = hostPrefixDir,
        hostLibDir = hostLibDir,
        hostTmpDir = hostTmpDir,
        hostProotBinary = hostProotBinary,
        hostProotLoader = hostProotLoader,
        hostProotLoader32 = hostProotLoader32,
        dataRoot = dataRoot,
        serverDataDir = serverDataDir,
        logsDir = logsDir
    )
}
