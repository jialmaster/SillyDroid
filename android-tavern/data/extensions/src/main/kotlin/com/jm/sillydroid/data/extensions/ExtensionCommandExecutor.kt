package com.jm.sillydroid.data.extensions

import android.net.Uri
import com.jm.sillydroid.core.model.extensions.ExtensionRuntimeProgress
import com.jm.sillydroid.core.model.extensions.NormalizedExtensionRepository
import com.jm.sillydroid.domain.extensions.ExtensionCommandRequest
import com.jm.sillydroid.domain.extensions.ExtensionCommandRunner
import org.json.JSONObject

enum class ExtensionTargetKind {
    GLOBAL,
    USER
}

class ExtensionCommandExecutor(
    private val commandRunner: ExtensionCommandRunner,
    private val remoteManifestDataSource: RemoteManifestDataSource
) {
    fun reinstall(
        folderName: String,
        repository: NormalizedExtensionRepository,
        kind: ExtensionTargetKind = ExtensionTargetKind.GLOBAL,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ) {
        val resolvedRepository = remoteManifestDataSource.fetchResolvedRemoteManifest(repository).repository
        installRepository(
            requestPrefix = "extension-reinstall",
            folderName = folderName,
            repository = resolvedRepository,
            kind = kind,
            onProgress = onProgress,
            failureMessage = failureMessage
        )
    }

    fun install(
        folderName: String,
        repository: NormalizedExtensionRepository,
        kind: ExtensionTargetKind = ExtensionTargetKind.GLOBAL,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ) {
        installRepository(
            requestPrefix = "extension-install",
            folderName = folderName,
            repository = repository,
            kind = kind,
            onProgress = onProgress,
            failureMessage = failureMessage
        )
    }

    fun resolveExtensionFolderName(repository: NormalizedExtensionRepository): String {
        val rawFolderName = runCatching {
            Uri.parse(repository.cloneUrl).pathSegments.orEmpty().lastOrNull()
        }.getOrNull()?.let(::stripGitSuffix).orEmpty()
        return rawFolderName.replace(Regex("[^A-Za-z0-9._-]"), "")
    }

    private fun installRepository(
        requestPrefix: String,
        folderName: String,
        repository: NormalizedExtensionRepository,
        kind: ExtensionTargetKind,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)?,
        failureMessage: (String) -> String
    ) {
        val safeFolderName = folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val requestName = "$requestPrefix-$safeFolderName-${System.currentTimeMillis()}"
        val tempGuestDir = "/tavern/data/.sillydroid-maintenance/$requestName-repo"
        val guestTargetDir = when (kind) {
            ExtensionTargetKind.GLOBAL -> "/tavern/data/extensions/$folderName"
            ExtensionTargetKind.USER -> "/tavern/data/data/default-user/extensions/$folderName"
        }
        val environment = mutableMapOf(
            "SILLYDROID_EXTENSION_TARGET_DIR" to guestTargetDir,
            "SILLYDROID_EXTENSION_REPO_URL" to repository.cloneUrl,
            "SILLYDROID_EXTENSION_TEMP_DIR" to tempGuestDir
        )
        repository.branch?.let { branch ->
            environment["SILLYDROID_EXTENSION_REPO_BRANCH"] = branch
        }

        commandRunner.run(
            request = ExtensionCommandRequest(
                requestName = requestName,
                commandFileName = "$requestName.mjs",
                commandContent = extensionReinstallCommand(),
                launchScriptContent = extensionRuntimeScript(),
                environment = environment
            ),
            onProgressPayload = { payload ->
                parseRuntimeProgress(payload)?.let { progress -> onProgress?.invoke(progress) }
            },
            failureMessage = failureMessage
        )
    }

    private fun parseRuntimeProgress(rawPayload: String): ExtensionRuntimeProgress? {
        return runCatching {
            val json = JSONObject(rawPayload)
            ExtensionRuntimeProgress(
                step = json.optMeaningfulString("step"),
                phase = json.optMeaningfulString("phase"),
                loaded = json.optInt("loaded").takeIf { !json.isNull("loaded") },
                total = json.optInt("total").takeIf { !json.isNull("total") },
                indeterminate = json.optBoolean("indeterminate", false),
                message = json.optMeaningfulString("message")
            )
        }.getOrNull()
    }

    private fun JSONObject.optMeaningfulString(key: String): String? {
        return optString(key)
            .trim()
            .takeUnless { value -> value.isBlank() || value.equals("null", ignoreCase = true) }
    }

    private fun stripGitSuffix(value: String): String {
        return if (value.endsWith(".git", ignoreCase = true)) {
            value.dropLast(4)
        } else {
            value
        }
    }

    private fun extensionRuntimeScript(): String {
        return """
            #!/system/bin/sh
            set -eu

            ROOTFS_DIR="${'$'}{ROOTFS_DIR:?ROOTFS_DIR is required}"
            SERVER_DIR="${'$'}{SERVER_DIR:?SERVER_DIR is required}"
            APP_DATA_ROOT="${'$'}{APP_DATA_ROOT:?APP_DATA_ROOT is required}"
            LOGS_DIR="${'$'}{LOGS_DIR:?LOGS_DIR is required}"
            COMMAND_JS="${'$'}{COMMAND_JS:?COMMAND_JS is required}"
            SILLYDROID_EXTENSION_TARGET_DIR="${'$'}{SILLYDROID_EXTENSION_TARGET_DIR:-}"
            SILLYDROID_EXTENSION_REPO_URL="${'$'}{SILLYDROID_EXTENSION_REPO_URL:?SILLYDROID_EXTENSION_REPO_URL is required}"

            PROOT_BIN="${'$'}{HOST_PROOT_BIN:?HOST_PROOT_BIN is required}"
            PROOT_LIB_DIR="${'$'}{HOST_PROOT_LIB_DIR:?HOST_PROOT_LIB_DIR is required}"
            PROOT_LOADER_PATH="${'$'}{HOST_PROOT_LOADER:?HOST_PROOT_LOADER is required}"
            PROOT_LOADER_32_PATH="${'$'}{HOST_PROOT_LOADER_32:-}"
            HOST_PREFIX_DIR="${'$'}{HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
            HOST_RUNTIME_PREFIX="${'$'}{HOST_RUNTIME_PREFIX:-/data/data/com.termux/files/usr}"
            LINUX_FS_DIR="${'$'}ROOTFS_DIR/fs"
            PROOT_TMP_DIR="${'$'}{HOST_TMP_DIR:?HOST_TMP_DIR is required}"
            ANDROID_RESOLV_CONF="${'$'}ROOTFS_DIR/android-resolv.conf"
            SERVER_MOUNT="/tavern/server"
            DATA_MOUNT="/tavern/data"
            LOGS_MOUNT="/tavern/logs"
            GUEST_PATH="${'$'}HOST_RUNTIME_PREFIX/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            HAS_LINKERCONFIG_BIND=""

            assert_file() {
                if [ ! -f "${'$'}1" ]; then
                    echo "${'$'}2" >&2
                    exit 1
                fi
            }

            assert_dir() {
                if [ ! -d "${'$'}1" ]; then
                    echo "${'$'}2" >&2
                    exit 1
                fi
            }

            assert_file "${'$'}PROOT_BIN" "缺少 proot：${'$'}PROOT_BIN"
            assert_dir "${'$'}PROOT_LIB_DIR" "缺少 host proot 依赖目录：${'$'}PROOT_LIB_DIR"
            assert_file "${'$'}PROOT_LOADER_PATH" "缺少 host proot loader：${'$'}PROOT_LOADER_PATH"
            assert_dir "${'$'}LINUX_FS_DIR" "缺少 Linux rootfs：${'$'}LINUX_FS_DIR"
            assert_dir "${'$'}HOST_PREFIX_DIR" "缺少 host prefix 目录：${'$'}HOST_PREFIX_DIR"
            assert_file "${'$'}ANDROID_RESOLV_CONF" "缺少 Android DNS 配置：${'$'}ANDROID_RESOLV_CONF"
            assert_dir "${'$'}SERVER_DIR" "缺少 Tavern 服务目录：${'$'}SERVER_DIR"

            if [ -f /linkerconfig/ld.config.txt ]; then
                HAS_LINKERCONFIG_BIND=1
            fi

            mkdir -p "${'$'}APP_DATA_ROOT" "${'$'}LOGS_DIR" "${'$'}PROOT_TMP_DIR"
            mkdir -p "${'$'}LINUX_FS_DIR${'$'}HOST_RUNTIME_PREFIX" "${'$'}LINUX_FS_DIR/linkerconfig"
            chmod 1777 "${'$'}PROOT_TMP_DIR"

            if [ -d "${'$'}PROOT_LIB_DIR" ]; then
                export LD_LIBRARY_PATH="${'$'}PROOT_LIB_DIR${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
            fi

            export PROOT_LOADER="${'$'}PROOT_LOADER_PATH"
            if [ -n "${'$'}PROOT_LOADER_32_PATH" ]; then
                assert_file "${'$'}PROOT_LOADER_32_PATH" "缺少 host proot loader32：${'$'}PROOT_LOADER_32_PATH"
                export PROOT_LOADER_32="${'$'}PROOT_LOADER_32_PATH"
            fi

            export PROOT_TMP_DIR
            export PREFIX="${'$'}HOST_RUNTIME_PREFIX"
            export TMPDIR=/tmp
            export TMP=/tmp
            export TEMP=/tmp
            export HOME=/tmp

            export PATH="${'$'}GUEST_PATH${'$'}{PATH:+:${'$'}PATH}"

            exec "${'$'}PROOT_BIN" -r "${'$'}LINUX_FS_DIR" \
                -b /dev \
                -b /proc \
                -b /sys \
                -b /system \
                -b /apex \
                -b /vendor \
                ${'$'}{HAS_LINKERCONFIG_BIND:+-b /linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt} \
                -b "${'$'}PROOT_TMP_DIR:/tmp" \
                -b "${'$'}HOST_PREFIX_DIR:${'$'}HOST_RUNTIME_PREFIX" \
                -b "${'$'}ANDROID_RESOLV_CONF:/etc/resolv.conf" \
                -b "${'$'}SERVER_DIR:${'$'}SERVER_MOUNT" \
                -b "${'$'}APP_DATA_ROOT:${'$'}DATA_MOUNT" \
                -b "${'$'}LOGS_DIR:${'$'}LOGS_MOUNT" \
                -w "${'$'}SERVER_MOUNT" \
                /bin/sh -lc 'cd /tavern/server; NODE_BIN="$(command -v node || true)"; if [ -z "${'$'}NODE_BIN" ] || [ ! -x "${'$'}NODE_BIN" ]; then NODE_BIN="./node/bin/node"; fi; if [ -z "${'$'}NODE_BIN" ] || [ ! -x "${'$'}NODE_BIN" ]; then echo "缺少可执行的 Node runtime，请确认已导入 node 依赖包。" >&2; exit 1; fi; "${'$'}NODE_BIN" "${'$'}COMMAND_JS"'
        """.trimIndent()
    }

    private fun extensionReinstallCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import git from 'isomorphic-git';
            import http from 'isomorphic-git/http/node';

            const targetDir = process.env.SILLYDROID_EXTENSION_TARGET_DIR;
            const repoUrl = process.env.SILLYDROID_EXTENSION_REPO_URL;
            const repoBranch = process.env.SILLYDROID_EXTENSION_REPO_BRANCH || undefined;
            const tempDir = process.env.SILLYDROID_EXTENSION_TEMP_DIR;
            if (!targetDir || !repoUrl || !tempDir) {
                throw new Error('Missing extension target or repository URL.');
            }

            ${extensionProgressHelpers()}

            const parentDir = path.dirname(targetDir);
            const backupDir = targetDir + '.sillydroid-backup-' + Date.now();
            fs.mkdirSync(parentDir, { recursive: true });

            try {
                writeProgress({ step: 'prepare', indeterminate: true });
                if (fs.existsSync(tempDir)) {
                    fs.rmSync(tempDir, { recursive: true, force: true });
                }

                writeProgress({ step: 'clone', indeterminate: true });
                await git.clone({
                    fs,
                    http,
                    dir: tempDir,
                    url: repoUrl,
                    depth: 1,
                    ref: repoBranch,
                    singleBranch: true,
                    onProgress: event => {
                        writeProgress({
                            step: 'clone',
                            phase: event.phase,
                            loaded: event.loaded,
                            total: event.total,
                        });
                    },
                });

                writeProgress({ step: 'validate', indeterminate: true });
                const manifestPath = path.join(tempDir, 'manifest.json');
                if (!fs.existsSync(manifestPath)) {
                    throw new Error('Manifest file not found at ' + manifestPath);
                }

                JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

                writeProgress({ step: 'backup', indeterminate: true });
                if (fs.existsSync(targetDir)) {
                    fs.renameSync(targetDir, backupDir);
                }

                writeProgress({ step: 'updating', indeterminate: true });
                fs.renameSync(tempDir, targetDir);
                writeProgress({ step: 'completed', loaded: 1, total: 1 });

                if (fs.existsSync(backupDir)) {
                    fs.rmSync(backupDir, { recursive: true, force: true });
                }
            } catch (error) {
                if (fs.existsSync(targetDir)) {
                    fs.rmSync(targetDir, { recursive: true, force: true });
                }

                if (fs.existsSync(backupDir)) {
                    fs.renameSync(backupDir, targetDir);
                }

                if (fs.existsSync(tempDir)) {
                    fs.rmSync(tempDir, { recursive: true, force: true });
                }

                throw error;
            } finally {
                if (fs.existsSync(tempDir)) {
                    fs.rmSync(tempDir, { recursive: true, force: true });
                }
            }
        """.trimIndent()
    }

    private fun extensionProgressHelpers(): String {
        return """
            const progressFile = process.env.SILLYDROID_EXTENSION_PROGRESS_FILE || null;

            function writeProgress({ step = null, phase = null, loaded = null, total = null, indeterminate = false, message = null }) {
                if (!progressFile) {
                    return;
                }

                const payload = {
                    step,
                    phase,
                    loaded: Number.isFinite(loaded) ? loaded : null,
                    total: Number.isFinite(total) ? total : null,
                    indeterminate: Boolean(indeterminate),
                    message,
                    updatedAt: Date.now(),
                };

                const tempFile = progressFile + '.tmp';
                fs.writeFileSync(tempFile, JSON.stringify(payload));
                fs.renameSync(tempFile, progressFile);
            }
        """.trimIndent()
    }
}
