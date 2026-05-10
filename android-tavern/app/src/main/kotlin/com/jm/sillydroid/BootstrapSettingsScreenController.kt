package com.jm.sillydroid

import android.graphics.Rect
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.R as MaterialR

internal class BootstrapSettingsScreenController(
    private val activity: AppCompatActivity,
    private val rootView: android.view.View,
    private val topShellView: android.view.View,
    private val scrollView: NestedScrollView,
    private val actionBarView: android.view.View,
    private val tabLayout: TabLayout,
    private val dataPanelView: android.view.View,
    private val extensionsPanelView: android.view.View,
    private val logsPanelView: android.view.View,
    private val logsScrollView: NestedScrollView,
    private val settingsPanelView: android.view.View,
    private val aboutPanelView: android.view.View,
    private val configPathView: TextView,
    private val warningView: TextView,
    private val loadingIndicator: LinearProgressIndicator,
    private val searchLayout: TextInputLayout,
    private val floatingLogsSwitch: MaterialSwitch,
    private val restoreDefaultsButton: ImageButton,
    private val importButton: MaterialButton,
    private val exportButton: MaterialButton,
    private val clearDataButton: MaterialButton,
    private val saveStartButton: MaterialButton,
    private val onTabChanged: (Int) -> Unit = {}
) {
    private var selectedTabIndex = 0
    private var bannerIsError = false

    fun initialize() {
        setupTabs()
        applyWindowInsets()
    }

    fun setConfigPath(filePath: String) {
        configPathView.text = filePath
    }

    fun setBusy(busy: Boolean) {
        loadingIndicator.isVisible = busy
        searchLayout.isEnabled = !busy
        floatingLogsSwitch.isEnabled = !busy
        restoreDefaultsButton.isEnabled = !busy
        importButton.isEnabled = !busy
        exportButton.isEnabled = !busy
        clearDataButton.isEnabled = !busy
        saveStartButton.isEnabled = !busy
    }

    fun updateDirtyState(hasUnsavedChanges: Boolean) {
        saveStartButton.text = if (hasUnsavedChanges) {
            activity.getString(R.string.bootstrap_settings_save_start_dirty)
        } else {
            activity.getString(R.string.bootstrap_settings_save_start)
        }
    }

    fun focusValidationTab(isQuickField: Boolean) {
        tabLayout.getTabAt(if (isQuickField) 0 else 3)?.select()
    }

    fun showBanner(message: String?, isError: Boolean = false) {
        bannerIsError = isError
        warningView.isVisible = !message.isNullOrBlank()
        warningView.text = message.orEmpty()
        if (message.isNullOrBlank()) {
            return
        }

        if (isError) {
            warningView.setBackgroundColor(resolveThemeColor(MaterialR.attr.colorErrorContainer))
            warningView.setTextColor(resolveThemeColor(MaterialR.attr.colorOnErrorContainer))
        } else {
            warningView.setBackgroundColor(resolveThemeColor(MaterialR.attr.colorSecondaryContainer))
            warningView.setTextColor(resolveThemeColor(MaterialR.attr.colorOnSecondaryContainer))
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
        val (titleRes, message) = when (preview.archiveKind) {
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
                R.string.bootstrap_settings_import_confirm_title_host to activity.getString(
                    R.string.bootstrap_settings_import_confirm_message_host
                )
            }
        }

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

    private fun setupTabs() {
        if (tabLayout.tabCount == 0) {
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_data))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_extensions))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_logs))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_settings))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.bootstrap_settings_tab_about))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                switchTab(tab.position)
            }
        })

        tabLayout.getTabAt(selectedTabIndex)?.select()
        switchTab(selectedTabIndex)
        compactTabLayout()
    }

    private fun switchTab(index: Int) {
        selectedTabIndex = index
        val isLogsTab = index == 2
        scrollView.isVisible = !isLogsTab
        dataPanelView.isVisible = index == 0
        extensionsPanelView.isVisible = index == 1
        logsPanelView.isVisible = isLogsTab
        settingsPanelView.isVisible = index == 3
        aboutPanelView.isVisible = index == 4
        searchLayout.isVisible = index == 3
        onTabChanged(index)
        if (!isLogsTab) {
            scrollView.post {
                scrollView.scrollTo(0, 0)
            }
        } else {
            logsScrollView.post {
                logsScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun applyWindowInsets() {
        val topShellTopPadding = topShellView.paddingTop
        val scrollBottomPadding = scrollView.paddingBottom
        val actionBarBottomPadding = actionBarView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            topShellView.updatePadding(top = topShellTopPadding + systemBars.top)
            scrollView.updatePadding(bottom = scrollBottomPadding + if (imeVisible) imeInsets.bottom + dp(12) else 0)
            actionBarView.updatePadding(bottom = actionBarBottomPadding + if (imeVisible) 0 else systemBars.bottom)
            actionBarView.isVisible = !imeVisible

            if (imeVisible) {
                scrollView.doOnNextLayout {
                    ensureFocusedViewVisible()
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun ensureFocusedViewVisible() {
        val focusedView = activity.currentFocus ?: return
        if (!focusedView.isAttachedToWindow || !isDescendantOfScrollView(focusedView)) {
            return
        }

        val targetRect = Rect()
        focusedView.getDrawingRect(targetRect)
        scrollView.offsetDescendantRectToMyCoords(focusedView, targetRect)

        val topSpacing = dp(12)
        val bottomSpacing = dp(24)
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
            val tabLayoutHeight = dp(40)
            val tabItemHeight = dp(36)

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
                tabView.setPadding(dp(10), 0, dp(10), 0)

                val layoutParams = tabView.layoutParams
                if (layoutParams.height != tabItemHeight) {
                    layoutParams.height = tabItemHeight
                    tabView.layoutParams = layoutParams
                }
            }

            tabLayout.requestLayout()
        }
    }

    private fun resolveThemeColor(attrRes: Int, fallback: Int = Color.TRANSPARENT): Int {
        return MaterialColors.getColor(activity, attrRes, fallback)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
