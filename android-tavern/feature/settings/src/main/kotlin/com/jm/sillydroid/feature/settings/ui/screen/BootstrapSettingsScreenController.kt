package com.jm.sillydroid.feature.settings.ui.screen

import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.jm.sillydroid.core.model.settings.BrowserDataClearOptions
import com.jm.sillydroid.core.model.settings.BrowserDataClearTarget
import com.jm.sillydroid.core.model.settings.TavernDataArchiveKind
import com.jm.sillydroid.core.model.settings.TavernDataArchivePreview
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.SettingsTab
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.R as MaterialR

class BootstrapSettingsScreenController(
    private val activity: AppCompatActivity,
    private val rootView: android.view.View,
    private val topShellView: android.view.View,
    private val scrollView: NestedScrollView,
    private val tabLayout: TabLayout,
    private val toolbarAboutEntryView: TextView,
    private val dataPanelView: android.view.View,
    private val extensionsPanelView: android.view.View,
    private val logsPanelView: android.view.View,
    private val logsScrollView: NestedScrollView,
    private val terminalPanelView: android.view.View,
    private val settingsPanelView: android.view.View,
    private val aboutPanelView: android.view.View,
    private val bottomActionBarView: android.view.View,
    private val configPathView: TextView,
    private val warningView: TextView,
    private val loadingIndicator: LinearProgressIndicator,
    private val searchLayout: TextInputLayout,
    private val quickActionsButton: MaterialButton,
    private val floatingLogsSwitch: MaterialSwitch,
    private val pullRefreshSwitch: MaterialSwitch,
    private val hostDisplayModeRow: View,
    private val unrestrictedFileImportSelectionSwitch: MaterialSwitch,
    private val restoreDefaultsButton: ImageButton,
    private val importButton: MaterialButton,
    private val exportButton: MaterialButton,
    private val clearDataButton: MaterialButton,
    private val clearBrowserDataButton: MaterialButton,
    private val saveStartButton: MaterialButton,
    private val busyLockedControls: List<View> = emptyList(),
    private val onTabChanged: (SettingsTab) -> Unit = {}
) {
    private var selectedTab = SettingsTab.DATA
    private var bannerIsError = false
    private var busy = false
    private var hasUnsavedChanges = false
    private val scrollBottomBasePadding = scrollView.paddingBottom
    private val logsPanelBottomBasePadding = logsPanelView.paddingBottom
    private val terminalPanelBottomBasePadding = terminalPanelView.paddingBottom
    private val bottomActionBarBottomBasePadding = bottomActionBarView.paddingBottom
    private var latestSystemBarsBottomInset = 0
    private var latestImeBottomInset = 0
    private var latestImeVisible = false

    fun initialize() {
        setupTabs()
        setupAboutEntry()
        applyWindowInsets()
    }

    fun setConfigPath(filePath: String) {
        configPathView.text = filePath
    }

    fun setBusy(busy: Boolean) {
        this.busy = busy
        loadingIndicator.isVisible = busy
        // 宿主数据、扩展安装、日志导出、保存启动等任务不能并行触发；
        // busy 期间统一锁住所有会切页、写数据或发起异步任务的入口，避免状态交错。
        setTabNavigationEnabled(!busy)
        toolbarAboutEntryView.isEnabled = !busy
        searchLayout.isEnabled = !busy
        quickActionsButton.isEnabled = !busy
        floatingLogsSwitch.isEnabled = !busy
        pullRefreshSwitch.isEnabled = !busy
        hostDisplayModeRow.isEnabled = !busy
        unrestrictedFileImportSelectionSwitch.isEnabled = !busy
        restoreDefaultsButton.isEnabled = !busy
        importButton.isEnabled = !busy
        exportButton.isEnabled = !busy
        clearDataButton.isEnabled = !busy
        clearBrowserDataButton.isEnabled = !busy
        busyLockedControls.forEach { view ->
            view.isEnabled = !busy
        }
        syncSaveStartButtonState()
    }

    fun isBusy(): Boolean {
        return busy
    }

    fun updateDirtyState(hasUnsavedChanges: Boolean) {
        this.hasUnsavedChanges = hasUnsavedChanges
        saveStartButton.text = if (hasUnsavedChanges) {
            activity.getString(R.string.bootstrap_settings_save_start_dirty)
        } else {
            activity.getString(R.string.bootstrap_settings_save_start)
        }
        syncSaveStartButtonState()
    }

    fun focusValidationTab(isQuickField: Boolean) {
        // 启动端口等 quick field 已经迁回“酒馆设置”页签；
        // 校验失败时仍要把焦点切到真实承载该字段的页签，避免继续误跳到数据页。
        val targetTab = SettingsTab.SETTINGS
        targetTab.tabPosition?.let { position ->
            tabLayout.getTabAt(position)?.select()
        }
    }

    fun showBanner(message: String?, isError: Boolean = false) {
        bannerIsError = isError
        warningView.isVisible = !message.isNullOrBlank()
        warningView.text = message.orEmpty()
        if (message.isNullOrBlank()) {
            return
        }

        if (isError) {
            applyRoundedBannerStyle(
                backgroundColorAttr = MaterialR.attr.colorErrorContainer,
                textColorAttr = MaterialR.attr.colorOnErrorContainer
            )
        } else {
            applyRoundedBannerStyle(
                backgroundColorAttr = MaterialR.attr.colorSecondaryContainer,
                textColorAttr = MaterialR.attr.colorOnSecondaryContainer
            )
        }
    }

    fun clearErrorBanner() {
        if (bannerIsError) {
            showBanner(null)
        }
    }

    fun showMessage(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    fun confirmRestoreDefaults(onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_restore_defaults_confirm_title)
            .setMessage(R.string.bootstrap_settings_restore_defaults_confirm_message)
            .setNegativeButton(R.string.bootstrap_settings_restore_defaults_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_restore_defaults_confirm_action) { _, _ ->
                onConfirm()
            }
            .show()
    }

    fun confirmDiscardChanges(onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_discard_changes_title)
            .setMessage(R.string.bootstrap_settings_discard_changes_message)
            .setNegativeButton(R.string.bootstrap_settings_discard_changes_keep, null)
            .setPositiveButton(R.string.bootstrap_settings_discard_changes_confirm) { _, _ ->
                onConfirm()
            }
            .show()
    }

    fun confirmImport(preview: TavernDataArchivePreview, onConfirm: () -> Unit) {
        val (titleRes, baseMessage) = when (preview.archiveKind) {
            TavernDataArchiveKind.USER_BACKUP -> {
                val sourceUserId = preview.sourceUserId ?: activity.getString(R.string.bootstrap_settings_import_unknown_user)
                val targetUserId = preview.targetUserId ?: activity.getString(R.string.bootstrap_settings_import_unknown_user)
                R.string.bootstrap_settings_import_confirm_title_user to activity.getString(
                    R.string.bootstrap_settings_import_confirm_message_user,
                    sourceUserId,
                    targetUserId
                )
            }

            TavernDataArchiveKind.HOST_FULL_SNAPSHOT -> {
                val baseMessage = activity.getString(
                    R.string.bootstrap_settings_import_confirm_message_host
                )
                val sourceLayoutLine = preview.sourceLayoutLabel?.let { "\n\n识别来源：$it" }.orEmpty()
                R.string.bootstrap_settings_import_confirm_title_host to (baseMessage + sourceLayoutLine)
            }
        }

        val writeTargetsBlock = if (preview.writeTargets.isNotEmpty()) {
            "\n\n将写入目录：\n" + preview.writeTargets.joinToString(separator = "\n") { "- $it" }
        } else {
            ""
        }

        val statsBlock = if (preview.contentStats.isNotEmpty()) {
            "\n\n包内容统计：\n" + preview.contentStats.joinToString(separator = "\n") { "- $it" }
        } else {
            ""
        }

        val message = baseMessage + writeTargetsBlock + statsBlock

        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_import_confirm_action) { _, _ ->
                onConfirm()
            }
            .show()
    }

    fun confirmClearData(onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_clear_data_confirm_title)
            .setMessage(R.string.bootstrap_settings_clear_data_confirm_message)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_clear_data_confirm_action) { _, _ ->
                onConfirm()
            }
            .show()
    }

    fun confirmClearBrowserData(onConfirm: (Int) -> Unit) {
        val targets = BrowserDataClearTarget.values()
        val labels = targets.map { target ->
            when (target) {
                BrowserDataClearTarget.RESOURCE_CACHE -> activity.getString(R.string.bootstrap_settings_clear_browser_data_target_resource_cache)
                BrowserDataClearTarget.SITE_STORAGE -> activity.getString(R.string.bootstrap_settings_clear_browser_data_target_site_storage)
                BrowserDataClearTarget.COOKIES -> activity.getString(R.string.bootstrap_settings_clear_browser_data_target_cookies)
                BrowserDataClearTarget.HISTORY_AND_FORM_DATA -> activity.getString(R.string.bootstrap_settings_clear_browser_data_target_history_form)
            }
        }.toTypedArray()
        val checkedItems = BooleanArray(targets.size) { index -> targets[index].selectedByDefault }

        fun selectedMask(): Int {
            return BrowserDataClearOptions.maskOf(
                targets.filterIndexed { index, _ -> checkedItems[index] }
            )
        }

        val dialogTitle = buildString {
            append(activity.getString(R.string.bootstrap_settings_clear_browser_data_confirm_title))
            append('\n')
            append(activity.getString(R.string.bootstrap_settings_clear_browser_data_confirm_message))
        }
        var positiveButton: android.widget.Button? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(dialogTitle)
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
                positiveButton?.isEnabled = selectedMask() != 0
            }
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_clear_browser_data_confirm_action, null)
            .create()

        // 浏览器清理默认只勾 JS/CSS 缓存；确认时传递真实选择范围，主界面 WebView host 再按范围清理。
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = selectedMask() != 0
            positiveButton.setOnClickListener {
                val mask = selectedMask()
                if (mask == 0) {
                    showMessage(activity.getString(R.string.bootstrap_settings_clear_browser_data_select_required))
                    positiveButton.isEnabled = false
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(mask)
            }
        }
        dialog.show()
    }

    private fun setupTabs() {
        if (tabLayout.tabCount == 0) {
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_data))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_extensions))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_logs))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_terminal))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_settings))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchTab(SettingsTab.fromTabPosition(tab.position))
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                switchTab(SettingsTab.fromTabPosition(tab.position))
            }
        })

        syncPrimaryNavigationSelection()
        switchTab(selectedTab)
        compactTabLayout()
    }

    private fun setupAboutEntry() {
        // 关于入口从 tab strip 移到标题右侧后，仍然复用原 about 面板，
        // 这样版本信息、更新状态和 GitHub 跳转逻辑都继续收敛在同一块 UI 上。
        toolbarAboutEntryView.setOnClickListener {
            if (busy) {
                return@setOnClickListener
            }
            switchTab(SettingsTab.ABOUT)
        }
        renderAboutEntryState()
    }

    private fun switchTab(tab: SettingsTab) {
        if (busy && tab != selectedTab) {
            syncPrimaryNavigationSelection()
            return
        }

        selectedTab = tab
        val isLogsTab = tab == SettingsTab.LOGS
        val isTerminalTab = tab == SettingsTab.TERMINAL
        scrollView.isVisible = !isLogsTab && !isTerminalTab
        dataPanelView.isVisible = tab == SettingsTab.DATA
        extensionsPanelView.isVisible = tab == SettingsTab.EXTENSIONS
        logsPanelView.isVisible = isLogsTab
        terminalPanelView.isVisible = isTerminalTab
        settingsPanelView.isVisible = tab == SettingsTab.SETTINGS
        aboutPanelView.isVisible = tab == SettingsTab.ABOUT
        searchLayout.isVisible = tab == SettingsTab.SETTINGS
        quickActionsButton.isVisible = tab == SettingsTab.SETTINGS
        // 保存按钮必须常驻在“酒馆设置”页签底部，不再跟随设置内容滚动。
        bottomActionBarView.isVisible = tab == SettingsTab.SETTINGS
        applyBottomInsets()
        syncPrimaryNavigationSelection()
        renderAboutEntryState()
        onTabChanged(tab)
        if (!isLogsTab && !isTerminalTab) {
            scrollView.post {
                scrollView.scrollTo(0, 0)
            }
        } else if (isLogsTab) {
            logsScrollView.post {
                logsScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun syncPrimaryNavigationSelection() {
        val tabPosition = selectedTab.tabPosition
        if (tabPosition == null) {
            tabLayout.selectTab(null)
            return
        }

        if (tabLayout.selectedTabPosition != tabPosition) {
            tabLayout.selectTab(tabLayout.getTabAt(tabPosition))
        }
    }

    private fun renderAboutEntryState() {
        val isAboutSelected = selectedTab == SettingsTab.ABOUT
        toolbarAboutEntryView.isSelected = isAboutSelected
        toolbarAboutEntryView.alpha = if (isAboutSelected) 1f else 0.78f
    }

    private fun applyWindowInsets() {
        val topShellTopPadding = topShellView.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            latestSystemBarsBottomInset = systemBars.bottom
            latestImeBottomInset = imeInsets.bottom
            latestImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            topShellView.updatePadding(top = topShellTopPadding + systemBars.top)
            applyBottomInsets()

            if (latestImeVisible) {
                scrollView.doOnNextLayout {
                    ensureFocusedViewVisible()
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun applyBottomInsets() {
        val settingsActionBarVisible = bottomActionBarView.isVisible
        // 设置页底部操作条可见时，由操作条自己承接底部 inset，避免滚动区再把按钮一起卷走。
        scrollView.updatePadding(
            bottom = scrollBottomBasePadding + when {
                latestImeVisible -> dimen(R.dimen.sillydroid_scroll_focus_spacing_top)
                settingsActionBarVisible -> 0
                else -> latestSystemBarsBottomInset
            }
        )
        logsPanelView.updatePadding(
            bottom = logsPanelBottomBasePadding + if (latestImeVisible) latestImeBottomInset else latestSystemBarsBottomInset
        )
        terminalPanelView.updatePadding(
            bottom = terminalPanelBottomBasePadding + if (latestImeVisible) latestImeBottomInset else latestSystemBarsBottomInset
        )
        bottomActionBarView.updatePadding(
            bottom = bottomActionBarBottomBasePadding + if (latestImeVisible) latestImeBottomInset else latestSystemBarsBottomInset
        )
    }

    private fun ensureFocusedViewVisible() {
        val focusedView = activity.currentFocus ?: return
        if (!focusedView.isAttachedToWindow || !isDescendantOfScrollView(focusedView)) {
            return
        }

        val targetRect = Rect()
        focusedView.getDrawingRect(targetRect)
        scrollView.offsetDescendantRectToMyCoords(focusedView, targetRect)

        val topSpacing = dimen(R.dimen.sillydroid_scroll_focus_spacing_top)
        val bottomSpacing = dimen(R.dimen.sillydroid_scroll_focus_spacing_bottom)
        val viewportTop = scrollView.scrollY
        val viewportBottom = scrollView.scrollY + scrollView.height - scrollView.paddingBottom

        val desiredScrollY = when {
            targetRect.bottom + bottomSpacing > viewportBottom -> {
                targetRect.bottom + bottomSpacing - (scrollView.height - scrollView.paddingBottom)
            }

            targetRect.top - topSpacing < viewportTop -> {
                targetRect.top - topSpacing
            }

            else -> scrollView.scrollY
        }.coerceAtLeast(0)

        if (desiredScrollY != scrollView.scrollY) {
            scrollView.smoothScrollTo(0, desiredScrollY)
        }
    }

    private fun isDescendantOfScrollView(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === scrollView) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun compactTabLayout() {
        tabLayout.post {
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@post
            // 页签外壳和单项尺寸统一走 token，避免 XML 与运行时二次压缩出现两套间距标准。
            val tabLayoutHeight = dimen(R.dimen.sillydroid_tab_strip_height)
            val tabItemHeight = dimen(R.dimen.sillydroid_tab_item_height)
            val tabHorizontalPadding = dimen(R.dimen.sillydroid_tab_horizontal_padding)

            tabLayout.minimumHeight = 0
            tabLayout.setPadding(0, 0, 0, 0)
            tabLayout.layoutParams = tabLayout.layoutParams.apply {
                height = tabLayoutHeight
            }

            tabStrip.minimumHeight = 0
            tabStrip.setPadding(0, 0, 0, 0)
            tabStrip.layoutParams = tabStrip.layoutParams.apply {
                height = tabLayoutHeight
            }

            for (index in 0 until tabStrip.childCount) {
                val tabView = tabStrip.getChildAt(index)
                tabView.minimumHeight = 0
                tabView.setPadding(tabHorizontalPadding, 0, tabHorizontalPadding, 0)

                val layoutParams = tabView.layoutParams
                if (layoutParams.height != tabItemHeight) {
                    layoutParams.height = tabItemHeight
                    tabView.layoutParams = layoutParams
                }
            }

            tabLayout.requestLayout()
            setTabNavigationEnabled(!busy)
        }
    }

    private fun setTabNavigationEnabled(enabled: Boolean) {
        tabLayout.isEnabled = enabled
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until tabStrip.childCount) {
            tabStrip.getChildAt(index).isEnabled = enabled
        }
    }

    private fun applyRoundedBannerStyle(backgroundColorAttr: Int, textColorAttr: Int) {
        // 启动设置页已经统一成圆角面板，这里不能再直接 setBackgroundColor 覆盖成矩形色块。
        warningView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = activity.resources.getDimension(R.dimen.sillydroid_nested_card_radius)
            setColor(resolveThemeColor(backgroundColorAttr))
            setStroke(dp(1).coerceAtLeast(1), resolveThemeColor(MaterialR.attr.colorOutlineVariant))
        }
        warningView.setTextColor(resolveThemeColor(textColorAttr))
    }

    private fun resolveThemeColor(attrRes: Int, fallback: Int = Color.TRANSPARENT): Int {
        return MaterialColors.getColor(activity, attrRes, fallback)
    }

    private fun syncSaveStartButtonState() {
        // 保存并启动现在只在“设置”页签内承担提交动作；没有脏数据时保持禁用，避免误触。
        saveStartButton.isEnabled = !busy && hasUnsavedChanges
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
