package com.jm.sillydroid.feature.settings.ui.extensions

import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.lifecycleScope
import com.jm.sillydroid.core.model.extensions.BatchExtensionFailure
import com.jm.sillydroid.core.model.extensions.BatchExtensionInstallResult
import com.jm.sillydroid.core.model.extensions.BatchExtensionPreview
import com.jm.sillydroid.core.model.extensions.BrokenExtensionDirectory
import com.jm.sillydroid.core.model.extensions.BundledExtension
import com.jm.sillydroid.core.model.extensions.DefaultExtensionRepository
import com.jm.sillydroid.core.model.extensions.ExtensionInventory
import com.jm.sillydroid.core.model.extensions.ExtensionInstallPreview
import com.jm.sillydroid.core.model.extensions.ExtensionKind
import com.jm.sillydroid.core.model.extensions.ExtensionRuntimeProgress
import com.jm.sillydroid.core.model.extensions.ManagedExtension
import com.jm.sillydroid.core.model.extensions.NormalizedExtensionRepository
import com.jm.sillydroid.domain.extensions.ExtensionsRepository
import com.jm.sillydroid.feature.settings.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.R as MaterialR

interface ExtensionInstallProgressHost {
    fun show(message: String, percent: Int?, indeterminate: Boolean)

    fun hide()
}

class ViewExtensionInstallProgressHost(
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

class BootstrapSettingsExtensionsCoordinator(
    private val activity: AppCompatActivity,
    private val dispatchers: DispatcherProvider,
    private val listContainer: LinearLayout,
    private val emptyView: TextView,
    private val installButton: ImageButton,
    private val batchDeleteButton: ImageButton,
    private val reloadButton: ImageButton,
    private val progressHost: ExtensionInstallProgressHost,
    private val extensionsRepository: ExtensionsRepository,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showBanner: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val onTavernUiReloadRequired: () -> Unit
) : DefaultLifecycleObserver {
    constructor(
        activity: AppCompatActivity,
        dispatchers: DispatcherProvider,
        listContainer: LinearLayout,
        emptyView: TextView,
        installButton: ImageButton,
        batchDeleteButton: ImageButton,
        reloadButton: ImageButton,
        progressIndicator: LinearProgressIndicator,
        progressLabel: TextView,
        extensionsRepository: ExtensionsRepository,
        setBusy: (Boolean) -> Unit,
        showError: (String) -> Unit,
        showBanner: (String) -> Unit,
        showMessage: (String) -> Unit,
        onTavernUiReloadRequired: () -> Unit
    ) : this(
        activity = activity,
        dispatchers = dispatchers,
        listContainer = listContainer,
        emptyView = emptyView,
        installButton = installButton,
        batchDeleteButton = batchDeleteButton,
        reloadButton = reloadButton,
        progressHost = ViewExtensionInstallProgressHost(activity, progressIndicator, progressLabel),
        extensionsRepository = extensionsRepository,
        setBusy = setBusy,
        showError = showError,
        showBanner = showBanner,
        showMessage = showMessage,
        onTavernUiReloadRequired = onTavernUiReloadRequired
    )

    private enum class ExtensionInstallMode {
        OVERWRITE,
        SKIP
    }

    private val progressController = ExtensionProgressController(activity, progressHost)
    private var extensions: List<ManagedExtension> = emptyList()
    private var bundledExtensions: List<BundledExtension> = emptyList()
    private var busy = false

    fun initialize() {
        installButton.setOnClickListener {
            promptInstallExtension()
        }
        batchDeleteButton.setOnClickListener {
            promptDeleteExtensionsBatch()
        }
        reloadButton.setOnClickListener {
            reloadExtensions()
        }
        renderExtensions()
        progressController.refresh()
        reloadExtensions()
        activity.lifecycle.addObserver(this)
    }

    fun reloadExtensions() {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
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
        return extensionsRepository.normalizedRepositoryKey(repository)
    }

    private fun loadExtensionInventory(): ExtensionInventory {
        return extensionsRepository.loadInventory()
    }

    private fun renderExtensions() {
        installButton.isEnabled = !busy
        batchDeleteButton.isEnabled = !busy
        reloadButton.isEnabled = !busy
        progressController.refresh()
        listContainer.removeAllViews()
        val missingBundledExtensions = bundledExtensions.filterNot { it.targetExists }
        val globalExtensions = extensions.filter { it.kind == ExtensionKind.GLOBAL }
        val userExtensions = extensions.filter { it.kind == ExtensionKind.USER }
        // 全局扩展段落标题永远存在并带上 3 个操作按钮，所以单独的 emptyView 已经没必要再展示；
        // 即便没有任何已安装扩展，用户也能从段落标题右侧的按钮直接发起安装。
        emptyView.isVisible = false

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
                    topMargin = dimen(R.dimen.sillydroid_section_card_spacing)
                })
                itemIndex += 1
            }
        }

        // “全局扩展”段落标题总是展示，后面跟随 3 个操作按钮（安装/批量删除/重新加载），
        // 即使当前未安装任何全局扩展也需要保留入口。
        listContainer.addView(createSectionHeader(
            title = activity.getString(R.string.bootstrap_settings_extensions_installed_title),
            summary = activity.getString(R.string.bootstrap_settings_extensions_installed_summary),
            actionsView = buildGlobalExtensionsActionsRow()
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = if (itemIndex == 0) 0 else dimen(R.dimen.sillydroid_space_xl)
        })

        globalExtensions.forEachIndexed { index, extension ->
            listContainer.addView(createExtensionCard(extension), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0 || itemIndex > 0) {
                    topMargin = dimen(R.dimen.sillydroid_section_card_spacing)
                }
            })
        }

        if (userExtensions.isNotEmpty()) {
            itemIndex += globalExtensions.size
            listContainer.addView(createSectionHeader(
                title = activity.getString(R.string.bootstrap_settings_extensions_user_title),
                summary = activity.getString(R.string.bootstrap_settings_extensions_user_summary)
            ), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_space_xl)
            })
        }

        userExtensions.forEach { extension ->
            listContainer.addView(createExtensionCard(extension), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_section_card_spacing)
            })
        }
    }

    private fun createSectionHeader(
        title: String,
        summary: String,
        actionsView: View? = null
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = title
                    TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
                    setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                })
                if (actionsView != null) {
                    addView(actionsView)
                }
            })
            addView(TextView(activity).apply {
                text = summary
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsMeta)
                setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
                // 下方加大 padding，让“这里列出当前宿主 xxxx”这段描述与下方卡片之间有足够呼吸空间。
                setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, dimen(R.dimen.sillydroid_space_md))
            })
        }
    }

    private fun buildGlobalExtensionsActionsRow(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 复用 XML 里已经创建的 3 个 ImageButton 实例（initialize() 里绑定的点击事件会跟随 view 一起走），
            // 只是把它们从 XML 顶部那个隐藏的 row 中重新挂到当前 “全局扩展” 标题右侧。
            val size = dimen(R.dimen.sillydroid_settings_dense_icon_button_size)
            listOf(installButton, batchDeleteButton, reloadButton).forEach { button ->
                (button.parent as? ViewGroup)?.removeView(button)
                button.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dimen(R.dimen.sillydroid_space_xs)
                }
                addView(button)
            }
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
            val kindLabel = when (extension.kind) {
                ExtensionKind.GLOBAL -> activity.getString(R.string.bootstrap_settings_extensions_kind_global)
                ExtensionKind.USER -> activity.getString(R.string.bootstrap_settings_extensions_kind_user)
            }
            text = "$kindLabel  ·  ${activity.getString(R.string.bootstrap_settings_extensions_folder, extension.folderName)}"
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

        val bundledReinstallSource = findBundledReinstallSource(extension)
        val isHostExtension = extension.folderName.contains("android-host", ignoreCase = true)
        val reinstallButton = createActionIconButton(
            iconResId = R.drawable.ic_restore_defaults,
            contentDescriptionResId = R.string.bootstrap_settings_extensions_reinstall
        ) {
            if (bundledReinstallSource != null) {
                confirmReinstallBundled(extension, bundledReinstallSource)
            } else if (isHostExtension) {
                confirmReinstallHost(extension)
            } else if (extension.homePage.isNullOrBlank()) {
                showError(activity.getString(R.string.bootstrap_settings_extensions_homepage_missing))
            } else {
                confirmReinstall(extension)
            }
        }.apply {
            isEnabled = !busy && (bundledReinstallSource != null || isHostExtension || !extension.homePage.isNullOrBlank())
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

    private fun confirmReinstallBundled(extension: ManagedExtension, bundledSource: BundledExtension) {
        val message = activity.getString(
            R.string.bootstrap_settings_extensions_reinstall_confirm_message,
            extension.displayName
        ) + "\n\n" + "该扩展将使用 APK 内置版本覆盖安装（不走 URL 拉取）。"

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_reinstall_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_reinstall) { _, _ ->
                reinstallBundledExtension(extension, bundledSource)
            }
            .show()
    }

    private fun confirmReinstallHost(extension: ManagedExtension) {
        // 查找 bundled-extensions 中的任何 host 类扩展
        val hostBundled = bundledExtensions.firstOrNull { it.category.equals("host", ignoreCase = true) }
        if (hostBundled != null) {
            confirmReinstallBundled(extension, hostBundled)
        } else {
            val message = activity.getString(
                R.string.bootstrap_settings_extensions_reinstall_confirm_message,
                extension.displayName
            ) + "\n\n" + "APK 内未找到内置版本，将尝试从文件重装。"

            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.bootstrap_settings_extensions_reinstall_confirm_title)
                .setMessage(message)
                .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
                .setPositiveButton(R.string.bootstrap_settings_extensions_reinstall) { _, _ ->
                    reinstallExtension(extension)
                }
                .show()
        }
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
            val result = withContext(dispatchers.io) {
                runCatching {
                    extensionsRepository.deleteExtension(extension)
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
            progressController.setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_action_delete_batch),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_delete_batch_progress, selectedExtensions.size),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    extensionsRepository.deleteExtensions(selectedExtensions)
                }
            }
            setBusyState(false)
            progressController.clear()

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
        findBundledReinstallSource(extension)?.let { bundledSource ->
            reinstallBundledExtension(extension, bundledSource)
            return
        }

        val repositoryUrl = extension.homePage ?: run {
            showError(activity.getString(R.string.bootstrap_settings_extensions_homepage_missing))
            return
        }
        val normalizedRepository = normalizeRepositoryUrl(repositoryUrl) ?: run {
            showError(activity.getString(R.string.bootstrap_settings_extensions_homepage_invalid))
            return
        }

        activity.lifecycleScope.launch {
            progressController.setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_reinstall),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_prepare),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    runExtensionReinstall(
                        extension.folderName,
                        normalizedRepository,
                        kind = extension.kind,
                        onProgress = { runtimeProgress ->
                            progressController.publishRuntimeProgress(
                                activity.getString(R.string.bootstrap_settings_extensions_progress_action_reinstall),
                                runtimeProgress
                            )
                        }
                    )
                }
            }
            setBusyState(false)
            progressController.clear()

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

    private fun reinstallBundledExtension(extension: ManagedExtension, bundledSource: BundledExtension) {
        activity.lifecycleScope.launch {
            progressController.setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_reinstall),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    extensionsRepository.reinstallBundledExtension(extension, bundledSource)
                }
            }
            setBusyState(false)
            progressController.clear()

            result.onSuccess { installResult ->
                val message = if (installResult.migratedFromFolderName == null) {
                    activity.getString(R.string.bootstrap_settings_extensions_reinstall_success, extension.displayName)
                } else {
                    activity.getString(R.string.bootstrap_settings_extensions_reinstall_success, extension.displayName) +
                        "\n\n" + "已迁移到 ${bundledSource.folderName}。"
                }
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

    private fun findBundledReinstallSource(extension: ManagedExtension): BundledExtension? {
        return bundledExtensions.firstOrNull { bundled ->
            bundled.category.equals("host", ignoreCase = true) &&
                (
                    bundled.displayName.equals(extension.displayName, ignoreCase = true) ||
                        bundled.folderName.equals(extension.folderName, ignoreCase = true)
                )
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
        if (bundledExtensions.isNotEmpty()) {
            actions += activity.getString(R.string.bootstrap_settings_extensions_action_install_bundled) to {
                promptInstallBundledExtension(bundledExtensions)
            }
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

        activity.lifecycleScope.launch {
            progressController.setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_preview),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_github_checking),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    val normalizedRepositories = repositories.mapNotNull { repository ->
                        normalizeRepositoryUrl(repository.repositoryUrl)
                    }
                    ensureRemoteSourcesReachable(normalizedRepositories)
                }
            }
            setBusyState(false)
            progressController.clear()

            result.onSuccess {
                promptDefaultRepositorySelectionDialog(repositories)
            }.onFailure { exception ->
                showError(
                    exception.message ?: activity.getString(
                        R.string.bootstrap_settings_extensions_github_unreachable,
                        "github.com"
                    )
                )
            }
        }
    }

    private fun promptDefaultRepositorySelectionDialog(repositories: List<DefaultExtensionRepository>) {
        val checkedItems = BooleanArray(repositories.size) { true }
        val contentView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_control_padding_horizontal),
                0,
                dimen(R.dimen.sillydroid_control_padding_horizontal),
                0
            )
        }
        val summaryView = TextView(activity).apply {
            text = activity.getString(
                R.string.bootstrap_settings_extensions_default_picker_message_count,
                repositories.size
            )
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, dimen(R.dimen.sillydroid_space_sm))
        }
        val selectAllCheckBox = MaterialCheckBox(activity).apply {
            text = activity.getString(R.string.bootstrap_settings_extensions_default_select_all)
            isChecked = true
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val checklistContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(checklistContainer)
        }

        contentView.addView(summaryView)
        contentView.addView(selectAllCheckBox)
        contentView.addView(scrollView)

        val itemCheckBoxes = mutableListOf<MaterialCheckBox>()
        repositories.forEachIndexed { index, repository ->
            val itemLabel = repository.description?.takeIf { it.isNotBlank() }?.let { description ->
                "${repository.displayName}\n$description"
            } ?: repository.displayName
            val checkBox = MaterialCheckBox(activity).apply {
                text = itemLabel
                isChecked = true
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 0, 0, dimen(R.dimen.sillydroid_space_xs))
            }
            checkedItems[index] = true
            itemCheckBoxes += checkBox
            checklistContainer.addView(checkBox)
        }

        var suppressSelectionCallback = false
        fun syncSelectAllState() {
            if (suppressSelectionCallback) {
                return
            }
            suppressSelectionCallback = true
            selectAllCheckBox.isChecked = itemCheckBoxes.all { checkBox -> checkBox.isChecked }
            suppressSelectionCallback = false
        }

        selectAllCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSelectionCallback) {
                return@setOnCheckedChangeListener
            }
            suppressSelectionCallback = true
            itemCheckBoxes.forEachIndexed { index, checkBox ->
                checkedItems[index] = isChecked
                checkBox.isChecked = isChecked
            }
            suppressSelectionCallback = false
        }

        itemCheckBoxes.forEachIndexed { index, checkBox ->
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                checkedItems[index] = isChecked
                syncSelectAllState()
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_default_title)
            .setView(contentView)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setNeutralButton(R.string.bootstrap_settings_extensions_default_existing_strategy_skip, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_default_existing_strategy_overwrite, null)
            .create()

        dialog.setOnShowListener {
            fun submit(installMode: ExtensionInstallMode) {
                val selectedRepositories = repositories.filterIndexed { index, _ -> checkedItems[index] }
                if (selectedRepositories.isEmpty()) {
                    showError(activity.getString(R.string.bootstrap_settings_extensions_default_select_required))
                    return
                }

                dialog.dismiss()
                beginRepositoryInstallFlow(
                    repositoryUrls = selectedRepositories.map { repository -> repository.repositoryUrl },
                    installMode = installMode,
                    allowUserInstallChoice = false
                )
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                submit(ExtensionInstallMode.SKIP)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submit(ExtensionInstallMode.OVERWRITE)
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

        beginRepositoryInstallFlow(
            repositoryUrls = repositories.map { it.repositoryUrl },
            installMode = ExtensionInstallMode.SKIP,
            allowUserInstallChoice = false,
            skipConfirmation = true
        )
    }

    fun checkDefaultRepositoriesGithubReachability(onResult: (reachable: Boolean, failureMessage: String?) -> Unit) {
        val repositoryUrls = loadDefaultExtensionRepositories().map { it.repositoryUrl }
        checkRepositoryUrlsGithubReachability(repositoryUrls, onResult)
    }

    private fun continueRepositoryBatchInstall(
        batchPreview: BatchExtensionPreview,
        installMode: ExtensionInstallMode,
        skipConfirmation: Boolean,
        allowUserInstallChoice: Boolean
    ) {
        if (installMode == ExtensionInstallMode.OVERWRITE) {
            if (skipConfirmation) {
                installPreviewedExtensionsBatch(batchPreview)
            } else {
                confirmBatchInstallPreview(batchPreview, allowUserInstallChoice = allowUserInstallChoice)
            }
            return
        }

        val installablePreviews = batchPreview.previews.filterNot { preview -> preview.targetExists }
        val skippedPreviews = batchPreview.previews.filter { preview -> preview.targetExists }
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
            confirmBatchInstallPreview(resolvedPreview, allowUserInstallChoice = allowUserInstallChoice)
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
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            isVerticalScrollBarEnabled = true
            minLines = 5
            maxLines = 10
            textSize = 13f
            minHeight = dimen(R.dimen.sillydroid_input_min_height)
            setPadding(dimen(R.dimen.sillydroid_control_padding_horizontal), dimen(R.dimen.sillydroid_control_padding_vertical), dimen(R.dimen.sillydroid_control_padding_horizontal), dimen(R.dimen.sillydroid_control_padding_vertical))
        }
        inputLayout.addView(inputView)

        // 包在一层 FrameLayout 里，给 dialog 内容区加上水平 / 垂直边距，
        // 避免 TextInputLayout 贴着弹窗左右边、与标题/按钮贴在一起。
        val contentWrapper = FrameLayout(activity).apply {
            setPadding(
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                dimen(R.dimen.sillydroid_dialog_content_padding_vertical),
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                dimen(R.dimen.sillydroid_dialog_content_padding_vertical)
            )
            addView(inputLayout)
        }

        inputView.doAfterTextChanged {
            inputLayout.error = null
            inputLayout.helperText = null
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_install_prompt_title)
            .setView(contentWrapper)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setNeutralButton(R.string.bootstrap_settings_extensions_default_existing_strategy_skip, null)
            .setPositiveButton(R.string.bootstrap_settings_extensions_default_existing_strategy_overwrite, null)
            .create()

        dialog.setOnShowListener {
            fun submit(installMode: ExtensionInstallMode) {
                val repositoryUrls = parseRepositoryUrls(inputView.text?.toString().orEmpty())
                if (repositoryUrls.isEmpty()) {
                    inputLayout.error = activity.getString(R.string.bootstrap_settings_extensions_install_empty)
                    return
                }

                inputLayout.error = null
                val supportedUrls = repositoryUrls.filter(::isSupportedRepositoryUrl)
                if (supportedUrls.size != repositoryUrls.size) {
                    inputLayout.error = activity.getString(R.string.bootstrap_settings_extensions_install_invalid_url)
                    return
                }

                dialog.dismiss()
                beginRepositoryInstallFlow(
                    repositoryUrls = repositoryUrls,
                    installMode = installMode,
                    allowUserInstallChoice = repositoryUrls.size == 1
                )
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                submit(ExtensionInstallMode.SKIP)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submit(ExtensionInstallMode.OVERWRITE)
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
            "- ${directory.folderName}"
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

        // 原「+ 按钮」菜单里的「清理错误插件」语义上属于删除，现完全迁移到删除按钮。
        val brokenDirectories = findBrokenExtensionDirectories()
        if (brokenDirectories.isNotEmpty()) {
            val cleanupLabel = activity.getString(
                R.string.bootstrap_settings_extensions_delete_batch_menu_cleanup_broken,
                brokenDirectories.size
            )
            val pickLabel = activity.getString(
                R.string.bootstrap_settings_extensions_delete_batch_menu_pick_extensions
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.bootstrap_settings_extensions_delete_batch_menu_title)
                .setItems(arrayOf(cleanupLabel, pickLabel)) { _, which ->
                    when (which) {
                        0 -> promptCleanupBrokenExtensions()
                        1 -> showDeleteExtensionsBatchPicker()
                    }
                }
                .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
                .show()
            return
        }

        showDeleteExtensionsBatchPicker()
    }

    private fun showDeleteExtensionsBatchPicker() {
        // sillydroid-android-host 是宿主侧 bundled 扩展，被用户误删后会直接干掉悬浮按钮 / 下拉刷新 / 原生通知等宿主能力，
        // 以及「安装默认扩展」里不包含它，要手动从 bundled 重装才能恢复。批量删除里直接过滤掉，避免误选。
        val deletableExtensions = extensions.filterNot {
            it.folderName.equals(ANDROID_HOST_EXTENSION_FOLDER, ignoreCase = true)
        }
        if (deletableExtensions.isEmpty()) {
            showMessage(activity.getString(R.string.bootstrap_settings_extensions_empty))
            return
        }

        val checkedItems = BooleanArray(deletableExtensions.size)
        val labels = deletableExtensions.map { extension ->
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
                val selectedExtensions = deletableExtensions.filterIndexed { index, _ -> checkedItems[index] }
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
            progressController.setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_install),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    extensionsRepository.installBundledExtension(extension)
                }
            }
            setBusyState(false)
            progressController.clear()

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

    private fun beginRepositoryInstallFlow(
        repositoryUrls: List<String>,
        installMode: ExtensionInstallMode,
        allowUserInstallChoice: Boolean,
        skipConfirmation: Boolean = false
    ) {
        previewInstallExtensionsBatch(repositoryUrls) { batchPreview ->
            continueRepositoryBatchInstall(
                batchPreview = batchPreview,
                installMode = installMode,
                skipConfirmation = skipConfirmation,
                allowUserInstallChoice = allowUserInstallChoice
            )
        }
    }

    private fun previewInstallExtensionsBatch(
        repositoryUrls: List<String>,
        onPreviewReady: ((BatchExtensionPreview) -> Unit)? = null
    ) {
        activity.lifecycleScope.launch {
            val previewActionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_preview)
            progressController.setProgressState(
                actionLabel = previewActionLabel,
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_github_checking),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    val normalizedRepositories = repositoryUrls.map { repositoryUrl ->
                        repositoryUrl to normalizeRepositoryUrl(repositoryUrl)
                    }
                    ensureRemoteSourcesReachable(
                        normalizedRepositories.mapNotNull { (_, normalizedRepository) -> normalizedRepository }
                    )

                    activity.runOnUiThread {
                        progressController.setProgressState(
                            actionLabel = previewActionLabel,
                            stageLabel = activity.getString(R.string.bootstrap_settings_extensions_batch_preview_stage, repositoryUrls.size),
                            percent = 0,
                            indeterminate = false
                        )
                    }

                    val previews = mutableListOf<ExtensionInstallPreview>()
                    val failures = mutableListOf<BatchExtensionFailure>()

                    normalizedRepositories.forEachIndexed { index, candidate ->
                        val repositoryUrl = candidate.first
                        val normalizedRepository = candidate.second
                        val batchProgress = BatchProgressDescriptor(
                            currentIndex = index + 1,
                            totalCount = normalizedRepositories.size,
                            itemLabel = repositoryDisplayLabel(repositoryUrl, normalizedRepository)
                        )
                        progressController.publishBatchProgress(
                            actionLabel = previewActionLabel,
                            descriptor = batchProgress,
                            stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_validating),
                            itemPercent = 0,
                            indeterminate = false
                        )

                        if (normalizedRepository == null) {
                            failures += BatchExtensionFailure(
                                input = repositoryUrl,
                                message = activity.getString(R.string.bootstrap_settings_extensions_install_invalid_url),
                                logPath = null
                            )
                            progressController.publishBatchProgress(
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
                                normalizedRepository
                            )
                        }.onSuccess { preview ->
                            previews += preview
                            progressController.publishBatchProgress(
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
                            progressController.publishBatchProgress(
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
            progressController.clear()

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

    private fun confirmBatchInstallPreview(
        batchPreview: BatchExtensionPreview,
        allowUserInstallChoice: Boolean = false
    ) {
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

        val dialogBuilder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_extensions_batch_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
        if (allowUserInstallChoice && batchPreview.previews.size == 1) {
            dialogBuilder
                .setNeutralButton(R.string.bootstrap_settings_extensions_kind_user) { _, _ ->
                    installPreviewedExtension(batchPreview.previews.single(), ExtensionKind.USER)
                }
                .setPositiveButton(R.string.bootstrap_settings_extensions_kind_global) { _, _ ->
                    installPreviewedExtension(batchPreview.previews.single(), ExtensionKind.GLOBAL)
                }
        } else {
            dialogBuilder.setPositiveButton(R.string.bootstrap_settings_extensions_install) { _, _ ->
                installPreviewedExtensionsBatch(batchPreview)
            }
        }
        dialogBuilder.show()
    }

    private fun installPreviewedExtension(preview: ExtensionInstallPreview, kind: ExtensionKind = ExtensionKind.GLOBAL) {
        activity.lifecycleScope.launch {
            progressController.setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_install),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    runExtensionInstall(preview, kind) { runtimeProgress ->
                        progressController.publishRuntimeProgress(
                            activity.getString(R.string.bootstrap_settings_extensions_progress_action_install),
                            runtimeProgress
                        )
                    }
                }
            }
            setBusyState(false)
            progressController.clear()

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

    private fun installPreviewedExtensionsBatch(batchPreview: BatchExtensionPreview, kind: ExtensionKind = ExtensionKind.GLOBAL) {
        activity.lifecycleScope.launch {
            val installActionLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_action_install)
            progressController.setProgressState(
                actionLabel = installActionLabel,
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_batch_install_stage, batchPreview.previews.size),
                percent = 0,
                indeterminate = false
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    val successes = mutableListOf<ExtensionInstallPreview>()
                    val failures = batchPreview.failures.toMutableList()
                    batchPreview.previews.forEachIndexed { index, preview ->
                        val batchProgress = BatchProgressDescriptor(
                            currentIndex = index + 1,
                            totalCount = batchPreview.previews.size,
                            itemLabel = preview.displayName
                        )
                        progressController.publishBatchProgress(
                            actionLabel = installActionLabel,
                            descriptor = batchProgress,
                            stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                            itemPercent = 0,
                            indeterminate = false
                        )
                        runCatching {
                            runExtensionInstall(preview, kind) { runtimeProgress ->
                                progressController.publishBatchRuntimeProgress(
                                    actionLabel = installActionLabel,
                                    descriptor = batchProgress,
                                    runtimeProgress = runtimeProgress
                                )
                            }
                        }.onSuccess {
                            successes += preview
                            progressController.publishBatchProgress(
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
                                exceptionMessage = exception.message
                            )
                            progressController.publishBatchProgress(
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
            progressController.clear()

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
        kind: ExtensionKind = ExtensionKind.GLOBAL,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null
    ) {
        extensionsRepository.reinstall(
            folderName = folderName,
            repository = repository,
            kind = kind,
            onProgress = onProgress,
            failureMessage = { failedLogPath ->
                activity.getString(R.string.bootstrap_settings_extensions_runtime_failed, failedLogPath)
            }
        )
    }

    private fun runExtensionInstallPreview(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository,
    ): ExtensionInstallPreview {
        return try {
            extensionsRepository.buildInstallPreview(repositoryUrl, normalizedRepository)
        } catch (exception: IllegalStateException) {
            throw ExtensionUiException(exception.message ?: activity.getString(R.string.bootstrap_settings_extensions_install_manifest_precheck_unreachable))
        }
    }

    private fun runExtensionInstall(
        preview: ExtensionInstallPreview,
        kind: ExtensionKind = ExtensionKind.GLOBAL,
        onProgress: ((ExtensionRuntimeProgress) -> Unit)? = null
    ) {
        extensionsRepository.install(
            preview = preview,
            kind = kind,
            onProgress = onProgress,
            failureMessage = { failedLogPath ->
                activity.getString(R.string.bootstrap_settings_extensions_runtime_failed, failedLogPath)
            }
        )
    }

    private fun cleanupBrokenExtensions(directories: List<BrokenExtensionDirectory>) {
        activity.lifecycleScope.launch {
            progressController.setProgressState(
                actionLabel = activity.getString(R.string.bootstrap_settings_extensions_action_cleanup_broken),
                stageLabel = activity.getString(R.string.bootstrap_settings_extensions_progress_stage_updating),
                percent = null,
                indeterminate = true
            )
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    extensionsRepository.cleanupBrokenExtensions(directories)
                }
            }
            setBusyState(false)
            progressController.clear()

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
        return extensionsRepository.bundledExtensions()
    }

    private fun loadDefaultExtensionRepositories(): List<DefaultExtensionRepository> {
        return extensionsRepository.defaultRepositories()
    }

    private fun findBrokenExtensionDirectories(): List<BrokenExtensionDirectory> {
        return extensionsRepository.findBrokenExtensionDirectories()
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
                append(successes.joinToString(separator = "\n") { preview ->
                    "- ${preview.displayName}：成功"
                })
            }

            if (skipped.isNotEmpty()) {
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_skipped))
                append("\n")
                append(skipped.joinToString(separator = "\n") { preview ->
                    "- ${preview.displayName}：跳过"
                })
            }

            if (failures.isNotEmpty()) {
                append("\n")
                append(activity.getString(R.string.bootstrap_settings_extensions_batch_result_failures))
                append("\n")
                append(failures.joinToString(separator = "\n\n") { failure ->
                    buildString {
                        append("- ${failure.input}：失败（${failure.message}）")
                        append("\n")
                        append(
                            failure.logPath?.let { logPath ->
                                "  ${activity.getString(R.string.bootstrap_settings_extensions_batch_result_log, logPath)}"
                            } ?: "  ${activity.getString(R.string.bootstrap_settings_extensions_batch_result_log_unavailable)}"
                        )
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
        val failures = extensionsRepository.githubReachabilityFailures(normalizedRepositories)
        if (failures.isNotEmpty()) {
            throw ExtensionUiException(activity.getString(R.string.bootstrap_settings_extensions_github_unreachable, failures.joinToString(separator = "；")))
        }
    }

    private fun checkRepositoryUrlsGithubReachability(
        repositoryUrls: List<String>,
        onResult: (reachable: Boolean, failureMessage: String?) -> Unit
    ) {
        val normalizedRepositories = repositoryUrls.mapNotNull(::normalizeRepositoryUrl)
        if (normalizedRepositories.none(extensionsRepository::requiresGithubReachabilityCheck)) {
            onResult(true, null)
            return
        }

        activity.lifecycleScope.launch {
            val failureMessage = withContext(dispatchers.io) {
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

    private fun isSupportedRepositoryUrl(repositoryUrl: String): Boolean {
        return extensionsRepository.isSupportedRepositoryUrl(repositoryUrl)
    }

    private fun normalizeRepositoryUrl(repositoryUrl: String): NormalizedExtensionRepository? {
        return extensionsRepository.normalizeRepositoryUrl(repositoryUrl)
    }

    private fun repositoryDisplayLabel(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository? = normalizeRepositoryUrl(repositoryUrl)
    ): String {
        return extensionsRepository.repositoryDisplayLabel(repositoryUrl, normalizedRepository)
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

    private companion object {
        const val ANDROID_HOST_EXTENSION_FOLDER = "sillydroid-android-host"
    }
}

private class ExtensionUiException(message: String) : IllegalStateException(message)

private typealias BatchProgressDescriptor = ExtensionProgressController.BatchDescriptor

