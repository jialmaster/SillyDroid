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
            assertFalse(spec.environment.containsKey("SILLYDROID_CONSOLE_WORKDIR"))
            assertFalse(spec.environment.containsKey("SILLYDROID_CONSOLE_HOME"))
            assertFalse(spec.environment.containsKey("SILLYDROID_CONSOLE_PROMPT"))
            assertEquals("xterm-256color", spec.environment["TERM"])
            assertEquals("truecolor", spec.environment["COLORTERM"])
            assertEquals(paths.serverDataDir.absolutePath, spec.environment["APP_DATA_ROOT"])
            assertEquals(paths.rootfsDir.absolutePath, spec.environment["ROOTFS_DIR"])
            assertEquals(paths.serverDir.absolutePath, spec.environment["SERVER_DIR"])
            assertEquals(paths.logsDir.absolutePath, spec.environment["LOGS_DIR"])
            assertEquals(paths.hostPrefixDir.absolutePath, spec.environment["HOST_PREFIX_DIR"])
            assertEquals(BootConfig.guestRuntimePrefix, spec.environment["HOST_RUNTIME_PREFIX"])
            assertEquals(paths.hostLibDir.absolutePath, spec.environment["HOST_NATIVE_LIB_DIR"])
            assertEquals(paths.hostTmpDir.absolutePath, spec.environment["HOST_TMP_DIR"])
            assertEquals(paths.hostTermuxNodeBinary.absolutePath, spec.environment["TERMUX_NODE_BIN"])
            assertEquals(paths.hostTermuxGitBinary.absolutePath, spec.environment["TERMUX_GIT_BIN"])
            assertEquals(paths.hostTermuxGitRemoteHttpBinary.absolutePath, spec.environment["TERMUX_GIT_REMOTE_HTTP_BIN"])
            assertEquals(paths.hostTermuxCurlBinary.absolutePath, spec.environment["TERMUX_CURL_BIN"])
            assertEquals(paths.hostTermuxShellBinary.absolutePath, spec.environment["TERMUX_SH_BIN"])
            assertFalse(spec.environment.containsKey("TERMUX_NPM_BIN"))
            assertFalse(spec.environment.containsKey("TERMUX_NPX_BIN"))
            assertFalse(spec.environment.containsKey("HOST_PROOT_BIN"))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `terminal script exposes npm through bash functions without native npm entrypoints`() {
        val script = resolveAndroidProjectFile(
            "app/src/main/assets/bootstrap/scripts/start-console-shell.sh"
        ).readText()

        assertTrue(script.contains("sillydroid_run_npm_cli()"))
        assertTrue(script.contains("sillydroid_run_npx_cli()"))
        assertTrue(script.contains("npm()"))
        assertTrue(script.contains("npx()"))
        assertTrue(script.contains("""${'$'}HOST_PREFIX_DIR/lib/node_modules/npm/bin/npm-cli.js"""))
        assertTrue(script.contains("""${'$'}HOST_PREFIX_DIR/lib/node_modules/npm/bin/npx-cli.js"""))
        assertTrue(script.contains("""PREFIX="${'$'}HOST_PREFIX_DIR" SHELL="${'$'}TERMUX_SH_BIN" npm_config_script_shell="${'$'}TERMUX_SH_BIN" NPM_CONFIG_SCRIPT_SHELL="${'$'}TERMUX_SH_BIN" command "${'$'}TERMUX_NODE_BIN" "${'$'}npm_cli" "${'$'}@""""))
        assertTrue(script.contains("""PREFIX="${'$'}HOST_PREFIX_DIR" SHELL="${'$'}TERMUX_SH_BIN" npm_config_script_shell="${'$'}TERMUX_SH_BIN" NPM_CONFIG_SCRIPT_SHELL="${'$'}TERMUX_SH_BIN" command "${'$'}TERMUX_NODE_BIN" "${'$'}npx_cli" "${'$'}@""""))
        assertFalse(script.contains("npm_config_optional=false command"))
        assertFalse(script.contains("--omit=optional"))
        assertFalse(script.contains("--ignore-scripts"))
        assertFalse(script.contains("corepack/dist/npm.js"))
        assertFalse(script.contains("process.argv.splice"))
        assertFalse(script.contains("SILLYDROID_NPM_CLI"))
        assertFalse(script.contains("TERMUX_NPM_BIN"))
        assertFalse(script.contains("TERMUX_NPX_BIN"))
        assertFalse(script.contains("libtermux-npm.so"))
    }

    @Test
    fun `termux host runtime injects npm lifecycle android shim without native npm entrypoints`() {
        val script = resolveAndroidProjectFile(
            "app/src/main/assets/bootstrap/scripts/termux-host-runtime.sh"
        ).readText()
        val shim = resolveAndroidProjectFile(
            "app/src/main/assets/bootstrap/scripts/npm-lifecycle-android-shim.cjs"
        ).readText()

        assertTrue(script.contains("npm-lifecycle-android-shim.cjs"))
        assertTrue(script.contains("""npm_lifecycle_android_shim="${'$'}(cd "${'$'}BOOTSTRAP_ROOT/scripts" 2>/dev/null && pwd -P)/npm-lifecycle-android-shim.cjs""""))
        assertTrue(script.contains("""NODE_OPTIONS="--require ${'$'}npm_lifecycle_android_shim ${'$'}{NODE_OPTIONS:-}""""))
        assertTrue(script.contains("SILLYDROID_HOST_COMMAND_PROFILE"))
        assertTrue(script.contains("install_termux_server_fast_command_links"))
        assertTrue(script.contains("""export SILLYDROID_HOST_COMMAND_PATH="${'$'}HOST_TMP_DIR/server-fast-bin""""))
        assertTrue(script.contains("""export SHELL="${'$'}TERMUX_SH_BIN""""))
        assertTrue(script.contains("""export npm_config_script_shell="${'$'}TERMUX_SH_BIN""""))
        assertTrue(script.contains("""export NPM_CONFIG_SCRIPT_SHELL="${'$'}TERMUX_SH_BIN""""))
        assertTrue(script.contains("""export GIT_CONFIG_NOSYSTEM=1"""))
        assertTrue(script.contains("""export GIT_ATTR_NOSYSTEM=1"""))
        assertTrue(script.contains("ensure_openssl_conf()"))
        assertTrue(script.contains("""fallback_conf="${'$'}HOST_TMP_DIR/openssl.cnf""""))
        assertTrue(script.contains("""export OPENSSL_CONF="${'$'}bundled_conf""""))
        assertTrue(script.contains("""export OPENSSL_CONF="${'$'}fallback_conf""""))
        assertTrue(script.contains("独立包名下若没有显式配置，会回退去读旧路径并触发 Permission denied"))
        assertTrue(script.contains("node_modules/.bin"))
        assertFalse(script.contains("TERMUX_NPM_BIN"))
        assertFalse(script.contains("libtermux-npm.so"))
        assertTrue(shim.contains("lib/node_modules/npm/bin/npm-cli.js"))
        assertTrue(shim.contains("node_modules/.bin"))
        assertTrue(shim.contains("prebuild-install"))
        assertTrue(shim.contains("node-gyp is not supported in SillyDroid Android runtime"))
        assertTrue(shim.contains("sh -c --"))
        assertTrue(shim.contains("args[cIndex + 1] === '--' ? cIndex + 2 : cIndex + 1"))
        assertTrue(shim.contains("process.env.TERMUX_SH_BIN"))
        assertTrue(shim.contains("commandName(command) === 'libtermux-sh.so'"))
        assertTrue(shim.contains("process.env.npm_lifecycle_event"))
        assertTrue(shim.contains("withAndroidShell(options)"))
        assertTrue(shim.contains("childProcess.execSync"))
        assertTrue(shim.contains("rewriteShellArgs(command, args, options)"))
        assertTrue(shim.contains("childProcess.spawn"))
        assertTrue(shim.contains("childProcess.spawnSync"))
        assertTrue(shim.contains("Third-party server plugins often pass a partial env"))
        assertTrue(shim.contains("function mergeAndroidRuntimeEnv(env)"))
        assertTrue(shim.contains("'PATH'"))
        assertTrue(shim.contains("'GIT_EXEC_PATH'"))
        assertTrue(shim.contains("'OPENSSL_CONF'"))
        assertTrue(shim.contains("'SILLYDROID_HOST_COMMAND_PATH'"))
        assertTrue(shim.contains("options = withAndroidRuntimeEnv(options)"))
        assertFalse(shim.contains("npm_config_optional"))
    }

    @Test
    fun `server startup defaults to fast host command profile without changing tavern config`() {
        val startServerScript = resolveAndroidProjectFile(
            "app/src/main/assets/bootstrap/scripts/start-server.sh"
        ).readText()
        val entrypointScript = resolveAndroidProjectFile(
            "app/src/main/assets/bootstrap/scripts/tavern-entrypoint.sh"
        ).readText()

        assertTrue(startServerScript.contains("""SILLYDROID_HOST_COMMAND_PROFILE="${'$'}{SILLYDROID_HOST_COMMAND_PROFILE:-server-fast}""""))
        assertTrue(startServerScript.contains("export SILLYDROID_HOST_COMMAND_PROFILE"))
        assertFalse(entrypointScript.contains("enableServerPluginsAutoUpdate"))
        assertFalse(entrypointScript.contains("config.yaml.sillydroid-fast"))
    }

    @Test
    fun `runtime build scripts require preinstalled npm cli assets`() {
        val syncScript = resolveWorkspaceFile("scripts/sync-android-rootfs.sh").readText()
        val runtimeImageScript = resolveWorkspaceFile("scripts/build-tavern-android-runtime-image.sh").readText()
        val apkScript = resolveWorkspaceFile("scripts/build-tavern-android-apk.sh").readText()
        val buildConfig = resolveWorkspaceFile("sillydroid-build-config.json").readText()

        assertTrue(syncScript.contains("npm"))
        assertTrue(syncScript.contains("lib/node_modules/npm/lib/cli.js"))
        assertTrue(syncScript.contains("lib/node_modules/npm/bin/npm-cli.js"))
        assertTrue(syncScript.contains("lib/node_modules/npm/bin/npx-cli.js"))
        assertTrue(syncScript.contains("缺少预置 npm CLI"))
        assertTrue(syncScript.contains("""rm -f "${'$'}prefix_root/bin/npm" "${'$'}prefix_root/bin/npx""""))
        assertTrue(syncScript.contains("写入 bin/npm 时误覆盖了 npm 包本体"))
        assertTrue(syncScript.contains("\"archiveSha256\""))
        assertFalse(syncScript.contains("corepack"))
        assertFalse(syncScript.contains("corepack_npm_cli_relative_path"))
        assertTrue(runtimeImageScript.contains("lib/node_modules/npm/lib/cli.js"))
        assertTrue(runtimeImageScript.contains("lib/node_modules/npm/bin/npm-cli.js"))
        assertTrue(runtimeImageScript.contains("lib/node_modules/npm/bin/npx-cli.js"))
        assertTrue(apkScript.contains("lib/node_modules/npm/lib/cli.js"))
        assertTrue(apkScript.contains("lib/node_modules/npm/bin/npm-cli.js"))
        assertTrue(apkScript.contains("lib/node_modules/npm/bin/npx-cli.js"))
        assertTrue(buildConfig.contains("\"npm\""))
    }

    @Test
    fun `tavern entrypoint preserves plugin symlink resolution for server plugins`() {
        val script = resolveAndroidProjectFile(
            "app/src/main/assets/bootstrap/scripts/tavern-entrypoint.sh"
        ).readText()

        assertTrue(script.contains("plugins 持久化到 TAVERN_DATA_ROOT 后再软链接回 SillyTavern 根目录"))
        assertTrue(script.contains("--preserve-symlinks"))
        assertTrue(script.contains("""NODE_OPTIONS="--preserve-symlinks ${'$'}{NODE_OPTIONS:-}""""))
        assertTrue(script.contains("export NODE_OPTIONS"))
    }

    @Test
    fun `rootfs refresh owns host prefix extraction so npm assets reach files usr`() {
        val runtimeSource = resolveAndroidProjectFile(
            "data/runtime/src/main/kotlin/com/jm/sillydroid/data/runtime/HostRuntime.kt"
        ).readText()

        assertTrue(runtimeSource.contains("refreshHostPrefixDirectory(paths, rootfsDirectoryRefreshed)"))
        assertTrue(runtimeSource.contains("refreshHostPrefixDirectory(paths, rootfsDirectoryRefreshed = false)"))
        assertTrue(runtimeSource.contains("lib/node_modules/npm/lib/cli.js"))
        assertTrue(runtimeSource.contains("lib/node_modules/npm/bin/npm-cli.js"))
        assertFalse(runtimeSource.contains("lib/node_modules/corepack/dist/npm.js"))
        assertTrue(runtimeSource.contains("rootfs manifest 与 host prefix 已同步完成"))
        assertFalse(runtimeSource.contains("refreshHostPrefixDirectory(paths, rootfsAssetsRefreshed)"))
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
    fun `buildHostRuntimeEnvironment reports termux host runtime mode from manifest`() {
        val rootDirectory = createTempTestDirectory(prefix = "host-runtime-mode")
        try {
            val paths = createHostPaths(rootDirectory)
            File(paths.rootfsDir, "rootfs-manifest.json").writeText("""{"runtimeMode":"termux-host"}""")

            val environment = buildHostRuntimeEnvironment(paths)

            assertEquals("termux-host", environment["SILLYDROID_RUNTIME_MODE"])
            assertEquals(paths.hostTermuxNodeBinary.absolutePath, environment["TERMUX_NODE_BIN"])
            assertEquals(paths.hostTermuxGitBinary.absolutePath, environment["TERMUX_GIT_BIN"])
            assertEquals(paths.hostTermuxGitRemoteHttpBinary.absolutePath, environment["TERMUX_GIT_REMOTE_HTTP_BIN"])
            assertEquals(paths.hostTermuxCurlBinary.absolutePath, environment["TERMUX_CURL_BIN"])
            assertEquals(paths.hostTermuxShellBinary.absolutePath, environment["TERMUX_SH_BIN"])
            assertEquals(paths.hostTermuxBashBinary.absolutePath, environment["TERMUX_BASH_BIN"])
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
                File(this, "libtermux-node.so").writeText("missing git and shell")
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

private fun resolveWorkspaceFile(relativePath: String): File {
    generateSequence(File("").absoluteFile) { directory -> directory.parentFile }
        .map { directory -> File(directory, relativePath) }
        .firstOrNull { candidate -> candidate.isFile }
        ?.let { return it }

    throw AssertionError("Cannot resolve workspace file: $relativePath")
}

private fun resolveAndroidProjectFile(relativePath: String): File {
    return resolveWorkspaceFile("android-tavern/$relativePath")
}

private fun File.writeHostRuntimeFiles() {
    File(this, "libtermux-node.so").apply {
        writeText("node")
        setExecutable(true)
    }
    File(this, "libtermux-git.so").apply {
        writeText("git")
        setExecutable(true)
    }
    File(this, "libtermux-git-remote-http.so").apply {
        writeText("git-remote-http")
        setExecutable(true)
    }
    File(this, "libtermux-curl.so").apply {
        writeText("curl")
        setExecutable(true)
    }
    File(this, "libtermux-sh.so").apply {
        writeText("shell")
        setExecutable(true)
    }
}

private fun createHostPaths(rootDirectory: File): HostPaths {
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
    File(scriptsDir, "start-console-shell.sh").writeText("#!/system/bin/sh")

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
