package com.jm.sillydroid.data.extensions

import android.net.Uri
import com.jm.sillydroid.core.model.extensions.ExtensionRuntimeProgress
import com.jm.sillydroid.core.model.extensions.NormalizedExtensionRepository
import com.jm.sillydroid.domain.extensions.ExtensionCommandRequest
import com.jm.sillydroid.domain.extensions.ExtensionCommandRunner
import org.json.JSONObject

enum class ExtensionTargetKind {
    GLOBAL,
    USER,
    SERVER_PLUGIN
}

data class RepositoryUpdateProbe(
    val hasNewVersion: Boolean,
    val localRevision: String?,
    val remoteRevision: String?,
    val localVersion: String?,
    val remoteVersion: String?,
    val versionSourceFile: String?
)

data class PluginInstallProbe(
    val displayName: String,
    val version: String?,
    val description: String?
)

/**
 * 负责把设置页里的扩展、后端插件维护操作转换为宿主 Node 命令。
 * 允许做仓库克隆、预检、安装、更新与依赖安装；不允许把可选元数据文件提升为所有插件的必选契约。
 */
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

    fun checkRepositoryUpdate(
        folderName: String,
        repository: NormalizedExtensionRepository,
        kind: ExtensionTargetKind,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ): RepositoryUpdateProbe {
        val safeFolderName = folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val requestName = "repository-update-check-$safeFolderName-${System.currentTimeMillis()}"
        val tempGuestDir = "/tavern/data/.sillydroid-maintenance/$requestName-repo"
        val guestTargetDir = resolveGuestTargetDir(folderName, kind)
        val environment = mutableMapOf(
            "SILLYDROID_EXTENSION_TARGET_DIR" to guestTargetDir,
            "SILLYDROID_EXTENSION_REPO_URL" to repository.cloneUrl,
            "SILLYDROID_EXTENSION_TEMP_DIR" to tempGuestDir,
            "SILLYDROID_EXTENSION_VERSION_FILE" to when (kind) {
                ExtensionTargetKind.SERVER_PLUGIN -> "package.json"
                else -> "manifest.json"
            }
        )
        repository.branch?.let { branch ->
            environment["SILLYDROID_EXTENSION_REPO_BRANCH"] = branch
        }

        val result = commandRunner.run(
            request = ExtensionCommandRequest(
                requestName = requestName,
                commandFileName = "$requestName.mjs",
                commandContent = repositoryUpdateProbeCommand(),
                environment = environment
            ),
            onProgressPayload = { payload ->
                parseRuntimeProgress(payload)?.let { progress -> onProgress?.invoke(progress) }
            },
            failureMessage = failureMessage
        )
        val payload = result.logPath.takeUnless { it.isBlank() }?.let { logPath ->
            runCatching { java.io.File(logPath).readText() }.getOrNull()
        }.orEmpty()
        val markerPayload = Regex("SILLYDROID_REPOSITORY_UPDATE_RESULT=(\\{.*\\})")
            .find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IllegalStateException("无法读取仓库更新检测结果。")
        val json = JSONObject(markerPayload)
        return RepositoryUpdateProbe(
            hasNewVersion = json.optBoolean("hasNewVersion", false),
            localRevision = json.optMeaningfulString("localRevision"),
            remoteRevision = json.optMeaningfulString("remoteRevision"),
            localVersion = json.optMeaningfulString("localVersion"),
            remoteVersion = json.optMeaningfulString("remoteVersion"),
            versionSourceFile = json.optMeaningfulString("versionSourceFile")
        )
    }

    fun installPlugin(
        folderName: String,
        repository: NormalizedExtensionRepository,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ) {
        installRepository(
            requestPrefix = "plugin-install",
            folderName = folderName,
            repository = repository,
            kind = ExtensionTargetKind.SERVER_PLUGIN,
            runNpmInstall = true,
            onProgress = onProgress,
            failureMessage = failureMessage
        )
    }

    fun previewPluginInstall(
        folderName: String,
        repository: NormalizedExtensionRepository,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ): PluginInstallProbe {
        val safeFolderName = folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val requestName = "plugin-preview-$safeFolderName-${System.currentTimeMillis()}"
        val tempGuestDir = "/tavern/data/.sillydroid-maintenance/$requestName-repo"
        val environment = mutableMapOf(
            "SILLYDROID_EXTENSION_REPO_URL" to repository.cloneUrl,
            "SILLYDROID_EXTENSION_TEMP_DIR" to tempGuestDir
        )
        repository.branch?.let { branch ->
            environment["SILLYDROID_EXTENSION_REPO_BRANCH"] = branch
        }

        val result = commandRunner.run(
            request = ExtensionCommandRequest(
                requestName = requestName,
                commandFileName = "$requestName.mjs",
                commandContent = pluginInstallPreviewCommand(),
                environment = environment
            ),
            onProgressPayload = { payload ->
                parseRuntimeProgress(payload)?.let { progress -> onProgress?.invoke(progress) }
            },
            failureMessage = failureMessage
        )
        val payload = result.logPath.takeUnless { it.isBlank() }?.let { logPath ->
            runCatching { java.io.File(logPath).readText() }.getOrNull()
        }.orEmpty()
        val markerPayload = Regex("SILLYDROID_PLUGIN_INSTALL_PREVIEW=(\\{.*\\})")
            .find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IllegalStateException("无法读取后端插件预检结果。")
        val json = JSONObject(markerPayload)
        return PluginInstallProbe(
            displayName = json.optMeaningfulString("displayName") ?: folderName,
            version = json.optMeaningfulString("version"),
            description = json.optMeaningfulString("description")
        )
    }

    fun updatePluginRepository(
        folderName: String,
        repository: NormalizedExtensionRepository,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ) {
        installRepository(
            requestPrefix = "plugin-git-update",
            folderName = folderName,
            repository = repository,
            kind = ExtensionTargetKind.SERVER_PLUGIN,
            runNpmInstall = false,
            onProgress = onProgress,
            failureMessage = failureMessage
        )
    }

    fun installPluginDependencies(
        folderName: String,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ) {
        val safeFolderName = folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val requestName = "server-plugin-npm-install-$safeFolderName-${System.currentTimeMillis()}"
        commandRunner.run(
            request = ExtensionCommandRequest(
                requestName = requestName,
                commandFileName = "$requestName.mjs",
                commandContent = singlePluginDependencyInstallCommand(),
                environment = mapOf("SILLYDROID_PLUGIN_FOLDER_NAME" to folderName)
            ),
            onProgressPayload = { payload ->
                parseRuntimeProgress(payload)?.let { progress -> onProgress?.invoke(progress) }
            },
            failureMessage = failureMessage
        )
    }

    fun installServerPluginDependencies(
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ) {
        val requestName = "server-plugin-npm-install-${System.currentTimeMillis()}"
        commandRunner.run(
            request = ExtensionCommandRequest(
                requestName = requestName,
                commandFileName = "$requestName.mjs",
                commandContent = serverPluginDependencyInstallCommand(),
                environment = emptyMap()
            ),
            onProgressPayload = { payload ->
                parseRuntimeProgress(payload)?.let { progress -> onProgress?.invoke(progress) }
            },
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
        runNpmInstall: Boolean = false,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)?,
        failureMessage: (String) -> String
    ) {
        val safeFolderName = folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val requestName = "$requestPrefix-$safeFolderName-${System.currentTimeMillis()}"
        val tempGuestDir = "/tavern/data/.sillydroid-maintenance/$requestName-repo"
        val guestTargetDir = resolveGuestTargetDir(folderName, kind)
        val environment = mutableMapOf(
            "SILLYDROID_EXTENSION_TARGET_DIR" to guestTargetDir,
            "SILLYDROID_EXTENSION_REPO_URL" to repository.cloneUrl,
            "SILLYDROID_EXTENSION_TEMP_DIR" to tempGuestDir,
            "SILLYDROID_EXTENSION_RUN_NPM_INSTALL" to if (runNpmInstall) "1" else "0",
            "SILLYDROID_EXTENSION_TARGET_KIND" to kind.name
        )
        repository.branch?.let { branch ->
            environment["SILLYDROID_EXTENSION_REPO_BRANCH"] = branch
        }

        commandRunner.run(
            request = ExtensionCommandRequest(
                requestName = requestName,
                commandFileName = "$requestName.mjs",
                commandContent = repositoryInstallCommand(),
                environment = environment
            ),
            onProgressPayload = { payload ->
                parseRuntimeProgress(payload)?.let { progress -> onProgress?.invoke(progress) }
            },
            failureMessage = failureMessage
        )
    }

    private fun resolveGuestTargetDir(folderName: String, kind: ExtensionTargetKind): String {
        return when (kind) {
            ExtensionTargetKind.GLOBAL -> "/tavern/data/extensions/$folderName"
            ExtensionTargetKind.USER -> "/tavern/data/data/default-user/extensions/$folderName"
            ExtensionTargetKind.SERVER_PLUGIN -> "/tavern/data/plugins/$folderName"
        }
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

    /**
     * 生成安装/更新仓库命令；前端扩展必须有 manifest，后端插件允许没有 package.json。
     * 后端插件的 package.json 只表示需要 npm install，不是插件仓库是否有效的必选契约。
     */
    private fun repositoryInstallCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import { spawn } from 'node:child_process';
            import git from 'isomorphic-git';
            import http from 'isomorphic-git/http/node';

            const targetDir = process.env.SILLYDROID_EXTENSION_TARGET_DIR;
            const repoUrl = process.env.SILLYDROID_EXTENSION_REPO_URL;
            const repoBranch = process.env.SILLYDROID_EXTENSION_REPO_BRANCH || undefined;
            const tempDir = process.env.SILLYDROID_EXTENSION_TEMP_DIR;
            const runNpmInstall = process.env.SILLYDROID_EXTENSION_RUN_NPM_INSTALL === '1';
            const targetKind = process.env.SILLYDROID_EXTENSION_TARGET_KIND || 'GLOBAL';
            if (!targetDir || !repoUrl || !tempDir) {
                throw new Error('Missing extension target or repository URL.');
            }

            ${extensionProgressHelpers()}
            ${npmInstallHelpers()}

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
                const packagePath = path.join(tempDir, 'package.json');
                if (targetKind !== 'SERVER_PLUGIN' && !fs.existsSync(manifestPath)) {
                    throw new Error('Manifest file not found at ' + manifestPath);
                }

                if (fs.existsSync(manifestPath)) {
                    JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
                }
                if (fs.existsSync(packagePath)) {
                    JSON.parse(fs.readFileSync(packagePath, 'utf8'));
                }

                writeProgress({ step: 'backup', indeterminate: true });
                if (fs.existsSync(targetDir)) {
                    fs.renameSync(targetDir, backupDir);
                }

                writeProgress({ step: 'updating', indeterminate: true });
                fs.renameSync(tempDir, targetDir);
                if (runNpmInstall && fs.existsSync(path.join(targetDir, 'package.json'))) {
                    writeProgress({ step: 'npm', indeterminate: true, message: '安装后端插件依赖：' + path.basename(targetDir) });
                    await runNpmInstallInDirectory(targetDir, path.basename(targetDir));
                }
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

    private fun repositoryUpdateProbeCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import git from 'isomorphic-git';
            import http from 'isomorphic-git/http/node';

            const targetDir = process.env.SILLYDROID_EXTENSION_TARGET_DIR;
            const repoUrl = process.env.SILLYDROID_EXTENSION_REPO_URL;
            const repoBranch = process.env.SILLYDROID_EXTENSION_REPO_BRANCH || undefined;
            const tempDir = process.env.SILLYDROID_EXTENSION_TEMP_DIR;
            const versionFile = process.env.SILLYDROID_EXTENSION_VERSION_FILE || 'manifest.json';
            if (!targetDir || !repoUrl || !tempDir) {
                throw new Error('Missing extension target or repository URL.');
            }

            ${extensionProgressHelpers()}

            function readVersion(dir) {
                const filePath = path.join(dir, versionFile);
                if (!fs.existsSync(filePath)) {
                    return null;
                }

                try {
                    const payload = JSON.parse(fs.readFileSync(filePath, 'utf8'));
                    const version = typeof payload.version === 'string' ? payload.version.trim() : '';
                    return version || null;
                } catch {
                    return null;
                }
            }

            try {
                writeProgress({ step: 'prepare', indeterminate: true });
                if (!fs.existsSync(path.join(targetDir, '.git'))) {
                    throw new Error('本地目录不是 Git 仓库，无法检测新版本。');
                }
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

                writeProgress({ step: 'validating', indeterminate: true });
                const localRevision = await git.resolveRef({ fs, dir: targetDir, ref: 'HEAD' });
                const remoteRevision = await git.resolveRef({ fs, dir: tempDir, ref: 'HEAD' });
                const localVersion = readVersion(targetDir);
                const remoteVersion = readVersion(tempDir);
                const hasVersionComparison = Boolean(localVersion && remoteVersion);
                const payload = {
                    hasNewVersion: hasVersionComparison ? localVersion !== remoteVersion : localRevision !== remoteRevision,
                    localRevision,
                    remoteRevision,
                    localVersion,
                    remoteVersion,
                    versionSourceFile: versionFile,
                };
                console.log('SILLYDROID_REPOSITORY_UPDATE_RESULT=' + JSON.stringify(payload));
                writeProgress({ step: 'completed', loaded: 1, total: 1 });
            } finally {
                if (fs.existsSync(tempDir)) {
                    fs.rmSync(tempDir, { recursive: true, force: true });
                }
            }
        """.trimIndent()
    }

    /**
     * 生成后端插件预检命令；package.json 与 manifest.json 都只是展示/依赖元数据，不是后端插件必选文件。
     */
    private fun pluginInstallPreviewCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import git from 'isomorphic-git';
            import http from 'isomorphic-git/http/node';

            const repoUrl = process.env.SILLYDROID_EXTENSION_REPO_URL;
            const repoBranch = process.env.SILLYDROID_EXTENSION_REPO_BRANCH || undefined;
            const tempDir = process.env.SILLYDROID_EXTENSION_TEMP_DIR;
            if (!repoUrl || !tempDir) {
                throw new Error('Missing plugin repository URL.');
            }

            ${extensionProgressHelpers()}

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

                writeProgress({ step: 'validating', indeterminate: true });
                const packagePath = path.join(tempDir, 'package.json');
                const manifestPath = path.join(tempDir, 'manifest.json');
                let packageJson = null;
                let manifestJson = null;
                if (fs.existsSync(packagePath)) {
                    packageJson = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
                }
                if (fs.existsSync(manifestPath)) {
                    manifestJson = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
                }

                function readString(source, keys) {
                    if (!source) {
                        return null;
                    }
                    for (const key of keys) {
                        const value = source[key];
                        if (typeof value === 'string' && value.trim()) {
                            return value.trim();
                        }
                    }
                    return null;
                }

                const fallbackName = path.basename(repoUrl).replace(/\.git$/i, '');
                const displayName =
                    readString(packageJson, ['name']) ||
                    readString(manifestJson, ['display_name', 'name']) ||
                    fallbackName;
                const version =
                    readString(packageJson, ['version']) ||
                    readString(manifestJson, ['version']);
                const description =
                    readString(packageJson, ['description']) ||
                    readString(manifestJson, ['description']);
                console.log('SILLYDROID_PLUGIN_INSTALL_PREVIEW=' + JSON.stringify({ displayName, version, description }));
                writeProgress({ step: 'completed', loaded: 1, total: 1 });
            } finally {
                if (fs.existsSync(tempDir)) {
                    fs.rmSync(tempDir, { recursive: true, force: true });
                }
            }
        """.trimIndent()
    }

    private fun serverPluginDependencyInstallCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import { spawn } from 'node:child_process';

            const appDataRoot = process.env.APP_DATA_ROOT;
            const hostPrefixDir = process.env.HOST_PREFIX_DIR;
            const nodeBin = process.env.TERMUX_NODE_BIN;
            const shellBin = process.env.TERMUX_SH_BIN;
            if (!appDataRoot || !hostPrefixDir || !nodeBin || !shellBin) {
                throw new Error('Missing server plugin npm environment.');
            }

            ${extensionProgressHelpers()}
            ${npmInstallHelpers()}

            const pluginsDir = path.join(appDataRoot, 'plugins');
            function listPluginPackageDirs() {
                if (!fs.existsSync(pluginsDir)) {
                    return [];
                }

                return fs.readdirSync(pluginsDir, { withFileTypes: true })
                    .filter(entry => entry.isDirectory())
                    .map(entry => ({
                        name: entry.name,
                        dir: path.join(pluginsDir, entry.name),
                    }))
                    .filter(plugin => fs.existsSync(path.join(plugin.dir, 'package.json')))
                    .sort((left, right) => left.name.localeCompare(right.name));
            }

            const plugins = listPluginPackageDirs();
            if (plugins.length === 0) {
                writeProgress({ step: 'completed', loaded: 1, total: 1, message: '未发现含 package.json 的后端插件' });
            } else {
                console.log('[sillydroid] installing npm dependencies for ' + plugins.length + ' server plugin(s).');
                for (const [index, plugin] of plugins.entries()) {
                    writeProgress({
                        step: 'npm',
                        loaded: index,
                        total: plugins.length,
                        message: '安装后端插件依赖：' + plugin.name,
                    });
                    await runNpmInstallInDirectory(plugin.dir, plugin.name);
                }
                writeProgress({ step: 'completed', loaded: plugins.length, total: plugins.length, message: '后端插件依赖安装完成' });
            }
        """.trimIndent()
    }

    private fun singlePluginDependencyInstallCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import { spawn } from 'node:child_process';

            const appDataRoot = process.env.APP_DATA_ROOT;
            const hostPrefixDir = process.env.HOST_PREFIX_DIR;
            const nodeBin = process.env.TERMUX_NODE_BIN;
            const shellBin = process.env.TERMUX_SH_BIN;
            const pluginFolderName = process.env.SILLYDROID_PLUGIN_FOLDER_NAME;
            if (!appDataRoot || !hostPrefixDir || !nodeBin || !shellBin || !pluginFolderName) {
                throw new Error('Missing server plugin npm environment.');
            }

            ${extensionProgressHelpers()}
            ${npmInstallHelpers()}

            if (path.basename(pluginFolderName) !== pluginFolderName || pluginFolderName.includes('/') || pluginFolderName.includes('\\')) {
                throw new Error('Invalid plugin folder name: ' + pluginFolderName);
            }

            const pluginDir = path.join(appDataRoot, 'plugins', pluginFolderName);
            const packagePath = path.join(pluginDir, 'package.json');
            if (!fs.existsSync(packagePath)) {
                throw new Error('后端插件目录缺少 package.json：' + pluginFolderName);
            }

            writeProgress({
                step: 'npm',
                loaded: 0,
                total: 1,
                message: '安装后端插件依赖：' + pluginFolderName,
            });
            await runNpmInstallInDirectory(pluginDir, pluginFolderName);
            writeProgress({ step: 'completed', loaded: 1, total: 1, message: '后端插件依赖安装完成' });
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

    private fun npmInstallHelpers(): String {
        return """
            const hostPrefixDirForNpm = process.env.HOST_PREFIX_DIR;
            const nodeBinForNpm = process.env.TERMUX_NODE_BIN;
            const shellBinForNpm = process.env.TERMUX_SH_BIN;

            function npmCliPath() {
                if (!hostPrefixDirForNpm) {
                    throw new Error('Missing HOST_PREFIX_DIR for npm install.');
                }
                const npmCli = path.join(hostPrefixDirForNpm, 'lib/node_modules/npm/bin/npm-cli.js');
                if (!fs.existsSync(npmCli)) {
                    throw new Error('Missing npm CLI: ' + npmCli);
                }
                return npmCli;
            }

            function runNpmInstallInDirectory(directory, displayName) {
                if (!nodeBinForNpm || !shellBinForNpm) {
                    throw new Error('Missing npm runtime environment.');
                }

                return new Promise((resolve, reject) => {
                    // 后端插件依赖安装必须保持官方安装命令语义，不替用户额外追加环境或忽略脚本参数。
                    const args = [npmCliPath(), 'install'];
                    console.log('[sillydroid] npm install in ' + directory);
                    const child = spawn(nodeBinForNpm, args, {
                        cwd: directory,
                        env: {
                            ...process.env,
                            PREFIX: hostPrefixDirForNpm,
                            SHELL: shellBinForNpm,
                            npm_config_script_shell: shellBinForNpm,
                            NPM_CONFIG_SCRIPT_SHELL: shellBinForNpm,
                        },
                        stdio: ['ignore', 'inherit', 'inherit'],
                    });
                    child.on('error', reject);
                    child.on('exit', (code, signal) => {
                        if (code === 0) {
                            resolve();
                            return;
                        }
                        reject(new Error('npm install failed for ' + displayName + ' code=' + code + ' signal=' + signal));
                    });
                });
            }
        """.trimIndent()
    }
}
