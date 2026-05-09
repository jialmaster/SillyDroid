package com.jm.sillydroid

import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.google.android.material.R as MaterialR

internal interface ExtensionInstallProgressHost {
    fun show(message: String, percent: Int?, indeterminate: Boolean)

    fun hide()
}

internal class ViewExtensionInstallProgressHost(
    private val activity: AppCompatActivity,
    private val progressIndicator: LinearProgressIndicator,
    private val progressLabel: TextView
) : ExtensionInstallProgressHost {
    override fun show(message: String, percent: Int?, indeterminate: Boolean) {
        progressIndicator.isVisible = true
        progressLabel.isVisible = true
        progressIndicator.isIndeterminate = indeterminate
        if (!indeterminate) {
            progressIndicator.max = 100
            progressIndicator.setProgressCompat(percent ?: 0, true)
        }
        progressLabel.text = message
    }

    override fun hide() {
        progressIndicator.isVisible = false
        progressLabel.isVisible = false
    }
}

internal class BootstrapSettingsExtensionsCoordinator(
    private val activity: AppCompatActivity,
    private val listContainer: LinearLayout,
    private val emptyView: TextView,
    private val installButton: ImageButton,
    private val reloadButton: ImageButton,
    private val progressHost: ExtensionInstallProgressHost,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showBanner: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val onTavernUiReloadRequired: () -> Unit
) {
    companion object {
        fun createHeadless(
            activity: AppCompatActivity,
            progressHost: ExtensionInstallProgressHost,
            setBusy: (Boolean) -> Unit = {},
            showError: (String) -> Unit,
            showBanner: (String) -> Unit,
            showMessage: (String) -> Unit,
            onTavernUiReloadRequired: () -> Unit
        ): BootstrapSettingsExtensionsCoordinator {
            return BootstrapSettingsExtensionsCoordinator(
                activity = activity,
                listContainer = LinearLayout(activity),
                emptyView = TextView(activity),
                installButton = ImageButton(activity),
                reloadButton = ImageButton(activity),
                progressHost = progressHost,
                setBusy = setBusy,
                showError = showError,
                showBanner = showBanner,
                showMessage = showMessage,
                onTavernUiReloadRequired = onTavernUiReloadRequired
            )
        }
    }

    constructor(
        activity: AppCompatActivity,
        listContainer: LinearLayout,
        emptyView: TextView,
        installButton: ImageButton,
        reloadButton: ImageButton,
        progressIndicator: LinearProgressIndicator,
        progressLabel: TextView,
        setBusy: (Boolean) -> Unit,
        showError: (String) -> Unit,
        showBanner: (String) -> Unit,
        showMessage: (String) -> Unit,
        onTavernUiReloadRequired: () -> Unit
    ) : this(
        activity = activity,
        listContainer = listContainer,
        emptyView = emptyView,
        installButton = installButton,
        reloadButton = reloadButton,
        progressHost = ViewExtensionInstallProgressHost(activity, progressIndicator, progressLabel),
        setBusy = setBusy,
        showError = showError,
        showBanner = showBanner,
        showMessage = showMessage,
        onTavernUiReloadRequired = onTavernUiReloadRequired
    )

    private data class ExtensionInventory(
        val installedExtensions: List<ManagedExtension>,
        val bundledExtensions: List<BundledExtension>
    )

    private data class ManagedExtension(
        val folderName: String,
        val displayName: String,
        val version: String?,
        val author: String?,
        val homePage: String?,
        val manifestHealthy: Boolean,
        val manifestMessage: String?
    )

    private data class BundledExtension(
        val folderName: String,
        val displayName: String,
        val version: String?,
        val author: String?,
        val sourceDirectory: File,
        val category: String,
        val targetExists: Boolean
    )

    private data class DefaultExtensionRepository(
        val displayName: String,
        val repositoryUrl: String,
        val description: String?
    )

    private enum class DefaultExtensionInstallMode {
        OVERWRITE,
        SKIP
    }

    private data class ExtensionInstallPreview(
        val repositoryUrl: String,
        val folderName: String,
        val displayName: String,
        val version: String?,
        val author: String?,
        val homePage: String?,
        val tempDirectory: File,
        val previewFile: File,
        val targetExists: Boolean,
        val previewLogPath: String
    )

    private data class BatchExtensionFailure(
        val input: String,
        val message: String,
        val logPath: String?
    )

    private data class BatchExtensionPreview(
        val previews: List<ExtensionInstallPreview>,
        val failures: List<BatchExtensionFailure>,
        val skipped: List<ExtensionInstallPreview> = emptyList()
    )

    private data class BatchExtensionInstallResult(
        val successes: List<ExtensionInstallPreview>,
        val failures: List<BatchExtensionFailure>,
        val skipped: List<ExtensionInstallPreview> = emptyList()
    )

    private data class NormalizedExtensionRepository(
        val cloneUrl: String,
        val branch: String?
    )

    private data class ExtensionRuntimeProgress(
        val step: String?,
        val phase: String?,
        val loaded: Int?,
        val total: Int?,
        val indeterminate: Boolean,
        val message: String?
    )

    private data class ExtensionProgressState(
        val actionLabel: String,
        val stageLabel: String,
        val percent: Int?,
        val indeterminate: Boolean
    )

    private val paths by lazy { HostPaths.from(activity) }
    private val launcher by lazy { LinuxRuntimeLauncher(paths) }
    private val runtimeProvisioner by lazy { RootfsRuntimeProvisioner(launcher, paths) }
    private var extensions: List<ManagedExtension> = emptyList()
    private var bundledExtensions: List<BundledExtension> = emptyList()
    private var busy = false
    private var progressState: ExtensionProgressState? = null

    fun initialize() {
        installButton.setOnClickListener {
            promptInstallExtension()
        }
        reloadButton.setOnClickListener {
            reloadExtensions()
        }
        renderExtensions()
        renderProgress()
        reloadExtensions()
    }

    fun reloadExtensions() {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    loadExtensionInventory()
                }
            }
            setBusyState(false)

            result.onSuccess { inventory ->
                extensions = inventory.installedExtensions
                bundledExtensions = inventory.bundledExtensions
                renderExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_reinstall_failed))
            }
        }
    }

    private fun hasMissingDefaultRepositories(
        repositories: List<DefaultExtensionRepository>,
        installedExtensions: List<ManagedExtension>
    ): Boolean {
        val installedRepositoryKeys = installedExtensions
            .mapNotNull { extension -> extension.homePage?.takeIf { it.isNotBlank() } }
            .mapNotNull(::normalizeRepositoryUrl)
            .map(::normalizedRepositoryKey)
            .toSet()

        return repositories.any { repository ->
            val normalizedRepository = normalizeRepositoryUrl(repository.repositoryUrl) ?: return@any true
            normalizedRepositoryKey(normalizedRepository) !in installedRepositoryKeys
        }
    }

    private fun normalizedRepositoryKey(repository: NormalizedExtensionRepository): String {
        val branchKey = repository.branch?.trim().orEmpty()
        return "${repository.cloneUrl.trim().lowercase()}#$branchKey"
    }

    private fun loadExtensionInventory(): ExtensionInventory {
        val installedExtensions = loadInstalledExtensionsFromDisk()
        val bundledExtensions = loadBundledExtensions()
        return ExtensionInventory(
            installedExtensions = installedExtensions,
            bundledExtensions = bundledExtensions
        )
    }

    private fun loadInstalledExtensionsFromDisk(): List<ManagedExtension> {
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
        renderProgress()
        listContainer.removeAllViews()
        val missingBundledExtensions = bundledExtensions.filterNot { it.targetExists }
        emptyView.isVisible = extensions.isEmpty() && missingBundledExtensions.isEmpty()
        if (extensions.isEmpty() && missingBundledExtensions.isEmpty()) {
            return
        }

        var itemIndex = 0

        if (missingBundledExtensions.isNotEmpty()) {
            listContainer.addView(createSectionHeader(
                title = activity.getString(R.string.bootstrap_settings_extensions_bundled_missing_title),
                summary = activity.getString(R.string.bootstrap_settings_extensions_bundled_missing_summary)
            ))

            missingBundledExtensions.forEach { extension ->
                listContainer.addView(createBundledInstallCard(extension), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(if (itemIndex == 0) 8 else 8)
                })
                itemIndex += 1
            }
        }

        if (extensions.isNotEmpty()) {
            listContainer.addView(createSectionHeader(
                title = activity.getString(R.string.bootstrap_settings_extensions_installed_title),
                summary = activity.getString(R.string.bootstrap_settings_extensions_installed_summary)
            ), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(if (itemIndex == 0) 0 else 12)
            })
        }

        extensions.forEachIndexed { index, extension ->
            listContainer.addView(createExtensionCard(extension), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0 || itemIndex > 0) {
                    topMargin = dp(8)
                }
            })
        }
    }

    private fun createSectionHeader(title: String, summary: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
                setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = summary
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsMeta)
                setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
            })
        }
    }

    private fun createBundledInstallCard(extension: BundledExtension): MaterialCardView {
        val card = MaterialCardView(activity).apply {
            radius = dimenFloat(R.dimen.sillydroid_nested_card_radius)
            strokeWidth = dp(1)
            strokeColor = resolveColor(MaterialR.attr.colorOutlineVariant)
            setCardBackgroundColor(resolveColor(MaterialR.attr.colorSurfaceContainerLow))
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding))
        }

        val infoColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoColumn.addView(TextView(activity).apply {
            text = extension.displayName
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsDenseCardTitle)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        infoColumn.addView(TextView(activity).apply {
            val versionLabel = extension.version ?: activity.getString(R.string.bootstrap_settings_extensions_version_unknown)
            val authorLabel = extension.author ?: activity.getString(R.string.bootstrap_settings_extensions_author_unknown)
            text = "$versionLabel  •  $authorLabel"
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
        })
        infoColumn.addView(TextView(activity).apply {
            text = activity.getString(R.string.bootstrap_settings_extensions_bundled_missing_badge, extension.folderName)
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsMeta)
            setTextColor(resolveColor(MaterialR.attr.colorError))
            setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, 0)
        })
        container.addView(infoColumn)

        container.addView(createActionIconButton(
            iconResId = R.drawable.ic_add,
            contentDescriptionResId = R.string.bootstrap_settings_extensions_install,
            tintAttr = MaterialR.attr.colorPrimary
        ) {
            confirmInstallBundledExtension(extension)
        }.apply {
            isEnabled = !busy
        })

        card.addView(container)
        return card
    }

    private fun createExtensionCard(extension: ManagedExtension): MaterialCardView {
        val card = MaterialCardView(activity).apply {
            radius = dimenFloat(R.dimen.sillydroid_nested_card_radius)
            strokeWidth = dp(1)
            strokeColor = resolveColor(MaterialR.attr.colorOutlineVariant)
            setCardBackgroundColor(resolveColor(MaterialR.attr.colorSurfaceContainerLow))
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding))
        }

        val contentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val infoColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        infoColumn.addView(TextView(activity).apply {
            text = extension.displayName
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsDenseCardTitle)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        infoColumn.addView(TextView(activity).apply {
            val versionLabel = extension.version ?: activity.getString(R.string.bootstrap_settings_extensions_version_unknown)
            val authorLabel = extension.author ?: activity.getString(R.string.bootstrap_settings_extensions_author_unknown)
            text = "$versionLabel  •  $authorLabel"
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
        })

        infoColumn.addView(TextView(activity).apply {
            text = activity.getString(R.string.bootstrap_settings_extensions_folder, extension.folderName)
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, 0)
        })

        if (!extension.homePage.isNullOrBlank()) {
            infoColumn.addView(TextView(activity).apply {
                text = activity.getString(R.string.bootstrap_settings_extensions_source, extension.homePage)
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsMeta)
                setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
            })
        }

        if (!extension.manifestHealthy || !extension.manifestMessage.isNullOrBlank()) {
            infoColumn.addView(TextView(activity).apply {
                text = extension.manifestMessage ?: activity.getString(R.string.bootstrap_settings_extensions_manifest_missing)
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsMeta)
                setTextColor(resolveColor(MaterialR.attr.colorError))
                setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, 0)
            })
        }

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dimen(R.dimen.sillydroid_space_sm)
            }
        }

        val reinstallButton = createActionIconButton(
            iconResId = R.drawable.ic_restore_defaults,
            contentDescriptionResId = R.string.bootstrap_settings_extensions_reinstall
        ) {
            if (extension.homePage.isNullOrBlank()) {
                showError(activity.getString(R.string.bootstrap_settings_extensions_homepage_missing))
            } else {
                confirmReinstall(extension)
            }
        }.apply {
            isEnabled = !busy && !extension.homePage.isNullOrBlank()
        }
        actionsRow.addView(reinstallButton)

        val deleteButton = createActionIconButton(
            iconResId = R.drawable.ic_delete,
            contentDescriptionResId = R.string.bootstrap_settings_extensions_delete,
            tintAttr = MaterialR.attr.colorError
        ) {
            confirmDelete(extension)
        }.apply {
            isEnabled = !busy
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dimen(R.dimen.sillydroid_space_xs)
        }
        actionsRow.addView(deleteButton)

        contentRow.addView(infoColumn)
        contentRow.addView(actionsRow)
        container.addView(contentRow)
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

    private fun createActionIconButton(
        iconResId: Int,
        contentDescriptionResId: Int,
        tintAttr: Int = MaterialR.attr.colorOnSurfaceVariant,
        onClick: () -> Unit
    ): ImageButton {
        val size = dimen(R.dimen.sillydroid_settings_dense_icon_button_size)
        val padding = dimen(R.dimen.sillydroid_settings_dense_icon_button_padding)
        val backgroundAttr = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundAttr, true)

        return ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundResource(backgroundAttr.resourceId)
            setImageResource(iconResId)
            imageTintList = ColorStateList.valueOf(resolveColor(tintAttr))
            contentDescription = activity.getString(contentDescriptionResId)
            setOnClickListener {
                onClick()
            }
        }
    }

    private fun deleteExtension(extension: ManagedExtension) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
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
                onTavernUiReloadRequired()
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_delete_failed))
                renderExtensions()
            }
        }
    }

    private fun deleteExtensionsBatch(selectedExtensions: List<ManagedExtension>) {
        activity.lifecycleScope.launch {
            setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_action_delete_batch),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_delete_batch_progress, selectedExtensions.size),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val removed = mutableListOf<String>()
                    val failed = mutableListOf<String>()
                    selectedExtensions.forEach { extension ->
                        val targetDir = File(File(paths.serverDataDir, "extensions"), extension.folderName)
                        if (!targetDir.exists() || targetDir.deleteRecursively()) {
                            removed += extension.displayName
                        } else {
                            failed += extension.displayName
                        }
                    }
                    removed to failed
                }
            }
            setBusyState(false)
            clearProgressState()

            result.onSuccess { (removed, failed) ->
                val summary = activity.getString(
                    R.string.bootstrap_settings_extensions_delete_batch_result,
                    removed.size,
                    failed.size
                )
                val details = buildString {
                    append(summary)
                    if (removed.isNotEmpty()) {
                        append("\n")
                        append(removed.joinToString(separator = "\n") { name -> "- $name" })
                    }
                    if (failed.isNotEmpty()) {
                        append("\n")
                        append(failed.joinToString(separator = "\n") { name -> "- $name" })
                    }
                }
                if (removed.isNotEmpty()) {
                    showBanner(summary)
                    showMessage(details)
                    onTavernUiReloadRequired()
                } else {
                    showError(details)
                }
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_delete_failed))
                reloadExtensions()
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
            setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_reinstall),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_prepare),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ensureRemoteSourcesReachable(listOf(normalizedRepository))
                    runExtensionReinstall(
                        extension.folderName,
                        normalizedRepository,
                        onProgress = { runtimeProgress ->
                            publishRuntimeProgress(
                                activity.getString(R.string.bootstrap_settings_extensions_progress_action_reinstall),
                                runtimeProgress
                            )
                        }
                    )
                }
            }
            setBusyState(false)
            clearProgressState()

            result.onSuccess {
                val message = activity.getString(R.string.bootstrap_settings_extensions_reinstall_success, extension.displayName)
                showBanner(message)
                showMessage(message)
                onTavernUiReloadRequired()
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

        val bundledExtensions = loadBundledExtensions()
        val defaultRepositories = loadDefaultExtensionRepositories()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (defaultRepositories.isNotEmpty()) {
            actions += activity.getString(R.string.bootstrap_settings_extensions_action_install_default) to {
                promptInstallDefaultRepositories(defaultRepositories)
            }
        }
        actions += activity.getString(R.string.bootstrap_settings_extensions_action_install_repository) to {
            promptInstallRepositoryExtension()
        }
        if (extensions.isNotEmpty()) {
            actions += activity.getString(R.string.bootstrap_settings_extensions_action_delete_batch) to {
                promptDeleteExtensionsBatch()
            }
        }
        if (bundledExtensions.isNotEmpty()) {
            actions += activity.getString(R.string.bootstrap_settings_extensions_action_install_bundled) to {
                promptInstallBundledExtension(bundledExtensions)
            }
        }
        actions += activity.getString(R.string.bootstrap_settings_extensions_action_cleanup_broken) to {
            promptCleanupBrokenExtensions()
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_manage_title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .show()
    }

    private fun promptInstallDefaultRepositories(repositories: List<DefaultExtensionRepository>) {
        if (repositories.isEmpty()) {
            showMessage(activity.getString(R.string.bootstrap_settings_extensions_default_empty))
            return
        }

        val checkedItems = BooleanArray(repositories.size) { true }
        var validatedSelectionKey: String? = null
        var githubCheckVersion = 0
        var selectionDialog: androidx.appcompat.app.AlertDialog? = null
        val labels = repositories.map { repository ->
            repository.description?.takeIf { it.isNotBlank() }?.let { description ->
                "${repository.displayName} | $description"
            } ?: repository.displayName
        }.toTypedArray()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_default_title)
            .setMessage(" ")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
                validatedSelectionKey = null
                githubCheckVersion += 1
                selectionDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.text = activity.getString(R.string.bootstrap_settings_extensions_install)
                selectionDialog?.let { updateDialogMessage(it, baseMessage = null, statusMessage = null) }
            }
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_install, null)
            .create()

        selectionDialog = dialog

        dialog.setOnShowListener {
            updateDialogMessage(dialog, baseMessage = null, statusMessage = null)
            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)

            fun selectionKey(repositoryUrls: List<String>): String {
                return repositoryUrls.joinToString(separator = "\n")
            }

            fun runGithubCheck(repositoryUrls: List<String>, autoContinueOnReachable: Boolean) {
                val currentSelectionKey = selectionKey(repositoryUrls)
                val checkVersion = githubCheckVersion + 1
                githubCheckVersion = checkVersion
                positiveButton.isEnabled = false
                updateDialogMessage(
                    dialog,
                    baseMessage = null,
                    statusMessage = activity.getString(R.string.bootstrap_settings_extensions_github_checking)
                )
                checkRepositoryUrlsGithubReachability(repositoryUrls) { reachable, failureMessage ->
                    if (checkVersion != githubCheckVersion) {
                        return@checkRepositoryUrlsGithubReachability
                    }

                    positiveButton.isEnabled = true
                    if (reachable) {
                        validatedSelectionKey = currentSelectionKey
                        positiveButton.text = activity.getString(R.string.bootstrap_settings_extensions_install)
                        updateDialogMessage(dialog, baseMessage = null, statusMessage = null)
                        if (autoContinueOnReachable) {
                            dialog.dismiss()
                            promptDefaultRepositoryInstallMode(repositoryUrls)
                        }
                    } else {
                        validatedSelectionKey = null
                        positiveButton.text = activity.getString(R.string.bootstrap_settings_extensions_github_check_action)
                        updateDialogMessage(dialog, baseMessage = null, statusMessage = failureMessage)
                    }
                }
            }

            positiveButton.setOnClickListener {
                val selectedRepositories = repositories.filterIndexed { index, _ -> checkedItems[index] }
                if (selectedRepositories.isEmpty()) {
                    showError(activity.getString(R.string.bootstrap_settings_extensions_default_select_required))
                    return@setOnClickListener
                }

                val repositoryUrls = selectedRepositories.map { repository -> repository.repositoryUrl }
                val currentSelectionKey = selectionKey(repositoryUrls)
                if (validatedSelectionKey == currentSelectionKey) {
                    dialog.dismiss()
                    promptDefaultRepositoryInstallMode(repositoryUrls)
                    return@setOnClickListener
                }

                val requestedByDetectAction = positiveButton.text?.toString() == activity.getString(R.string.bootstrap_settings_extensions_github_check_action)
                runGithubCheck(repositoryUrls, autoContinueOnReachable = !requestedByDetectAction)
            }
        }

        dialog.show()
    }

    fun promptDefaultRepositoriesSelection() {
        val repositories = loadDefaultExtensionRepositories()
        if (repositories.isEmpty()) {
            showMessage(activity.getString(R.string.bootstrap_settings_extensions_default_empty))
            return
        }

        promptInstallDefaultRepositories(repositories)
    }

    fun autoInstallDefaultRepositories() {
        if (busy) {
            return
        }

        val repositories = loadDefaultExtensionRepositories()
        if (repositories.isEmpty()) {
            showMessage(activity.getString(R.string.bootstrap_settings_extensions_default_empty))
            return
        }

        previewInstallExtensionsBatch(repositories.map { it.repositoryUrl }) { batchPreview ->
            continueDefaultRepositoryBatchInstall(
                batchPreview = batchPreview,
                installMode = DefaultExtensionInstallMode.SKIP,
                skipConfirmation = true
            )
        }
    }

    fun checkDefaultRepositoriesGithubReachability(onResult: (reachable: Boolean, failureMessage: String?) -> Unit) {
        val repositoryUrls = loadDefaultExtensionRepositories().map { it.repositoryUrl }
        checkRepositoryUrlsGithubReachability(repositoryUrls, onResult)
    }

    private fun promptDefaultRepositoryInstallMode(repositoryUrls: List<String>) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_default_existing_strategy_title)
            .setMessage(R.string.bootstrap_settings_extensions_default_existing_strategy_message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setNeutralButton(R.string.bootstrap_settings_extensions_default_existing_strategy_skip) { _, _ ->
                previewInstallExtensionsBatch(repositoryUrls) { batchPreview ->
                    handleDefaultRepositoryBatchPreview(batchPreview, DefaultExtensionInstallMode.SKIP)
                }
            }
            .setPositiveButton(R.string.bootstrap_settings_extensions_default_existing_strategy_overwrite) { _, _ ->
                previewInstallExtensionsBatch(repositoryUrls) { batchPreview ->
                    handleDefaultRepositoryBatchPreview(batchPreview, DefaultExtensionInstallMode.OVERWRITE)
                }
            }
            .show()
    }

    private fun handleDefaultRepositoryBatchPreview(
        batchPreview: BatchExtensionPreview,
        installMode: DefaultExtensionInstallMode
    ) {
        continueDefaultRepositoryBatchInstall(batchPreview, installMode, skipConfirmation = false)
    }

    private fun continueDefaultRepositoryBatchInstall(
        batchPreview: BatchExtensionPreview,
        installMode: DefaultExtensionInstallMode,
        skipConfirmation: Boolean
    ) {
        if (installMode == DefaultExtensionInstallMode.OVERWRITE) {
            if (skipConfirmation) {
                installPreviewedExtensionsBatch(batchPreview)
            } else {
                confirmBatchInstallPreview(batchPreview)
            }
            return
        }

        val installablePreviews = batchPreview.previews.filterNot { preview -> preview.targetExists }
        val skippedPreviews = batchPreview.previews.filter { preview -> preview.targetExists }
        skippedPreviews.forEach(::cleanupInstallPreview)

        if (installablePreviews.isEmpty()) {
            showDetailedBatchInstallResult(
                details = buildBatchInstallMessage(
                    successes = emptyList(),
                    failures = batchPreview.failures,
                    skipped = skippedPreviews
                ),
                isError = batchPreview.failures.isNotEmpty()
            )
            return
        }

        val resolvedPreview = batchPreview.copy(previews = installablePreviews, skipped = skippedPreviews)
        if (skipConfirmation) {
            installPreviewedExtensionsBatch(resolvedPreview)
        } else {
            confirmBatchInstallPreview(resolvedPreview)
        }
    }

    private fun promptInstallRepositoryExtension() {
        if (busy) {
            return
        }

        val inputLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.bootstrap_settings_extensions_install_prompt_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius))
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val inputView = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 4
            maxLines = 8
            textSize = 13f
            minHeight = dimen(R.dimen.sillydroid_input_min_height)
            setPadding(dimen(R.dimen.sillydroid_control_padding_horizontal), dimen(R.dimen.sillydroid_control_padding_vertical), dimen(R.dimen.sillydroid_control_padding_horizontal), dimen(R.dimen.sillydroid_control_padding_vertical))
        }
        inputLayout.addView(inputView)

        var validatedSelectionKey: String? = null
        var githubCheckVersion = 0
        var inputDialog: androidx.appcompat.app.AlertDialog? = null

        inputView.doAfterTextChanged {
            validatedSelectionKey = null
            githubCheckVersion += 1
            inputLayout.error = null
            inputLayout.helperText = null
            inputDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.text = activity.getString(R.string.bootstrap_settings_extensions_install_prompt_action)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_install_prompt_title)
            .setView(inputLayout)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_install_prompt_action, null)
            .create()

        inputDialog = dialog

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)

            fun continueInstall(repositoryUrls: List<String>) {
                dialog.dismiss()
                if (repositoryUrls.size == 1) {
                    val repositoryUrl = repositoryUrls.single()
                    val normalizedRepository = normalizeRepositoryUrl(repositoryUrl)
                    if (normalizedRepository == null) {
                        showError(activity.getString(R.string.bootstrap_settings_extensions_install_invalid_url))
                        return
                    }

                    previewInstallExtension(repositoryUrl, normalizedRepository)
                    return
                }

                previewInstallExtensionsBatch(repositoryUrls)
            }

            fun selectionKey(repositoryUrls: List<String>): String {
                return repositoryUrls.joinToString(separator = "\n")
            }

            fun runGithubCheck(repositoryUrls: List<String>, autoContinueOnReachable: Boolean) {
                val currentSelectionKey = selectionKey(repositoryUrls)
                val checkVersion = githubCheckVersion + 1
                githubCheckVersion = checkVersion
                positiveButton.isEnabled = false
                inputLayout.error = null
                inputLayout.helperText = activity.getString(R.string.bootstrap_settings_extensions_github_checking)
                checkRepositoryUrlsGithubReachability(repositoryUrls) { reachable, failureMessage ->
                    if (checkVersion != githubCheckVersion) {
                        return@checkRepositoryUrlsGithubReachability
                    }

                    positiveButton.isEnabled = true
                    if (reachable) {
                        validatedSelectionKey = currentSelectionKey
                        inputLayout.helperText = null
                        positiveButton.text = activity.getString(R.string.bootstrap_settings_extensions_install_prompt_action)
                        if (autoContinueOnReachable) {
                            continueInstall(repositoryUrls)
                        }
                    } else {
                        validatedSelectionKey = null
                        inputLayout.helperText = failureMessage
                        positiveButton.text = activity.getString(R.string.bootstrap_settings_extensions_github_check_action)
                    }
                }
            }

            positiveButton.setOnClickListener {
                val repositoryUrls = parseRepositoryUrls(inputView.text?.toString().orEmpty())
                if (repositoryUrls.isEmpty()) {
                    inputLayout.error = activity.getString(R.string.bootstrap_settings_extensions_install_empty)
                    return@setOnClickListener
                }

                inputLayout.error = null
                val supportedUrls = repositoryUrls.filter(::isSupportedRepositoryUrl)
                if (supportedUrls.size != repositoryUrls.size) {
                    showError(activity.getString(R.string.bootstrap_settings_extensions_install_invalid_url))
                    return@setOnClickListener
                }

                val currentSelectionKey = selectionKey(repositoryUrls)
                if (validatedSelectionKey == currentSelectionKey) {
                    continueInstall(repositoryUrls)
                    return@setOnClickListener
                }

                val requestedByDetectAction = positiveButton.text?.toString() == activity.getString(R.string.bootstrap_settings_extensions_github_check_action)
                runGithubCheck(repositoryUrls, autoContinueOnReachable = !requestedByDetectAction)
            }
        }
        dialog.show()
    }

    private fun promptInstallBundledExtension(bundledExtensions: List<BundledExtension>) {
        if (bundledExtensions.isEmpty()) {
            showMessage(activity.getString(R.string.bootstrap_settings_extensions_bundled_selector_empty))
            return
        }

        val labels = bundledExtensions.map { extension ->
            buildString {
                append(extension.displayName)
                extension.version?.takeIf { it.isNotBlank() }?.let { version ->
                    append(" | ")
                    append(version)
                }
                append(" | ")
                append(
                    if (extension.targetExists) {
                        activity.getString(R.string.bootstrap_settings_extensions_default_installed)
                    } else {
                        activity.getString(R.string.bootstrap_settings_extensions_default_not_installed)
                    }
                )
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_bundled_selector_title)
            .setItems(labels) { _, which ->
                confirmInstallBundledExtension(bundledExtensions[which])
            }
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .show()
    }

    private fun promptCleanupBrokenExtensions() {
        if (busy) {
            return
        }

        val brokenDirectories = findBrokenExtensionDirectories()
        if (brokenDirectories.isEmpty()) {
            showMessage(activity.getString(R.string.bootstrap_settings_extensions_cleanup_broken_empty))
            return
        }

        val directoryLines = brokenDirectories.joinToString(separator = "\n") { directory ->
            "- ${directory.name}"
        }
        val message = activity.getString(
            R.string.bootstrap_settings_extensions_cleanup_broken_confirm_message,
            brokenDirectories.size,
            directoryLines
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_cleanup_broken_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_action_cleanup_broken) { _, _ ->
                cleanupBrokenExtensions(brokenDirectories)
            }
            .show()
    }

    private fun promptDeleteExtensionsBatch() {
        if (busy) {
            return
        }

        if (extensions.isEmpty()) {
            showMessage(activity.getString(R.string.bootstrap_settings_extensions_empty))
            return
        }

        val checkedItems = BooleanArray(extensions.size)
        val labels = extensions.map { extension ->
            val versionLabel = extension.version ?: activity.getString(R.string.bootstrap_settings_extensions_version_unknown)
            "${extension.displayName} | ${extension.folderName} | $versionLabel"
        }.toTypedArray()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_delete_batch_title)
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_delete, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedExtensions = extensions.filterIndexed { index, _ -> checkedItems[index] }
                if (selectedExtensions.isEmpty()) {
                    showError(activity.getString(R.string.bootstrap_settings_extensions_delete_batch_select_required))
                    return@setOnClickListener
                }

                dialog.dismiss()
                deleteExtensionsBatch(selectedExtensions)
            }
        }

        dialog.show()
    }

    private fun confirmInstallBundledExtension(extension: BundledExtension) {
        val versionLabel = extension.version ?: activity.getString(R.string.bootstrap_settings_extensions_version_unknown)
        val authorLabel = extension.author ?: activity.getString(R.string.bootstrap_settings_extensions_author_unknown)
        val message = if (extension.targetExists) {
            activity.getString(
                R.string.bootstrap_settings_extensions_bundled_confirm_message_overwrite,
                extension.displayName,
                versionLabel,
                authorLabel,
                extension.folderName
            )
        } else {
            activity.getString(
                R.string.bootstrap_settings_extensions_bundled_confirm_message_new,
                extension.displayName,
                versionLabel,
                authorLabel,
                extension.folderName
            )
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_bundled_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_install) { _, _ ->
                installBundledExtension(extension)
            }
            .show()
    }

    private fun installBundledExtension(extension: BundledExtension) {
        activity.lifecycleScope.launch {
            setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_install),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val extensionsRoot = File(paths.serverDataDir, "extensions")
                    val targetDirectory = File(extensionsRoot, extension.folderName)
                    extensionsRoot.mkdirs()
                    if (targetDirectory.exists()) {
                        targetDirectory.deleteRecursively()
                    }
                    if (!extension.sourceDirectory.copyRecursively(targetDirectory, overwrite = true)) {
                        throw BootstrapException(activity.getString(R.string.bootstrap_settings_extensions_install_failed))
                    }
                }
            }
            setBusyState(false)
            clearProgressState()

            result.onSuccess {
                val message = activity.getString(R.string.bootstrap_settings_extensions_install_success, extension.displayName)
                showBanner(message)
                showMessage(message)
                onTavernUiReloadRequired()
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_install_failed))
                reloadExtensions()
            }
        }
    }

    private fun previewInstallExtensionsBatch(
        repositoryUrls: List<String>,
        onPreviewReady: ((BatchExtensionPreview) -> Unit)? = null
    ) {
        activity.lifecycleScope.launch {
            val previewActionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_preview)
            setProgressState(
                actionLabel = previewActionLabel,
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_batch_preview_stage, repositoryUrls.size),
                percent = 0,
                indeterminate = false
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ensureRemoteSourcesReachable(repositoryUrls.mapNotNull(::normalizeRepositoryUrl))
                    val previews = mutableListOf<ExtensionInstallPreview>()
                    val failures = mutableListOf<BatchExtensionFailure>()
                    repositoryUrls.forEachIndexed { index, repositoryUrl ->
                        val normalizedRepository = normalizeRepositoryUrl(repositoryUrl)
                        val batchProgress = BatchProgressDescriptor(
                            currentIndex = index + 1,
                            totalCount = repositoryUrls.size,
                            itemLabel = repositoryDisplayLabel(repositoryUrl, normalizedRepository)
                        )
                        publishBatchProgress(
                            actionLabel = previewActionLabel,
                            descriptor = batchProgress,
                            stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_prepare),
                            itemPercent = 0,
                            indeterminate = false
                        )
                        if (normalizedRepository == null) {
                            failures += BatchExtensionFailure(
                                input = repositoryUrl,
                                message = activity.getString(R.string.bootstrap_settings_extensions_install_invalid_url),
                                logPath = null
                            )
                            publishBatchProgress(
                                actionLabel = previewActionLabel,
                                descriptor = batchProgress,
                                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_completed),
                                itemPercent = 100,
                                indeterminate = false
                            )
                            return@forEachIndexed
                        }

                        runCatching {
                            runExtensionInstallPreview(
                                repositoryUrl,
                                normalizedRepository,
                                onProgress = { runtimeProgress ->
                                    publishBatchRuntimeProgress(
                                        actionLabel = previewActionLabel,
                                        descriptor = batchProgress,
                                        runtimeProgress = runtimeProgress
                                    )
                                }
                            )
                        }.onSuccess { preview ->
                            previews += preview
                            publishBatchProgress(
                                actionLabel = previewActionLabel,
                                descriptor = batchProgress,
                                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_completed),
                                itemPercent = 100,
                                indeterminate = false
                            )
                        }.onFailure { exception ->
                            failures += toBatchExtensionFailure(
                                input = repositoryUrl,
                                fallbackMessage = activity.getString(R.string.bootstrap_settings_extensions_install_failed),
                                exceptionMessage = exception.message
                            )
                            publishBatchProgress(
                                actionLabel = previewActionLabel,
                                descriptor = batchProgress,
                                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_completed),
                                itemPercent = 100,
                                indeterminate = false
                            )
                        }
                    }
                    BatchExtensionPreview(previews = previews, failures = failures)
                }
            }
            setBusyState(false)
            clearProgressState()

            result.onSuccess { batchPreview ->
                if (batchPreview.previews.isEmpty()) {
                    showDetailedBatchInstallResult(
                        details = buildBatchInstallMessage(emptyList(), batchPreview.failures),
                        isError = true
                    )
                    return@onSuccess
                }

                onPreviewReady?.invoke(batchPreview) ?: confirmBatchInstallPreview(batchPreview)
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_install_failed))
            }
        }
    }

    private fun previewInstallExtension(repositoryUrl: String, normalizedRepository: NormalizedExtensionRepository) {
        activity.lifecycleScope.launch {
            setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_preview),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_prepare),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ensureRemoteSourcesReachable(listOf(normalizedRepository))
                    runExtensionInstallPreview(
                        repositoryUrl,
                        normalizedRepository,
                        onProgress = { runtimeProgress ->
                            publishRuntimeProgress(
                                activity.getString(R.string.bootstrap_settings_extensions_progress_action_preview),
                                runtimeProgress
                            )
                        }
                    )
                }
            }
            setBusyState(false)
            clearProgressState()

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

    private fun confirmBatchInstallPreview(batchPreview: BatchExtensionPreview) {
        var confirmed = false
        val successLines = batchPreview.previews.joinToString(separator = "\n") { preview ->
            val targetLabel = if (preview.targetExists) {
                activity.getString(R.string.bootstrap_settings_extensions_batch_target_overwrite, preview.folderName)
            } else {
                activity.getString(R.string.bootstrap_settings_extensions_batch_target_new, preview.folderName)
            }
            "- ${preview.displayName} (${targetLabel})"
        }
        val failureLines = batchPreview.failures.joinToString(separator = "\n") { failure ->
            "- ${failure.input}: ${failure.message}"
        }
        val skippedLines = batchPreview.skipped.joinToString(separator = "\n") { preview ->
            "- ${preview.displayName} (${preview.folderName})"
        }

        val message = buildString {
            append(
                activity.getString(
                    R.string.bootstrap_settings_extensions_batch_confirm_summary,
                    batchPreview.previews.size,
                    batchPreview.failures.size
                )
            )
            append("\n\n")
            append(successLines)
            if (failureLines.isNotBlank()) {
                append("\n\n")
                append(activity.getString(R.string.bootstrap_settings_extensions_batch_confirm_failures))
                append("\n")
                append(failureLines)
            }
            if (skippedLines.isNotBlank()) {
                append("\n\n")
                append(activity.getString(R.string.bootstrap_settings_extensions_batch_confirm_skipped))
                append("\n")
                append(skippedLines)
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_batch_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_install) { _, _ ->
                confirmed = true
                installPreviewedExtensionsBatch(batchPreview)
            }
            .setOnDismissListener {
                if (!confirmed) {
                    batchPreview.previews.forEach(::cleanupInstallPreview)
                }
            }
            .show()
    }

    private fun installPreviewedExtension(preview: ExtensionInstallPreview) {
        activity.lifecycleScope.launch {
            setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_install),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    finalizeInstallPreview(preview)
                }
            }
            setBusyState(false)
            clearProgressState()

            result.onSuccess {
                val message = activity.getString(R.string.bootstrap_settings_extensions_install_success, preview.displayName)
                showBanner(message)
                showMessage(message)
                onTavernUiReloadRequired()
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_install_failed))
                reloadExtensions()
            }
        }
    }

    private fun installPreviewedExtensionsBatch(batchPreview: BatchExtensionPreview) {
        activity.lifecycleScope.launch {
            val installActionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_install)
            setProgressState(
                actionLabel = installActionLabel,
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_batch_install_stage, batchPreview.previews.size),
                percent = 0,
                indeterminate = false
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val successes = mutableListOf<ExtensionInstallPreview>()
                    val failures = batchPreview.failures.toMutableList()
                    batchPreview.previews.forEachIndexed { index, preview ->
                        val batchProgress = BatchProgressDescriptor(
                            currentIndex = index + 1,
                            totalCount = batchPreview.previews.size,
                            itemLabel = preview.displayName
                        )
                        publishBatchProgress(
                            actionLabel = installActionLabel,
                            descriptor = batchProgress,
                            stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                            itemPercent = 0,
                            indeterminate = false
                        )
                        runCatching {
                            finalizeInstallPreview(preview)
                        }.onSuccess {
                            successes += preview
                            publishBatchProgress(
                                actionLabel = installActionLabel,
                                descriptor = batchProgress,
                                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_completed),
                                itemPercent = 100,
                                indeterminate = false
                            )
                        }.onFailure { exception ->
                            failures += toBatchExtensionFailure(
                                input = preview.repositoryUrl,
                                fallbackMessage = activity.getString(R.string.bootstrap_settings_extensions_install_failed),
                                exceptionMessage = exception.message,
                                fallbackLogPath = preview.previewLogPath
                            )
                            publishBatchProgress(
                                actionLabel = installActionLabel,
                                descriptor = batchProgress,
                                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_completed),
                                itemPercent = 100,
                                indeterminate = false
                            )
                        }
                    }
                    BatchExtensionInstallResult(successes = successes, failures = failures)
                        .copy(skipped = batchPreview.skipped)
                }
            }
            setBusyState(false)
            clearProgressState()

            result.onSuccess { installResult ->
                val summary = activity.getString(
                    R.string.bootstrap_settings_extensions_batch_result_summary,
                    installResult.successes.size,
                    installResult.failures.size
                )
                val details = buildBatchInstallMessage(
                    successes = installResult.successes,
                    failures = installResult.failures,
                    skipped = installResult.skipped
                )
                if (installResult.successes.isNotEmpty()) {
                    showBanner(summary)
                    showDetailedBatchInstallResult(details = details)
                    onTavernUiReloadRequired()
                } else {
                    showDetailedBatchInstallResult(details = details, isError = true)
                }
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_install_failed))
                reloadExtensions()
            }
        }
    }

    private fun runExtensionReinstall(
        folderName: String,
        repository: NormalizedExtensionRepository,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null
    ) {
        val requestName = "extension-reinstall-${folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val environment = mutableMapOf(
            "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
            "SILLYDROID_EXTENSION_TARGET_DIR" to "/tavern/data/extensions/$folderName",
            "SILLYDROID_EXTENSION_REPO_URL" to repository.cloneUrl
        )
        repository.branch?.let { branch ->
            environment["SILLYDROID_EXTENSION_REPO_BRANCH"] = branch
        }
        runExtensionCommand(
            requestName = requestName,
            commandFileName = "$requestName.mjs",
            commandContent = extensionReinstallCommand(),
            environment = environment,
            onProgress = onProgress,
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
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null,
        failureMessage: (String) -> String
    ): String {
        paths.ensureWorkingDirectories()
        runtimeProvisioner.ensure()

        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance")
        val serverMaintenanceRoot = File(paths.bootstrapRoot, "server/.sillydroid-maintenance")
        maintenanceRoot.mkdirs()
        serverMaintenanceRoot.mkdirs()

        val commandScript = File(serverMaintenanceRoot, commandFileName)
        val launchScript = File(maintenanceRoot, "extension-command.sh")
        commandScript.writeText(commandContent)
        launchScript.writeText(extensionRuntimeScript())

        val logPath = File(paths.logsDir, "$requestName.log").absolutePath
        val progressFile = File(maintenanceRoot, "$requestName.progress.json")
        val guestProgressFile = "/tavern/data/.sillydroid-maintenance/${progressFile.name}"
        progressFile.delete()
        val request = LaunchRequest(
            name = requestName,
            scriptFile = launchScript,
            workingDirectory = paths.bootstrapRoot,
            environment = environment + mapOf(
                "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
                "COMMAND_JS" to "/tavern/server/.sillydroid-maintenance/$commandFileName",
                "SILLYDROID_EXTENSION_PROGRESS_FILE" to guestProgressFile
            )
        )
        val process = launcher.start(request)
        var lastProgressPayload: String? = null
        val timeoutAtNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (onProgress != null) {
                    val progressUpdate = readRuntimeProgress(progressFile)
                    if (progressUpdate != null && progressUpdate.first != lastProgressPayload) {
                        lastProgressPayload = progressUpdate.first
                        onProgress(progressUpdate.second)
                    }
                }

                if (System.nanoTime() >= timeoutAtNanos) {
                    process.stop()
                    throw BootstrapException(failureMessage(logPath))
                }
            }

            if (onProgress != null) {
                val progressUpdate = readRuntimeProgress(progressFile)
                if (progressUpdate != null && progressUpdate.first != lastProgressPayload) {
                    onProgress(progressUpdate.second)
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw BootstrapException(failureMessage(logPath))
            }
        } catch (exception: Exception) {
            if (process.isAlive()) {
                process.stop()
            }
            if (exception is BootstrapException) {
                throw exception
            }
            throw exception
        } finally {
            progressFile.delete()
            commandScript.delete()
        }
        return logPath
    }

    private fun runExtensionInstallPreview(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null
    ): ExtensionInstallPreview {
        val previewId = System.currentTimeMillis()
        val requestName = "extension-install-preview-$previewId"
        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance")
        maintenanceRoot.mkdirs()
        val previewHostFile = File(maintenanceRoot, "$requestName.json")
        val previewGuestFile = "/tavern/data/.sillydroid-maintenance/${previewHostFile.name}"
        val previewTempDir = File(maintenanceRoot, "$requestName-repo")
        val previewGuestTempDir = "/tavern/data/.sillydroid-maintenance/${previewTempDir.name}"
        val environment = mutableMapOf(
            "SILLYDROID_EXTENSION_REPO_URL" to normalizedRepository.cloneUrl,
            "SILLYDROID_EXTENSION_PREVIEW_FILE" to previewGuestFile,
            "SILLYDROID_EXTENSION_TEMP_DIR" to previewGuestTempDir
        )
        normalizedRepository.branch?.let { branch ->
            environment["SILLYDROID_EXTENSION_REPO_BRANCH"] = branch
        }
        val logPath = runExtensionCommand(
            requestName = requestName,
            commandFileName = "$requestName.mjs",
            commandContent = extensionInstallPreviewCommand(),
            environment = environment,
            onProgress = onProgress,
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
            targetExists = targetExists,
            previewLogPath = logPath
        )
    }

    private fun finalizeInstallPreview(preview: ExtensionInstallPreview) {
        val extensionsRoot = File(paths.serverDataDir, "extensions")
        val targetDir = File(extensionsRoot, preview.folderName)
        val backupDir = File(extensionsRoot, "${preview.folderName}.sillydroid-backup-${System.currentTimeMillis()}")
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

    private fun cleanupBrokenExtensions(directories: List<File>) {
        activity.lifecycleScope.launch {
            setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_action_cleanup_broken),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val removed = mutableListOf<String>()
                    val failed = mutableListOf<String>()
                    directories.forEach { directory ->
                        if (!directory.exists() || directory.deleteRecursively()) {
                            removed += directory.name
                        } else {
                            failed += directory.name
                        }
                    }
                    removed to failed
                }
            }
            setBusyState(false)
            clearProgressState()

            result.onSuccess { (removed, failed) ->
                val summary = activity.getString(
                    R.string.bootstrap_settings_extensions_cleanup_broken_result,
                    removed.size,
                    failed.size
                )
                val details = buildString {
                    append(summary)
                    if (removed.isNotEmpty()) {
                        append("\n")
                        append(removed.joinToString(separator = "\n") { folderName -> "- $folderName" })
                    }
                    if (failed.isNotEmpty()) {
                        append("\n")
                        append(failed.joinToString(separator = "\n") { folderName -> "- $folderName" })
                    }
                }
                if (removed.isNotEmpty()) {
                    showBanner(summary)
                    showMessage(details)
                    onTavernUiReloadRequired()
                } else {
                    showError(details)
                }
                reloadExtensions()
            }.onFailure { exception ->
                showError(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_cleanup_broken_failed))
                reloadExtensions()
            }
        }
    }

    private fun loadBundledExtensions(): List<BundledExtension> {
        val bundledRoot = File(paths.bootstrapRoot, "bundled-extensions")
        if (!bundledRoot.isDirectory) {
            return emptyList()
        }

        val targetRoot = File(paths.serverDataDir, "extensions")
        return bundledRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory ->
                val manifestFile = File(directory, "manifest.json")
                if (!manifestFile.isFile) {
                    return@mapNotNull null
                }

                runCatching {
                    val manifest = JSONObject(manifestFile.readText())
                    val targetDirectory = File(targetRoot, directory.name)
                    BundledExtension(
                        folderName = directory.name,
                        displayName = manifest.optString("display_name").ifBlank { directory.name },
                        version = manifest.optString("version").ifBlank { null },
                        author = manifest.optString("author").ifBlank { null },
                        sourceDirectory = directory,
                        category = manifest.optString("sillydroid_bundle_category").ifBlank { "default" },
                        targetExists = targetDirectory.isDirectory && File(targetDirectory, "manifest.json").isFile
                    )
                }.getOrNull()
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    private fun loadDefaultExtensionRepositories(): List<DefaultExtensionRepository> {
        val packagedConfigFile = File(paths.bootstrapRoot, "default-extensions/sillydroid-build-config.json")
        if (!packagedConfigFile.isFile) {
            return emptyList()
        }

        return runCatching {
            val root = JSONObject(packagedConfigFile.readText())
            val repositories = root.optJSONArray("defaultExtensionRepositories")
                ?: root.optJSONArray("repositories")
                ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until repositories.length()) {
                    val repository = repositories.optJSONObject(index) ?: continue
                    val displayName = repository.optString("displayName").trim()
                    val repositoryUrl = repository.optString("repositoryUrl").trim()
                    if (displayName.isBlank() || repositoryUrl.isBlank()) {
                        continue
                    }

                    add(
                        DefaultExtensionRepository(
                            displayName = displayName,
                            repositoryUrl = repositoryUrl,
                            description = repository.optString("description").trim().ifBlank { null }
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun findBrokenExtensionDirectories(): List<File> {
        val extensionsRoot = File(paths.serverDataDir, "extensions")
        if (!extensionsRoot.isDirectory) {
            return emptyList()
        }

        return extensionsRoot.listFiles()
            .orEmpty()
            .filter { directory -> directory.isDirectory && !File(directory, "manifest.json").isFile }
            .sortedBy { directory -> directory.name.lowercase() }
    }

    private fun parseRepositoryUrls(rawInput: String): List<String> {
        return rawInput.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()
    }

    private fun buildBatchInstallMessage(
        successes: List<ExtensionInstallPreview>,
        failures: List<BatchExtensionFailure>,
        skipped: List<ExtensionInstallPreview> = emptyList()
    ): String {
        return buildString {
            append(
                activity.getString(
                    R.string.bootstrap_settings_extensions_batch_result_summary,
                    successes.size,
                    failures.size
                )
            )

            if (successes.isNotEmpty()) {
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_successes))
                append("\n")
                append(successes.joinToString(separator = "\n\n") { preview ->
                    buildString {
                        append("- ")
                        append(preview.displayName)
                        append(" -> ")
                        append(preview.folderName)
                        append("\n")
                        append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_source, preview.repositoryUrl))
                        append("\n")
                        append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_log, preview.previewLogPath))
                    }
                })
            }

            if (skipped.isNotEmpty()) {
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_skipped))
                append("\n")
                append(skipped.joinToString(separator = "\n") { preview ->
                    "- ${preview.displayName} -> ${preview.folderName}"
                })
            }

            if (failures.isNotEmpty()) {
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_failures))
                append("\n")
                append(failures.joinToString(separator = "\n\n") { failure ->
                    buildString {
                        append("- ")
                        append(failure.input)
                        append(": ")
                        append(failure.message)
                        failure.logPath?.let { logPath ->
                            append("\n")
                            append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_log, logPath))
                        }
                    }
                })
            }
        }
    }

    private fun showDetailedBatchInstallResult(details: String, isError: Boolean = false) {
        if (isError) {
            showError(activity.getString(R.string.bootstrap_settings_extensions_batch_result_error_banner))
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_batch_result_title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toBatchExtensionFailure(
        input: String,
        fallbackMessage: String,
        exceptionMessage: String?,
        fallbackLogPath: String? = null
    ): BatchExtensionFailure {
        val rawMessage = exceptionMessage?.trim().orEmpty()
        val logPath = extractLogPath(rawMessage) ?: fallbackLogPath
        val message = rawMessage
            .replace(Regex("[\\s，,;；。]*日志[:：]\\s*[^\\r\\n]+"), "")
            .trim()
            .ifBlank { fallbackMessage }
        return BatchExtensionFailure(
            input = input,
            message = message,
            logPath = logPath
        )
    }

    private fun extractLogPath(message: String): String? {
        return Regex("日志[:：]\\s*([^\\r\\n]+)")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
    }

    private fun ensureRemoteSourcesReachable(normalizedRepositories: List<NormalizedExtensionRepository>) {
        if (normalizedRepositories.none(::requiresGithubReachabilityCheck)) {
            return
        }

        ensureGithubReachable()
    }

    private fun requiresGithubReachabilityCheck(repository: NormalizedExtensionRepository): Boolean {
        val host = runCatching { Uri.parse(repository.cloneUrl).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "github.com" || host == "www.github.com"
    }

    private fun ensureGithubReachable() {
        val failures = mutableListOf<String>()
        val checks = listOf(
            Triple("github.com", "https://github.com/robots.txt", setOf(200, 301, 302, 307, 308)),
            Triple("raw.githubusercontent.com", "https://raw.githubusercontent.com/github/gitignore/main/README.md", setOf(200, 301, 302, 307, 308)),
            Triple("api.github.com", "https://api.github.com/rate_limit", setOf(200, 301, 302, 307, 308, 403))
        )

        checks.forEach { (label, targetUrl, acceptedStatusCodes) ->
            val statusCode = runCatching { fetchReachabilityStatusCode(targetUrl) }.getOrElse { error ->
                failures += "$label (${error.message ?: "连接失败"})"
                return@forEach
            }

            if (statusCode !in acceptedStatusCodes) {
                failures += "$label (HTTP $statusCode)"
            }
        }

        if (failures.isNotEmpty()) {
            throw BootstrapException(activity.getString(R.string.bootstrap_settings_extensions_github_unreachable, failures.joinToString(separator = "；")))
        }
    }

    private fun checkRepositoryUrlsGithubReachability(
        repositoryUrls: List<String>,
        onResult: (reachable: Boolean, failureMessage: String?) -> Unit
    ) {
        val normalizedRepositories = repositoryUrls.mapNotNull(::normalizeRepositoryUrl)
        if (normalizedRepositories.none(::requiresGithubReachabilityCheck)) {
            onResult(true, null)
            return
        }

        activity.lifecycleScope.launch {
            val failureMessage = withContext(Dispatchers.IO) {
                runCatching {
                    ensureRemoteSourcesReachable(normalizedRepositories)
                    null
                }.getOrElse { exception ->
                    exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_github_unreachable, "github.com")
                }
            }
            onResult(failureMessage == null, failureMessage)
        }
    }

    private fun updateDialogMessage(
        dialog: androidx.appcompat.app.AlertDialog,
        baseMessage: String?,
        statusMessage: String?
    ) {
        val resolvedMessage = buildDialogMessage(baseMessage, statusMessage)
        val messageView = dialog.findViewById<TextView>(android.R.id.message)
        messageView?.text = resolvedMessage
        messageView?.isVisible = !resolvedMessage.isNullOrBlank()
    }

    private fun buildDialogMessage(baseMessage: String?, statusMessage: String?): String? {
        val messageParts = listOfNotNull(
            baseMessage?.trim()?.takeIf { it.isNotEmpty() },
            statusMessage?.trim()?.takeIf { it.isNotEmpty() }
        )
        return messageParts.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n\n")
    }

    private fun fetchReachabilityStatusCode(targetUrl: String): Int {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "SillyDroid-Android-Host")
        }

        return try {
            val responseCode = connection.responseCode
            (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { stream ->
                    stream.read()
                }
            responseCode
        } finally {
            connection.disconnect()
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
            if (!targetDir || !repoUrl) {
                throw new Error('Missing extension target or repository URL.');
            }

            ${extensionProgressHelpers()}

            const parentDir = path.dirname(targetDir);
            const backupDir = targetDir + '.sillydroid-backup-' + Date.now();
            fs.mkdirSync(parentDir, { recursive: true });

            try {
                writeProgress({ step: 'backup', indeterminate: true });
                if (fs.existsSync(targetDir)) {
                    fs.renameSync(targetDir, backupDir);
                }

                writeProgress({ step: 'prepare', indeterminate: true });
                await git.clone({
                    fs,
                    http,
                    dir: targetDir,
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
                const manifestPath = path.join(targetDir, 'manifest.json');
                if (!fs.existsSync(manifestPath)) {
                    throw new Error('Manifest file not found at ' + manifestPath);
                }

                JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
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

                throw error;
            }
        """.trimIndent()
    }

    private fun extensionInstallPreviewCommand(): String {
        return """
            import fs from 'node:fs';
            import path from 'node:path';
            import git from 'isomorphic-git';
            import http from 'isomorphic-git/http/node';

            const repoUrl = process.env.SILLYDROID_EXTENSION_REPO_URL;
            const repoBranch = process.env.SILLYDROID_EXTENSION_REPO_BRANCH || undefined;
            const previewFile = process.env.SILLYDROID_EXTENSION_PREVIEW_FILE;
            const tempDir = process.env.SILLYDROID_EXTENSION_TEMP_DIR;
            if (!repoUrl || !previewFile || !tempDir) {
                throw new Error('Missing preview environment.');
            }

            ${extensionProgressHelpers()}

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

                writeProgress({ step: 'prepare', indeterminate: true });
                await git.clone({
                    fs,
                    http,
                    dir: tempDir,
                    url: parsedUrl.href,
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
                writeProgress({ step: 'completed', loaded: 1, total: 1 });
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

    private fun readRuntimeProgress(file: File): Pair<String, ExtensionRuntimeProgress>? {
        if (!file.exists()) {
            return null
        }

        return runCatching {
            val rawPayload = file.readText()
            if (rawPayload.isBlank()) {
                return null
            }

            val json = JSONObject(rawPayload)
            rawPayload to ExtensionRuntimeProgress(
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

    private data class BatchProgressDescriptor(
        val currentIndex: Int,
        val totalCount: Int,
        val itemLabel: String
    )

    private fun publishRuntimeProgress(actionLabel: String, runtimeProgress: ExtensionRuntimeProgress) {
        val nextState = mapProgressState(actionLabel, runtimeProgress)
        activity.runOnUiThread {
            progressState = nextState
            renderProgress()
        }
    }

    private fun publishBatchRuntimeProgress(
        actionLabel: String,
        descriptor: BatchProgressDescriptor,
        runtimeProgress: ExtensionRuntimeProgress
    ) {
        val mappedProgress = mapProgressState(actionLabel, runtimeProgress)
        publishBatchProgress(
            actionLabel = actionLabel,
            descriptor = descriptor,
            stageLabel = mappedProgress.stageLabel,
            itemPercent = mappedProgress.percent,
            indeterminate = mappedProgress.indeterminate
        )
    }

    private fun publishBatchProgress(
        actionLabel: String,
        descriptor: BatchProgressDescriptor,
        stageLabel: String,
        itemPercent: Int?,
        indeterminate: Boolean
    ) {
        val overallPercent = itemPercent?.let { percent ->
            val safePercent = percent.coerceIn(0, 100)
            ((((descriptor.currentIndex - 1).toDouble() + (safePercent / 100.0)) / descriptor.totalCount.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        }
        val nextState = ExtensionProgressState(
            actionLabel = actionLabel,
            stageLabel = activity.getString(
                R.string.bootstrap_settings_extensions_batch_item_stage,
                descriptor.currentIndex,
                descriptor.totalCount,
                descriptor.itemLabel,
                stageLabel
            ),
            percent = overallPercent,
            indeterminate = indeterminate || overallPercent == null
        )
        activity.runOnUiThread {
            progressState = nextState
            renderProgress()
        }
    }

    private fun mapProgressState(actionLabel: String, runtimeProgress: ExtensionRuntimeProgress): ExtensionProgressState {
        val stageLabel = when {
            !runtimeProgress.message.isNullOrBlank() -> runtimeProgress.message
            runtimeProgress.step == "backup" -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_backup)
            runtimeProgress.step == "validate" -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_validating)
            runtimeProgress.step == "completed" -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_completed)
            runtimeProgress.phase?.contains("receiving objects", ignoreCase = true) == true -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_receiving)
            runtimeProgress.phase?.contains("resolving deltas", ignoreCase = true) == true -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_resolving)
            runtimeProgress.phase?.contains("updating workdir", ignoreCase = true) == true -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating)
            else -> activity.getString(R.string.bootstrap_settings_extensions_progress_stage_prepare)
        }

        val percent = when {
            runtimeProgress.step == "completed" -> 100
            runtimeProgress.phase?.contains("receiving objects", ignoreCase = true) == true -> scaleProgress(runtimeProgress.loaded, runtimeProgress.total, 6, 82)
            runtimeProgress.phase?.contains("resolving deltas", ignoreCase = true) == true -> scaleProgress(runtimeProgress.loaded, runtimeProgress.total, 82, 94)
            runtimeProgress.phase?.contains("updating workdir", ignoreCase = true) == true -> scaleProgress(runtimeProgress.loaded, runtimeProgress.total, 94, 99)
            else -> null
        }

        return ExtensionProgressState(
            actionLabel = actionLabel,
            stageLabel = stageLabel,
            percent = percent,
            indeterminate = runtimeProgress.indeterminate || percent == null
        )
    }

    private fun repositoryDisplayLabel(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository? = normalizeRepositoryUrl(repositoryUrl)
    ): String {
        val cloneUrl = normalizedRepository?.cloneUrl ?: repositoryUrl.trim()
        val pathSegments = runCatching { Uri.parse(cloneUrl).pathSegments.orEmpty() }.getOrDefault(emptyList())
            .map(::stripGitSuffix)
            .filter { it.isNotBlank() }
        return when {
            pathSegments.size >= 2 -> pathSegments.takeLast(2).joinToString("/")
            pathSegments.isNotEmpty() -> pathSegments.joinToString("/")
            else -> cloneUrl
        }
    }

    private fun scaleProgress(loaded: Int?, total: Int?, minPercent: Int, maxPercent: Int): Int? {
        val safeLoaded = loaded ?: return null
        val safeTotal = total ?: return null
        if (safeTotal <= 0) {
            return null
        }

        val boundedRatio = safeLoaded.coerceIn(0, safeTotal).toDouble() / safeTotal.toDouble()
        return (minPercent + ((maxPercent - minPercent) * boundedRatio).toInt()).coerceIn(minPercent, maxPercent)
    }

    private fun setProgressState(actionLabel: String, stageLabel: String, percent: Int?, indeterminate: Boolean) {
        progressState = ExtensionProgressState(
            actionLabel = actionLabel,
            stageLabel = stageLabel,
            percent = percent,
            indeterminate = indeterminate
        )
        renderProgress()
    }

    private fun clearProgressState() {
        progressState = null
        renderProgress()
    }

    private fun renderProgress() {
        val currentProgress = progressState
        if (currentProgress == null) {
            progressHost.hide()
            return
        }

        val message = if (!currentProgress.indeterminate && currentProgress.percent != null) {
            activity.getString(
                R.string.bootstrap_settings_extensions_progress_label,
                currentProgress.actionLabel,
                currentProgress.stageLabel,
                currentProgress.percent
            )
        } else {
            activity.getString(
                R.string.bootstrap_settings_extensions_progress_indeterminate,
                currentProgress.actionLabel,
                currentProgress.stageLabel
            )
        }

        progressHost.show(
            message = message,
            percent = currentProgress.percent,
            indeterminate = currentProgress.indeterminate
        )
    }

    private fun setBusyState(value: Boolean) {
        busy = value
        setBusy(value)
        renderExtensions()
    }

    private fun resolveColor(attr: Int): Int {
        return MaterialColors.getColor(activity, attr, 0)
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private fun dimenFloat(resId: Int): Float {
        return activity.resources.getDimension(resId)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}

