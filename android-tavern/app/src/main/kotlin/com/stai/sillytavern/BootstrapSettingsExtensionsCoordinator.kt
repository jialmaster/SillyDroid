package com.stai.sillytavern

import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.google.android.material.R as MaterialR

internal class BootstrapSettingsExtensionsCoordinator(
    private val activity: AppCompatActivity,
    private val listContainer: LinearLayout,
    private val emptyView: TextView,
    private val installButton: MaterialButton,
    private val reloadButton: MaterialButton,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showBanner: (String) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private data class ManagedExtension(
        val folderName: String,
        val displayName: String,
        val version: String?,
        val author: String?,
        val homePage: String?,
        val manifestHealthy: Boolean,
        val manifestMessage: String?
    )

    private data class ExtensionInstallPreview(
        val repositoryUrl: String,
        val folderName: String,
        val displayName: String,
        val version: String?,
        val author: String?,
        val homePage: String?,
        val tempDirectory: File,
        val previewFile: File,
        val targetExists: Boolean
    )

    private data class NormalizedExtensionRepository(
        val cloneUrl: String,
        val branch: String?
    )

    private val stoppedPhases = setOf(
        StartupPhase.CONFIGURING,
        StartupPhase.IDLE,
        StartupPhase.BLOCKED,
        StartupPhase.ERROR
    )
    private val paths by lazy { HostPaths.from(activity) }
    private val launcher by lazy { LinuxRuntimeLauncher(paths) }
    private val runtimeProvisioner by lazy { RootfsRuntimeProvisioner(launcher, paths) }
    private var extensions: List<ManagedExtension> = emptyList()
    private var busy = false

    fun initialize() {
        installButton.setOnClickListener {
            promptInstallExtension()
        }
        reloadButton.setOnClickListener {
            reloadExtensions()
        }
        renderExtensions()
    }

    fun reloadExtensions() {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    loadExtensionsFromDisk()
                }
            }
            setBusyState(false)

            result.onSuccess { items ->
                extensions = items
                renderExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_reinstall_failed))
            }
        }
    }

    private fun loadExtensionsFromDisk(): List<ManagedExtension> {
        paths.ensureWorkingDirectories()
        val extensionsRoot = File(paths.serverDataDir, "extensions")
        if (!extensionsRoot.exists()) {
            return emptyList()
        }

        return extensionsRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { directory ->
                val manifestFile = File(directory, "manifest.json")
                if (!manifestFile.exists()) {
                    return@map ManagedExtension(
                        folderName = directory.name,
                        displayName = directory.name,
                        version = null,
                        author = null,
                        homePage = null,
                        manifestHealthy = false,
                        manifestMessage = activity.getString(R.string.bootstrap_settings_extensions_manifest_missing)
                    )
                }

                runCatching {
                    val manifest = JSONObject(manifestFile.readText())
                    ManagedExtension(
                        folderName = directory.name,
                        displayName = manifest.optString("display_name").ifBlank { directory.name },
                        version = manifest.optString("version").ifBlank { null },
                        author = manifest.optString("author").ifBlank { null },
                        homePage = manifest.optString("homePage").ifBlank { null },
                        manifestHealthy = true,
                        manifestMessage = null
                    )
                }.getOrElse {
                    ManagedExtension(
                        folderName = directory.name,
                        displayName = directory.name,
                        version = null,
                        author = null,
                        homePage = null,
                        manifestHealthy = false,
                        manifestMessage = activity.getString(R.string.bootstrap_settings_extensions_manifest_missing)
                    )
                }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    private fun renderExtensions() {
        installButton.isEnabled = !busy
        reloadButton.isEnabled = !busy
        listContainer.removeAllViews()
        emptyView.isVisible = extensions.isEmpty()
        if (extensions.isEmpty()) {
            return
        }

        extensions.forEachIndexed { index, extension ->
            listContainer.addView(createExtensionCard(extension), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) {
                    topMargin = dp(8)
                }
            })
        }
    }

    private fun createExtensionCard(extension: ManagedExtension): MaterialCardView {
        val card = MaterialCardView(activity).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = resolveColor(MaterialR.attr.colorOutlineVariant)
            setCardBackgroundColor(resolveColor(MaterialR.attr.colorSurfaceContainerLow))
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        container.addView(TextView(activity).apply {
            text = extension.displayName
            setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        container.addView(TextView(activity).apply {
            val versionLabel = extension.version ?: activity.getString(R.string.bootstrap_settings_extensions_version_unknown)
            val authorLabel = extension.author ?: activity.getString(R.string.bootstrap_settings_extensions_author_unknown)
            text = "$versionLabel  •  $authorLabel"
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })

        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.bootstrap_settings_extensions_folder, extension.folderName)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })

        if (!extension.homePage.isNullOrBlank()) {
            container.addView(TextView(activity).apply {
                text = activity.getString(R.string.bootstrap_settings_extensions_source, extension.homePage)
                setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
        }

        if (!extension.manifestHealthy || !extension.manifestMessage.isNullOrBlank()) {
            container.addView(TextView(activity).apply {
                text = extension.manifestMessage ?: activity.getString(R.string.bootstrap_settings_extensions_manifest_missing)
                setTextColor(resolveColor(MaterialR.attr.colorError))
                textSize = 11f
                setPadding(0, dp(6), 0, 0)
            })
        }

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }

        val reinstallButton = MaterialButton(activity, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.bootstrap_settings_extensions_reinstall)
            isEnabled = !busy && !extension.homePage.isNullOrBlank()
            setOnClickListener {
                if (extension.homePage.isNullOrBlank()) {
                    showError(activity.getString(R.string.bootstrap_settings_extensions_homepage_missing))
                } else {
                    confirmReinstall(extension)
                }
            }
        }
        actionsRow.addView(reinstallButton)

        val deleteButton = MaterialButton(activity, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.bootstrap_settings_extensions_delete)
            setTextColor(resolveColor(MaterialR.attr.colorError))
            strokeColor = android.content.res.ColorStateList.valueOf(resolveColor(MaterialR.attr.colorError))
            isEnabled = !busy
            val layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.marginStart = dp(8)
            this.layoutParams = layoutParams
            setOnClickListener {
                confirmDelete(extension)
            }
        }
        actionsRow.addView(deleteButton)

        container.addView(actionsRow)
        card.addView(container)
        return card
    }

    private fun confirmDelete(extension: ManagedExtension) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_delete_confirm_title)
            .setMessage(activity.getString(
                R.string.bootstrap_settings_extensions_delete_confirm_message,
                extension.displayName,
                extension.folderName
            ))
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_delete) { _, _ ->
                deleteExtension(extension)
            }
            .show()
    }

    private fun confirmReinstall(extension: ManagedExtension) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_reinstall_confirm_title)
            .setMessage(activity.getString(
                R.string.bootstrap_settings_extensions_reinstall_confirm_message,
                extension.displayName
            ))
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_reinstall) { _, _ ->
                reinstallExtension(extension)
            }
            .show()
    }

    private fun deleteExtension(extension: ManagedExtension) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    stopServiceForMaintenance()
                    val targetDir = File(File(paths.serverDataDir, "extensions"), extension.folderName)
                    if (!targetDir.deleteRecursively() && targetDir.exists()) {
                        throw BootstrapException(activity.getString(R.string.bootstrap_settings_extensions_delete_failed))
                    }
                }
            }
            setBusyState(false)

            result.onSuccess {
                val message = activity.getString(R.string.bootstrap_settings_extensions_delete_success, extension.displayName)
                showBanner(message)
                showMessage(message)
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_delete_failed))
                renderExtensions()
            }
        }
    }

    private fun reinstallExtension(extension: ManagedExtension) {
        val repositoryUrl = extension.homePage ?: run {
            showError(activity.getString(R.string.bootstrap_settings_extensions_homepage_missing))
            return
        }
        val normalizedRepository = normalizeRepositoryUrl(repositoryUrl) ?: run {
            showError(activity.getString(R.string.bootstrap_settings_extensions_homepage_invalid))
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    stopServiceForMaintenance()
                    runExtensionReinstall(extension.folderName, normalizedRepository)
                }
            }
            setBusyState(false)

            result.onSuccess {
                val message = activity.getString(R.string.bootstrap_settings_extensions_reinstall_success, extension.displayName)
                showBanner(message)
                showMessage(message)
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_reinstall_failed))
                reloadExtensions()
            }
        }
    }

    private fun promptInstallExtension() {
        if (busy) {
            return
        }

        val inputLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.bootstrap_settings_extensions_install_prompt_hint)
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val inputView = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            minHeight = dp(44)
        }
        inputLayout.addView(inputView)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_install_prompt_title)
            .setView(inputLayout)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_install_prompt_action, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val repositoryUrl = inputView.text?.toString().orEmpty().trim()
                val normalizedRepository = normalizeRepositoryUrl(repositoryUrl)
                if (normalizedRepository == null) {
                    inputLayout.error = activity.getString(R.string.bootstrap_settings_extensions_install_invalid_url)
                    return@setOnClickListener
                }

                inputLayout.error = null
                dialog.dismiss()
                previewInstallExtension(repositoryUrl, normalizedRepository)
            }
        }
        dialog.show()
    }

    private fun previewInstallExtension(repositoryUrl: String, normalizedRepository: NormalizedExtensionRepository) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    stopServiceForMaintenance()
                    runExtensionInstallPreview(repositoryUrl, normalizedRepository)
                }
            }
            setBusyState(false)

            result.onSuccess { preview ->
                confirmInstallPreview(preview)
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_install_failed))
            }
        }
    }

    private fun confirmInstallPreview(preview: ExtensionInstallPreview) {
        var confirmed = false
        val versionLabel = preview.version ?: activity.getString(R.string.bootstrap_settings_extensions_version_unknown)
        val authorLabel = preview.author ?: activity.getString(R.string.bootstrap_settings_extensions_author_unknown)
        val message = if (preview.targetExists) {
            activity.getString(
                R.string.bootstrap_settings_extensions_install_confirm_message_overwrite,
                preview.displayName,
                versionLabel,
                authorLabel,
                preview.folderName
            )
        } else {
            activity.getString(
                R.string.bootstrap_settings_extensions_install_confirm_message_new,
                preview.displayName,
                versionLabel,
                authorLabel,
                preview.folderName
            )
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_install_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_install) { _, _ ->
                confirmed = true
                installPreviewedExtension(preview)
            }
            .setOnDismissListener {
                if (!confirmed) {
                    cleanupInstallPreview(preview)
                }
            }
            .show()
    }

    private fun installPreviewedExtension(preview: ExtensionInstallPreview) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    stopServiceForMaintenance()
                    finalizeInstallPreview(preview)
                }
            }
            setBusyState(false)

            result.onSuccess {
                val message = activity.getString(R.string.bootstrap_settings_extensions_install_success, preview.displayName)
                showBanner(message)
                showMessage(message)
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_install_failed))
                reloadExtensions()
            }
        }
    }

    private suspend fun stopServiceForMaintenance() {
        if (StartupRuntimeStore.state.value.phase !in stoppedPhases) {
            withContext(Dispatchers.Main.immediate) {
                activity.startService(StartupCoordinatorService.createStopForSettingsIntent(activity))
            }
        }

        val stoppedState = withTimeoutOrNull(5000) {
            StartupRuntimeStore.state.first { state ->
                state.phase in stoppedPhases
            }
        }

        if (stoppedState == null) {
            throw BootstrapException(activity.getString(R.string.bootstrap_settings_import_stop_timeout))
        }
    }

    private fun runExtensionReinstall(folderName: String, repository: NormalizedExtensionRepository) {
        val requestName = "extension-reinstall-${folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val environment = mutableMapOf(
            "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
            "STAI_EXTENSION_TARGET_DIR" to "/tavern/data/extensions/$folderName",
            "STAI_EXTENSION_REPO_URL" to repository.cloneUrl
        )
        repository.branch?.let { branch ->
            environment["STAI_EXTENSION_REPO_BRANCH"] = branch
        }
        runExtensionCommand(
            requestName = requestName,
            commandFileName = "$requestName.mjs",
            commandContent = extensionReinstallCommand(),
            environment = environment,
            failureMessage = { logPath ->
                activity.getString(R.string.bootstrap_settings_extensions_runtime_timeout, logPath)
            }
        )
    }

    private fun runExtensionCommand(
        requestName: String,
        commandFileName: String,
        commandContent: String,
        environment: Map<String, String>,
        failureMessage: (String) -> String
    ): String {
        paths.ensureWorkingDirectories()
        runtimeProvisioner.ensure()

        val maintenanceRoot = File(paths.serverDataDir, ".stai-maintenance")
        maintenanceRoot.mkdirs()

        val commandScript = File(maintenanceRoot, commandFileName)
        val launchScript = File(maintenanceRoot, "extension-command.sh")
        commandScript.writeText(commandContent)
        launchScript.writeText(extensionRuntimeScript())

        val logPath = File(paths.logsDir, "$requestName.log").absolutePath
        val request = LaunchRequest(
            name = requestName,
            scriptFile = launchScript,
            workingDirectory = paths.bootstrapRoot,
            environment = environment + mapOf(
                "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
                "COMMAND_JS" to "/tavern/data/.stai-maintenance/$commandFileName"
            )
        )
        val process = launcher.start(request)
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.stop()
            throw BootstrapException(failureMessage(logPath))
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw BootstrapException(failureMessage(logPath))
        }
        return logPath
    }

    private fun runExtensionInstallPreview(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository
    ): ExtensionInstallPreview {
        val previewId = System.currentTimeMillis()
        val requestName = "extension-install-preview-$previewId"
        val maintenanceRoot = File(paths.serverDataDir, ".stai-maintenance")
        maintenanceRoot.mkdirs()
        val previewHostFile = File(maintenanceRoot, "$requestName.json")
        val previewGuestFile = "/tavern/data/.stai-maintenance/${previewHostFile.name}"
        val previewTempDir = File(maintenanceRoot, "$requestName-repo")
        val previewGuestTempDir = "/tavern/data/.stai-maintenance/${previewTempDir.name}"
        val environment = mutableMapOf(
            "STAI_EXTENSION_REPO_URL" to normalizedRepository.cloneUrl,
            "STAI_EXTENSION_PREVIEW_FILE" to previewGuestFile,
            "STAI_EXTENSION_TEMP_DIR" to previewGuestTempDir
        )
        normalizedRepository.branch?.let { branch ->
            environment["STAI_EXTENSION_REPO_BRANCH"] = branch
        }
        val logPath = runExtensionCommand(
            requestName = requestName,
            commandFileName = "$requestName.mjs",
            commandContent = extensionInstallPreviewCommand(),
            environment = environment,
            failureMessage = { failedLogPath ->
                activity.getString(R.string.bootstrap_settings_extensions_install_preview_failed, failedLogPath)
            }
        )

        if (!previewHostFile.exists() || !previewTempDir.exists()) {
            throw BootstrapException(activity.getString(R.string.bootstrap_settings_extensions_install_preview_failed, logPath))
        }

        val previewJson = JSONObject(previewHostFile.readText())
        val folderName = previewJson.optString("folderName").ifBlank {
            throw BootstrapException(activity.getString(R.string.bootstrap_settings_extensions_install_preview_failed, logPath))
        }
        val targetExists = File(File(paths.serverDataDir, "extensions"), folderName).exists()
        return ExtensionInstallPreview(
            repositoryUrl = repositoryUrl,
            folderName = folderName,
            displayName = previewJson.optString("displayName").ifBlank { folderName },
            version = previewJson.optString("version").ifBlank { null },
            author = previewJson.optString("author").ifBlank { null },
            homePage = previewJson.optString("homePage").ifBlank { null },
            tempDirectory = previewTempDir,
            previewFile = previewHostFile,
            targetExists = targetExists
        )
    }

    private fun finalizeInstallPreview(preview: ExtensionInstallPreview) {
        val extensionsRoot = File(paths.serverDataDir, "extensions")
        val targetDir = File(extensionsRoot, preview.folderName)
        val backupDir = File(extensionsRoot, "${preview.folderName}.stai-backup-${System.currentTimeMillis()}")
        var backupCreated = false
        var installed = false

        try {
            extensionsRoot.mkdirs()
            if (targetDir.exists()) {
                Files.move(targetDir.toPath(), backupDir.toPath())
                backupCreated = true
            }
            Files.move(preview.tempDirectory.toPath(), targetDir.toPath())
            installed = true
            preview.previewFile.delete()
            if (backupCreated && backupDir.exists()) {
                backupDir.deleteRecursively()
            }
        } catch (exception: Exception) {
            if (installed && targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            if (backupCreated && backupDir.exists()) {
                Files.move(backupDir.toPath(), targetDir.toPath())
            }
            cleanupInstallPreview(preview)
            throw exception
        }
    }

    private fun cleanupInstallPreview(preview: ExtensionInstallPreview) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            preview.previewFile.delete()
            preview.tempDirectory.deleteRecursively()
        }
    }

    private fun isSupportedRepositoryUrl(repositoryUrl: String): Boolean {
        return normalizeRepositoryUrl(repositoryUrl) != null
    }

    private fun normalizeRepositoryUrl(repositoryUrl: String): NormalizedExtensionRepository? {
        val uri = runCatching { Uri.parse(repositoryUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme.orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return null
        }
        if (host.isBlank()) {
            return null
        }

        val segments = uri.pathSegments.orEmpty()
            .map { segment -> segment.trim() }
            .filter { segment -> segment.isNotEmpty() }
        if (segments.isEmpty()) {
            return null
        }

        return when (host) {
            "github.com", "www.github.com" -> buildHostedRepositoryUrl(
                host = "github.com",
                ownerAndRepo = segments.take(2),
                branch = segments.getOrNull(2)
                    ?.takeIf { marker -> marker.equals("blob", ignoreCase = true) || marker.equals("tree", ignoreCase = true) || marker.equals("raw", ignoreCase = true) }
                    ?.let { _ -> segments.getOrNull(3) }
            )

            "raw.githubusercontent.com" -> buildHostedRepositoryUrl(
                host = "github.com",
                ownerAndRepo = segments.take(2),
                branch = segments.getOrNull(2)
            )

            "gitlab.com", "www.gitlab.com" -> {
                val markerIndex = segments.indexOf("-")
                val repoSegments = if (markerIndex >= 0) segments.subList(0, markerIndex) else segments
                val branch = if (
                    markerIndex >= 0 &&
                    segments.getOrNull(markerIndex + 1)?.let { marker ->
                        marker.equals("blob", ignoreCase = true) || marker.equals("tree", ignoreCase = true) || marker.equals("raw", ignoreCase = true)
                    } == true
                ) {
                    segments.getOrNull(markerIndex + 2)
                } else {
                    null
                }
                buildHostedRepositoryUrl("gitlab.com", repoSegments, branch)
            }

            else -> {
                val lastSegment = segments.last()
                if (lastSegment.equals("manifest.json", ignoreCase = true)) {
                    return null
                }
                val cloneUrl = buildString {
                    append(scheme.lowercase())
                    append("://")
                    append(uri.encodedAuthority)
                    append(uri.encodedPath?.trimEnd('/'))
                }
                NormalizedExtensionRepository(
                    cloneUrl = cloneUrl,
                    branch = null
                )
            }
        }
    }

    private fun buildHostedRepositoryUrl(
        host: String,
        ownerAndRepo: List<String>,
        branch: String?
    ): NormalizedExtensionRepository? {
        if (ownerAndRepo.size < 2) {
            return null
        }
        val repoSegments = ownerAndRepo.mapIndexed { index, segment ->
            if (index == ownerAndRepo.lastIndex) {
                stripGitSuffix(segment)
            } else {
                segment
            }
        }
        if (repoSegments.any { segment -> segment.isBlank() }) {
            return null
        }
        return NormalizedExtensionRepository(
            cloneUrl = "https://$host/${repoSegments.joinToString("/")}.git",
            branch = branch?.takeIf { value -> value.isNotBlank() }
        )
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
            STAI_EXTENSION_TARGET_DIR="${'$'}{STAI_EXTENSION_TARGET_DIR:-}"
            STAI_EXTENSION_REPO_URL="${'$'}{STAI_EXTENSION_REPO_URL:?STAI_EXTENSION_REPO_URL is required}"

            PROOT_BIN="${'$'}{HOST_PROOT_BIN:?HOST_PROOT_BIN is required}"
            PROOT_LIB_DIR="${'$'}{HOST_PROOT_LIB_DIR:?HOST_PROOT_LIB_DIR is required}"
            PROOT_LOADER_PATH="${'$'}{HOST_PROOT_LOADER:?HOST_PROOT_LOADER is required}"
            PROOT_LOADER_32_PATH="${'$'}{HOST_PROOT_LOADER_32:-}"
            LINUX_FS_DIR="${'$'}ROOTFS_DIR/fs"
            PROOT_TMP_DIR="${'$'}{HOST_TMP_DIR:?HOST_TMP_DIR is required}"
            ANDROID_RESOLV_CONF="${'$'}ROOTFS_DIR/android-resolv.conf"
            SERVER_MOUNT="/tavern/server"
            DATA_MOUNT="/tavern/data"
            LOGS_MOUNT="/tavern/logs"
            SERVER_NODE_BIN="${'$'}SERVER_MOUNT/node/bin/node"
            GUEST_PATH="/usr/sbin:/usr/bin:/sbin:/bin"

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
                assert_file "${'$'}ANDROID_RESOLV_CONF" "缺少 Android DNS 配置：${'$'}ANDROID_RESOLV_CONF"
                assert_dir "${'$'}SERVER_DIR" "缺少 Tavern 服务目录：${'$'}SERVER_DIR"
                assert_file "${'$'}SERVER_DIR/node/bin/node" "缺少 Tavern Node runtime：${'$'}SERVER_DIR/node/bin/node"

                mkdir -p "${'$'}APP_DATA_ROOT" "${'$'}LOGS_DIR" "${'$'}PROOT_TMP_DIR"
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
            export TMPDIR=/tmp
            export TMP=/tmp
            export TEMP=/tmp
            export HOME=/tmp
                export PATH="${'$'}SERVER_MOUNT/node/bin:${'$'}GUEST_PATH"

                exec "${'$'}PROOT_BIN" -r "${'$'}LINUX_FS_DIR" \
            	-b /dev \
            	-b /proc \
            	-b /sys \
                	-b "${'$'}PROOT_TMP_DIR:/tmp" \
                	-b "${'$'}ANDROID_RESOLV_CONF:/etc/resolv.conf" \
                	-b "${'$'}SERVER_DIR:${'$'}SERVER_MOUNT" \
                	-b "${'$'}APP_DATA_ROOT:${'$'}DATA_MOUNT" \
                	-b "${'$'}LOGS_DIR:${'$'}LOGS_MOUNT" \
                	-w "${'$'}SERVER_MOUNT" \
                	/bin/sh -lc 'cd /tavern/server && /tavern/server/node/bin/node "${'$'}COMMAND_JS"'
        """.trimIndent()
    }

    private fun extensionReinstallCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import { createGitClient } from '/tavern/server/src/git/client.js';

            const targetDir = process.env.STAI_EXTENSION_TARGET_DIR;
            const repoUrl = process.env.STAI_EXTENSION_REPO_URL;
            const repoBranch = process.env.STAI_EXTENSION_REPO_BRANCH || undefined;
            if (!targetDir || !repoUrl) {
                throw new Error('Missing extension target or repository URL.');
            }

            const parentDir = path.dirname(targetDir);
            const backupDir = targetDir + '.stai-backup-' + Date.now();
            fs.mkdirSync(parentDir, { recursive: true });

            try {
                if (fs.existsSync(targetDir)) {
                    fs.renameSync(targetDir, backupDir);
                }

                const git = createGitClient({ backend: 'builtin' });
                const cloneOptions = { depth: 1 };
                if (repoBranch) {
                    cloneOptions.branch = repoBranch;
                }
                await git.clone(repoUrl, targetDir, cloneOptions);

                const manifestPath = path.join(targetDir, 'manifest.json');
                if (!fs.existsSync(manifestPath)) {
                    throw new Error('Manifest file not found at ' + manifestPath);
                }

                JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

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

                throw error;
            }
        """.trimIndent()
    }

    private fun extensionInstallPreviewCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import { createGitClient } from '/tavern/server/src/git/client.js';

            const repoUrl = process.env.STAI_EXTENSION_REPO_URL;
            const repoBranch = process.env.STAI_EXTENSION_REPO_BRANCH || undefined;
            const previewFile = process.env.STAI_EXTENSION_PREVIEW_FILE;
            const tempDir = process.env.STAI_EXTENSION_TEMP_DIR;
            if (!repoUrl || !previewFile || !tempDir) {
                throw new Error('Missing preview environment.');
            }

            const parsedUrl = new URL(repoUrl);
            if (!['http:', 'https:'].includes(parsedUrl.protocol)) {
                throw new Error('Only HTTP and HTTPS URLs are supported.');
            }

            const folderName = path.basename(parsedUrl.pathname, '.git').replace(/[^A-Za-z0-9._-]/g, '');
            if (!folderName) {
                throw new Error('Could not determine extension folder name from URL.');
            }

            try {
                if (fs.existsSync(tempDir)) {
                    fs.rmSync(tempDir, { recursive: true, force: true });
                }

                const git = createGitClient({ backend: 'builtin' });
                const cloneOptions = { depth: 1 };
                if (repoBranch) {
                    cloneOptions.branch = repoBranch;
                }
                await git.clone(parsedUrl.href, tempDir, cloneOptions);

                const manifestPath = path.join(tempDir, 'manifest.json');
                if (!fs.existsSync(manifestPath)) {
                    throw new Error('Manifest file not found at ' + manifestPath);
                }

                const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
                if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
                    throw new Error('Manifest is not a valid JSON object.');
                }

                fs.writeFileSync(previewFile, JSON.stringify({
                    folderName,
                    displayName: typeof manifest.display_name === 'string' && manifest.display_name.trim() ? manifest.display_name.trim() : folderName,
                    version: typeof manifest.version === 'string' && manifest.version.trim() ? manifest.version.trim() : null,
                    author: typeof manifest.author === 'string' && manifest.author.trim() ? manifest.author.trim() : null,
                    homePage: typeof manifest.homePage === 'string' && manifest.homePage.trim() ? manifest.homePage.trim() : null,
                }));
            } catch (error) {
                if (fs.existsSync(tempDir)) {
                    fs.rmSync(tempDir, { recursive: true, force: true });
                }
                if (fs.existsSync(previewFile)) {
                    fs.rmSync(previewFile, { force: true });
                }
                throw error;
            }
        """.trimIndent()
    }

    private fun setBusyState(value: Boolean) {
        busy = value
        setBusy(value)
        renderExtensions()
    }

    private fun resolveColor(attr: Int): Int {
        return MaterialColors.getColor(activity, attr, 0)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}