package com.jm.sillydroid.data.runtime

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RootConfigLayoutTest {

    @Test
    fun `ensureRootConfigLayout migrates legacy config without modifying legacy path`() {
        val rootDirectory = createTempDirectory(prefix = "root-config-legacy").toFile()
        try {
            val paths = createHostPaths(rootDirectory)
            val legacyConfig = File(paths.serverDataDir, "config/config.yaml").apply {
                parentFile?.mkdirs()
                writeText("port: 18000\n")
            }

            assertTrue(ensureRootConfigLayout(paths))

            assertEquals("port: 18000\n", File(paths.serverDir, "config.yaml").readText())
            assertEquals("port: 18000\n", legacyConfig.readText())
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `ensureRootConfigLayout materializes root symlink as regular file`() {
        val rootDirectory = createTempDirectory(prefix = "root-config-link").toFile()
        try {
            val paths = createHostPaths(rootDirectory)
            val legacyConfig = File(paths.serverDataDir, "config/config.yaml").apply {
                parentFile?.mkdirs()
                writeText("listen: true\n")
            }
            val rootConfig = File(paths.serverDir, "config.yaml")
            assumeTrue(createFileSymlink(rootConfig, legacyConfig))

            assertTrue(ensureRootConfigLayout(paths))

            assertFalse(Files.isSymbolicLink(rootConfig.toPath()))
            assertTrue(Files.isRegularFile(rootConfig.toPath()))
            assertEquals("listen: true\n", rootConfig.readText())
            assertEquals("listen: true\n", legacyConfig.readText())
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `ensureRootConfigLayout initializes missing root from default config`() {
        val rootDirectory = createTempDirectory(prefix = "root-config-default").toFile()
        try {
            val paths = createHostPaths(rootDirectory)
            File(paths.serverDir, "default/config.yaml").apply {
                parentFile?.mkdirs()
                writeText("port: 8000\n")
            }

            assertTrue(ensureRootConfigLayout(paths))

            assertEquals("port: 8000\n", File(paths.serverDir, "config.yaml").readText())
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `ensureRootConfigLayout keeps existing regular root config`() {
        val rootDirectory = createTempDirectory(prefix = "root-config-existing").toFile()
        try {
            val paths = createHostPaths(rootDirectory)
            File(paths.serverDir, "config.yaml").writeText("root: true\n")
            File(paths.serverDataDir, "config/config.yaml").apply {
                parentFile?.mkdirs()
                writeText("legacy: true\n")
            }

            assertFalse(ensureRootConfigLayout(paths))

            assertEquals("root: true\n", File(paths.serverDir, "config.yaml").readText())
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `ensureRootConfigLayout rejects root config directory`() {
        val rootDirectory = createTempDirectory(prefix = "root-config-dir").toFile()
        try {
            val paths = createHostPaths(rootDirectory)
            File(paths.serverDir, "config.yaml").mkdirs()

            assertThrows(BootstrapException::class.java) {
                ensureRootConfigLayout(paths)
            }
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `server archive skip only protects top level config yaml`() {
        assertTrue(shouldSkipServerArchiveEntry("config.yaml"))
        assertFalse(shouldSkipServerArchiveEntry("default/config.yaml"))
        assertFalse(shouldSkipServerArchiveEntry("config/config.yaml"))
    }

    private fun createFileSymlink(link: File, target: File): Boolean {
        return runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrDefault(false)
    }

    private fun createHostPaths(rootDirectory: File): HostPaths {
        val bootstrapRoot = File(rootDirectory, "bootstrap").apply { mkdirs() }
        val scriptsDir = File(bootstrapRoot, "scripts").apply { mkdirs() }
        val rootfsDir = File(bootstrapRoot, "rootfs").apply { mkdirs() }
        val serverDir = File(bootstrapRoot, "server").apply { mkdirs() }
        val hostPrefixDir = File(rootDirectory, "usr").apply { mkdirs() }
        val hostLibDir = File(rootDirectory, "lib").apply { mkdirs() }
        val dataRoot = File(rootDirectory, "data").apply { mkdirs() }
        return HostPaths(
            bootstrapRoot = bootstrapRoot,
            scriptsDir = scriptsDir,
            rootfsDir = rootfsDir,
            serverDir = serverDir,
            hostPrefixDir = hostPrefixDir,
            hostLibDir = hostLibDir,
            hostTmpDir = File(hostPrefixDir, "tmp").apply { mkdirs() },
            hostTermuxNodeBinary = File(hostLibDir, "libtermux-node.so"),
            hostTermuxGitBinary = File(hostLibDir, "libtermux-git.so"),
            hostTermuxGitRemoteHttpBinary = File(hostLibDir, "libtermux-git-remote-http.so"),
            hostTermuxCurlBinary = File(hostLibDir, "libtermux-curl.so"),
            hostTermuxShellBinary = File(hostLibDir, "libtermux-sh.so"),
            hostTermuxBashBinary = File(hostLibDir, "libtermux-bash.so"),
            dataRoot = dataRoot,
            serverDataDir = File(dataRoot, "server").apply { mkdirs() },
            logsDir = File(rootDirectory, "logs").apply { mkdirs() }
        )
    }
}
