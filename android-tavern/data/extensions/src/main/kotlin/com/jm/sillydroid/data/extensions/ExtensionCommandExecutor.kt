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
