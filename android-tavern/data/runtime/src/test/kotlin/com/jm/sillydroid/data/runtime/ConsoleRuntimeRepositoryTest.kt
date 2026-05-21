package com.jm.sillydroid.data.runtime

import android.content.pm.ApplicationInfo
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleRuntimeRepositoryTest {

    @Test
    fun `readInstalledRootfsGuestShellPath falls back to sh when manifest missing or blank`() {
        val rootDirectory = createTempTestDirectory(prefix = "rootfs-manifest-fallback")
        try {
            val missingManifestPaths = createHostPaths(rootDirectory)
            assertEquals("/bin/sh", readInstalledRootfsGuestShellPath(missingManifestPaths))

            val blankManifestRoot = File(rootDirectory, "blank").apply { mkdirs() }
            val blankManifestPaths = createHostPaths(blankManifestRoot)
            File(blankManifestPaths.rootfsDir, "rootfs-manifest.json").writeText("""{"guestShellPath":"   "}""")

            assertEquals("/bin/sh", readInstalledRootfsGuestShellPath(blankManifestPaths))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `buildConsoleShellLaunchSpec uses shared host runtime contract and terminal defaults`() {
        val rootDirectory = createTempTestDirectory(prefix = "console-launch-spec")
        try {
            val paths = createHostPaths(rootDirectory)
            val spec = buildConsoleShellLaunchSpec(paths, "/bin/bash")
            val scriptPath = File(paths.scriptsDir, "start-console-shell.sh").absolutePath

            assertEquals("/system/bin/sh", spec.shellPath)
            assertEquals(paths.bootstrapRoot.absolutePath, spec.workingDirectory)
            assertEquals(listOf("/system/bin/sh", scriptPath), spec.arguments)
            assertEquals(10_000, spec.transcriptRows)
            assertEquals("/bin/bash", spec.environment["SILLYDROID_GUEST_SHELL_PATH"])
            assertEquals("/tavern/server", spec.environment["SILLYDROID_CONSOLE_WORKDIR"])
            assertEquals("/tavern/data/.sillydroid-terminal-home", spec.environment["SILLYDROID_CONSOLE_HOME"])
            assertEquals("${'$'}PWD > ", spec.environment["SILLYDROID_CONSOLE_PROMPT"])
            assertEquals("xterm-256color", spec.environment["TERM"])
            assertEquals("truecolor", spec.environment["COLORTERM"])
            assertEquals(paths.serverDataDir.absolutePath, spec.environment["APP_DATA_ROOT"])
            assertEquals(paths.rootfsDir.absolutePath, spec.environment["ROOTFS_DIR"])
            assertEquals(paths.serverDir.absolutePath, spec.environment["SERVER_DIR"])
            assertEquals(paths.logsDir.absolutePath, spec.environment["LOGS_DIR"])
            assertEquals(paths.hostPrefixDir.absolutePath, spec.environment["HOST_PREFIX_DIR"])
            assertEquals(BootConfig.guestRuntimePrefix, spec.environment["HOST_RUNTIME_PREFIX"])
            assertEquals(paths.hostProotBinary.absolutePath, spec.environment["HOST_PROOT_BIN"])
            assertEquals(paths.hostLibDir.absolutePath, spec.environment["HOST_PROOT_LIB_DIR"])
            assertEquals(paths.hostProotLoader.absolutePath, spec.environment["HOST_PROOT_LOADER"])
            assertEquals(paths.hostTmpDir.absolutePath, spec.environment["HOST_TMP_DIR"])
            assertFalse(spec.environment.containsKey("HOST_PROOT_LOADER_32"))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `resolveConsoleGuestShellPath upgrades sh to bash for terminal history support when bash exists`() {
        val rootDirectory = createTempTestDirectory(prefix = "console-guest-shell")
        try {
            val paths = createHostPaths(rootDirectory)
            File(paths.rootfsDir, "fs/bin").mkdirs()
            File(paths.rootfsDir, "fs/bin/bash").writeText("bash")

            assertEquals("/bin/bash", resolveConsoleGuestShellPath(paths, "/bin/sh"))
            assertEquals("/bin/zsh", resolveConsoleGuestShellPath(paths, "/bin/zsh"))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `normalizeBootstrapManifestForComparison ignores syncedAtUtc only`() {
        val left = """
            {
              "runtimeVersion": "1.0.0",
              "syncedAtUtc": "2026-05-17T11:33:48Z",
              "guestShellPath": "/bin/sh"
            }
        """.trimIndent()
        val right = """
            {
              "runtimeVersion": "1.0.0",
              "syncedAtUtc": "2026-05-17T19:44:12Z",
              "guestShellPath": "/bin/sh"
            }
        """.trimIndent()
        val changedShell = """
            {
              "runtimeVersion": "1.0.0",
              "syncedAtUtc": "2026-05-17T19:44:12Z",
              "guestShellPath": "/bin/bash"
            }
        """.trimIndent()

        assertEquals(
            normalizeBootstrapManifestForComparison(left),
            normalizeBootstrapManifestForComparison(right)
        )
        assertTrue(
            normalizeBootstrapManifestForComparison(left) !=
                normalizeBootstrapManifestForComparison(changedShell)
        )
    }

    @Test
    fun `buildHostRuntimeEnvironment includes loader32 when present`() {
        val rootDirectory = createTempTestDirectory(prefix = "host-runtime-env")
        try {
            val paths = createHostPaths(rootDirectory, includeLoader32 = true)

            val environment = buildHostRuntimeEnvironment(paths)

            assertTrue(environment.containsKey("HOST_PROOT_LOADER_32"))
            assertEquals(paths.hostProotLoader32.absolutePath, environment["HOST_PROOT_LOADER_32"])
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `selectHostRuntimeDirectory keeps native directory when package manager extracted host runtime`() {
        val rootDirectory = createTempTestDirectory(prefix = "host-runtime-native")
        try {
            val nativeHostLibDir = File(rootDirectory, "native").apply {
                mkdirs()
                writeHostRuntimeFiles()
            }
            val packageHostLibDir = File(rootDirectory, "package/lib/arm64")

            assertEquals(
                nativeHostLibDir,
                selectHostRuntimeDirectory(
                    nativeHostLibDir = nativeHostLibDir,
                    packageHostLibDirs = listOf(packageHostLibDir),
                    forcePackageHostRuntime = false
                )
            )
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `selectHostRuntimeDirectory uses package lib directory when native directory is incomplete`() {
        val rootDirectory = createTempTestDirectory(prefix = "host-runtime-package")
        try {
            val nativeHostLibDir = File(rootDirectory, "native").apply {
                mkdirs()
                File(this, "libproot.so").writeText("missing loader and talloc")
            }
            val packageHostLibDir = File(rootDirectory, "package/lib/arm64").apply {
                mkdirs()
                writeHostRuntimeFiles()
            }

            assertEquals(
                packageHostLibDir,
                selectHostRuntimeDirectory(
                    nativeHostLibDir = nativeHostLibDir,
                    packageHostLibDirs = listOf(packageHostLibDir),
                    forcePackageHostRuntime = false
                )
            )
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `selectHostRuntimeDirectory force mode ignores native directory for device fallback repro`() {
        val rootDirectory = createTempTestDirectory(prefix = "host-runtime-force-select")
        try {
            val nativeHostLibDir = File(rootDirectory, "native").apply {
                mkdirs()
                writeHostRuntimeFiles()
            }
            val packageHostLibDir = File(rootDirectory, "package/lib/arm64").apply {
                mkdirs()
                writeHostRuntimeFiles()
            }

            assertEquals(
                packageHostLibDir,
                selectHostRuntimeDirectory(
                    nativeHostLibDir = nativeHostLibDir,
                    packageHostLibDirs = listOf(packageHostLibDir),
                    forcePackageHostRuntime = true
                )
            )
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `shouldForcePackageHostRuntime only accepts debug marker`() {
        val rootDirectory = createTempTestDirectory(prefix = "host-runtime-force")
        try {
            val bootstrapRoot = File(rootDirectory, "bootstrap").apply { mkdirs() }

            assertFalse(shouldForcePackageHostRuntime(ApplicationInfo.FLAG_DEBUGGABLE, bootstrapRoot))

            File(bootstrapRoot, ".force-package-host-runtime").writeText("1")

            assertFalse(shouldForcePackageHostRuntime(0, bootstrapRoot))
            assertTrue(shouldForcePackageHostRuntime(ApplicationInfo.FLAG_DEBUGGABLE, bootstrapRoot))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `resolvePackageHostRuntimeDirectories maps apk source directory to installed arm64 lib directories`() {
        val rootDirectory = createTempTestDirectory(prefix = "host-runtime-package-dirs")
        try {
            val installRoot = File(rootDirectory, "package").apply { mkdirs() }
            val apkFile = File(installRoot, "base.apk")
            val nativeHostLibDir = File(installRoot, "lib/arm64").apply { mkdirs() }

            val resolved = resolvePackageHostRuntimeDirectories(
                sourceDirs = listOf(apkFile),
                nativeHostLibDir = nativeHostLibDir
            )

            assertTrue(resolved.contains(File(installRoot, "lib/arm64")))
            assertTrue(resolved.contains(File(installRoot, "lib/arm64-v8a")))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }
}

private fun createTempTestDirectory(prefix: String): File {
    return createTempDirectory(prefix = prefix).toFile()
}

private fun File.writeHostRuntimeFiles() {
    File(this, "libtalloc_2.so").writeText("talloc")
    File(this, "libproot.so").apply {
        writeText("proot")
        setExecutable(true)
    }
    File(this, "libproot-loader.so").apply {
        writeText("loader")
        setExecutable(true)
    }
}

private fun createHostPaths(rootDirectory: File, includeLoader32: Boolean = false): HostPaths {
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
    if (includeLoader32) {
        hostProotLoader32.writeText("loader32")
    }
    File(scriptsDir, "start-console-shell.sh").writeText("#!/system/bin/sh")

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
