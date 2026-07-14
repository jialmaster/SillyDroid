package com.jm.sillydroid.feature.settings.ui.screen

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.model.settings.NodeHeapLimitOptions
import com.jm.sillydroid.core.model.settings.NodeNewSpaceLimitOptions
import com.jm.sillydroid.core.model.settings.TavernServerLaunchMode
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.SettingsActivityUiState
import com.jm.sillydroid.feature.settings.viewmodel.SettingsActivityViewModel
import kotlinx.coroutines.launch

/**
 * 绑定设置页开关与状态流，并把用户操作转成明确的 ViewModel 或宿主动作。
 *
 * 允许：触发悬浮浏览器授权请求和关闭动作；不允许自行写入未经权限复核的启用状态。
 */
class SettingsActivityStateController(
    private val activity: AppCompatActivity,
    private val viewModel: SettingsActivityViewModel,
    private val floatingBrowserSwitch: MaterialSwitch,
    private val floatingLogsSwitch: MaterialSwitch,
    private val backgroundOnlyModeSwitch: MaterialSwitch,
    private val backgroundHealthCheckSwitch: MaterialSwitch,
    private val launchModeRow: View,
    private val launchModeValueView: TextView,
    private val tavernRuntimePatchRow: View,
    private val tavernRuntimePatchConfigureButton: MaterialButton,
    private val tavernRuntimePatchSwitch: MaterialSwitch,
    private val pullRefreshSwitch: MaterialSwitch,
    private val browserEngineRow: View,
    private val browserEngineValueView: TextView,
    private val nodeMemoryLimitRow: View,
    private val nodeMemoryLimitValueView: TextView,
    private val nodeNewSpaceLimitRow: View,
    private val nodeNewSpaceLimitValueView: TextView,
    private val hostDisplayModeRow: View,
    private val hostDisplayModeValueView: TextView,
    private val debugDiagnosticsSwitch: MaterialSwitch,
    private val unrestrictedFileImportSelectionSwitch: MaterialSwitch,
    private val onFloatingBrowserEnableRequested: () -> Unit,
    private val onFloatingBrowserDisabled: () -> Unit,
    private val showRuntimePatchBottomSheet: (SettingsActivityUiState) -> Unit,
    private val onServiceRestartRequired: () -> Unit,
    private val applyHostDisplayMode: (HostDisplayMode) -> Unit,
    private val renderResultFlags: (SettingsActivityUiState) -> Unit
) {
    /** 初始化控件监听并订阅状态流，保证开关始终反映持久化真值。 */
    fun initialize() {
        val initialState = viewModel.uiState.value
        floatingBrowserSwitch.isChecked = initialState.floatingBrowserEnabled
        floatingBrowserSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 开启动作必须先离开 App 进入系统 overlay 授权页；只有授权回调能写入真实启用状态。
                onFloatingBrowserEnableRequested()
            } else {
                onFloatingBrowserDisabled()
            }
        }
        floatingLogsSwitch.isChecked = initialState.floatingLogsEnabled
        floatingLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFloatingLogsEnabled(isChecked)
        }
        backgroundOnlyModeSwitch.isChecked = initialState.backgroundOnlyModeEnabled
        backgroundOnlyModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBackgroundOnlyModeEnabled(isChecked)
        }
        backgroundHealthCheckSwitch.isChecked = initialState.backgroundHealthCheckEnabled
        backgroundHealthCheckSwitch.setOnCheckedChangeListener { _, isChecked ->
            val changed = viewModel.setBackgroundHealthCheckEnabled(isChecked)
            if (changed) {
                onServiceRestartRequired()
                Toast.makeText(
                    activity,
                    R.string.bootstrap_settings_host_service_restart_hint,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        launchModeValueView.text = resolveLaunchModeLabel(initialState.tavernServerLaunchMode)
        launchModeRow.setOnClickListener {
            showLaunchModeDialog(viewModel.uiState.value.tavernServerLaunchMode)
        }
        tavernRuntimePatchRow.setOnClickListener {
            showRuntimePatchBottomSheet(viewModel.uiState.value)
        }
        tavernRuntimePatchConfigureButton.setOnClickListener {
            showRuntimePatchBottomSheet(viewModel.uiState.value)
        }
        tavernRuntimePatchSwitch.isChecked = initialState.tavernRuntimePatchEnabled
        tavernRuntimePatchSwitch.setOnCheckedChangeListener { _, isChecked ->
            val changed = viewModel.setTavernRuntimePatchEnabled(isChecked)
            if (changed) {
                onServiceRestartRequired()
                Toast.makeText(
                    activity,
                    R.string.bootstrap_settings_host_runtime_patch_restart_hint,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        pullRefreshSwitch.isChecked = initialState.pullRefreshEnabled
        pullRefreshSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPullRefreshEnabled(isChecked)
        }
        browserEngineValueView.text = resolveBrowserEngineLabel(initialState.browserEngine)
        browserEngineRow.setOnClickListener {
            showBrowserEngineDialog(viewModel.uiState.value.browserEngine)
        }
        nodeMemoryLimitValueView.text = resolveNodeMemoryLimitLabel(initialState.nodeMaxOldSpaceMb)
        nodeMemoryLimitRow.setOnClickListener {
            showNodeMemoryLimitDialog(viewModel.uiState.value.nodeMaxOldSpaceMb)
        }
        nodeNewSpaceLimitValueView.text = resolveNodeNewSpaceLimitLabel(initialState.nodeMaxSemiSpaceMb)
        nodeNewSpaceLimitRow.setOnClickListener {
            showNodeNewSpaceLimitDialog(viewModel.uiState.value.nodeMaxSemiSpaceMb)
        }
        hostDisplayModeValueView.text = resolveHostDisplayModeLabel(initialState.hostDisplayMode)
        hostDisplayModeRow.setOnClickListener {
            showHostDisplayModeDialog(viewModel.uiState.value.hostDisplayMode)
        }
        debugDiagnosticsSwitch.isChecked = initialState.debugDiagnosticsEnabled
        debugDiagnosticsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDebugDiagnosticsEnabled(isChecked)
        }
        unrestrictedFileImportSelectionSwitch.isChecked = initialState.unrestrictedFileImportSelectionEnabled
        unrestrictedFileImportSelectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setUnrestrictedFileImportSelectionEnabled(isChecked)
        }
        applyHostDisplayMode(initialState.hostDisplayMode)

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    /** 渲染设置状态；纯后台模式下禁用没有浏览器表面的悬浮入口。 */
    private fun render(state: SettingsActivityUiState) {
        if (floatingBrowserSwitch.isChecked != state.floatingBrowserEnabled) {
            floatingBrowserSwitch.isChecked = state.floatingBrowserEnabled
        }
        // 纯后台模式不创建浏览器表面，必须禁用悬浮浏览器入口而不是让用户保存无效配置。
        floatingBrowserSwitch.isEnabled = !state.backgroundOnlyModeEnabled
        if (floatingLogsSwitch.isChecked != state.floatingLogsEnabled) {
            floatingLogsSwitch.isChecked = state.floatingLogsEnabled
        }
        if (backgroundOnlyModeSwitch.isChecked != state.backgroundOnlyModeEnabled) {
            backgroundOnlyModeSwitch.isChecked = state.backgroundOnlyModeEnabled
        }
        if (backgroundHealthCheckSwitch.isChecked != state.backgroundHealthCheckEnabled) {
            backgroundHealthCheckSwitch.isChecked = state.backgroundHealthCheckEnabled
        }
        val launchModeLabel = resolveLaunchModeLabel(state.tavernServerLaunchMode)
        if (launchModeValueView.text?.toString() != launchModeLabel) {
            launchModeValueView.text = launchModeLabel
        }
        if (tavernRuntimePatchSwitch.isChecked != state.tavernRuntimePatchEnabled) {
            tavernRuntimePatchSwitch.isChecked = state.tavernRuntimePatchEnabled
        }
        if (pullRefreshSwitch.isChecked != state.pullRefreshEnabled) {
            pullRefreshSwitch.isChecked = state.pullRefreshEnabled
        }
        val browserEngineLabel = resolveBrowserEngineLabel(state.browserEngine)
        if (browserEngineValueView.text?.toString() != browserEngineLabel) {
            browserEngineValueView.text = browserEngineLabel
        }
        val nodeMemoryLimitLabel = resolveNodeMemoryLimitLabel(state.nodeMaxOldSpaceMb)
        if (nodeMemoryLimitValueView.text?.toString() != nodeMemoryLimitLabel) {
            nodeMemoryLimitValueView.text = nodeMemoryLimitLabel
        }
        val nodeNewSpaceLimitLabel = resolveNodeNewSpaceLimitLabel(state.nodeMaxSemiSpaceMb)
        if (nodeNewSpaceLimitValueView.text?.toString() != nodeNewSpaceLimitLabel) {
            nodeNewSpaceLimitValueView.text = nodeNewSpaceLimitLabel
        }
        val hostDisplayModeLabel = resolveHostDisplayModeLabel(state.hostDisplayMode)
        if (hostDisplayModeValueView.text?.toString() != hostDisplayModeLabel) {
            hostDisplayModeValueView.text = hostDisplayModeLabel
        }
        if (debugDiagnosticsSwitch.isChecked != state.debugDiagnosticsEnabled) {
            debugDiagnosticsSwitch.isChecked = state.debugDiagnosticsEnabled
        }
        if (unrestrictedFileImportSelectionSwitch.isChecked != state.unrestrictedFileImportSelectionEnabled) {
            unrestrictedFileImportSelectionSwitch.isChecked = state.unrestrictedFileImportSelectionEnabled
        }
        applyHostDisplayMode(state.hostDisplayMode)
        renderResultFlags(state)
    }

    private fun showHostDisplayModeDialog(currentMode: HostDisplayMode) {
        val modes = HostDisplayMode.entries
        val optionLabels = modes.map(::resolveHostDisplayModeLabel).toTypedArray()
        val checkedItem = modes.indexOf(currentMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_host_display_mode_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedItem) { dialog, which ->
                val selectedMode = modes[which]
                viewModel.setHostDisplayMode(selectedMode)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLaunchModeDialog(currentMode: TavernServerLaunchMode) {
        val modes = TavernServerLaunchMode.entries
        val optionLabels = modes.map(::resolveLaunchModeLabel).toTypedArray()
        val checkedItem = modes.indexOf(currentMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_host_launch_mode_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedItem) { dialog, which ->
                val selectedMode = modes[which]
                val changed = viewModel.setTavernServerLaunchMode(selectedMode)
                if (changed) {
                    onServiceRestartRequired()
                    Toast.makeText(
                        activity,
                        R.string.bootstrap_settings_host_launch_mode_restart_hint,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBrowserEngineDialog(currentEngine: BrowserEngine) {
        val engines = BrowserEngine.entries
        val optionLabels = engines.map(::resolveBrowserEngineLabel).toTypedArray()
        val checkedItem = engines.indexOf(currentEngine).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_host_browser_engine_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedItem) { dialog, which ->
                val selectedEngine = engines[which]
                viewModel.setBrowserEngine(selectedEngine)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNodeMemoryLimitDialog(currentValueMb: Int) {
        val options = NodeHeapLimitOptions.optionsMb
        val optionLabels = options.map(::resolveNodeMemoryLimitLabel).toTypedArray()
        val checkedItem = options.indexOf(NodeHeapLimitOptions.sanitize(currentValueMb)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_host_node_memory_limit_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedItem) { dialog, which ->
                val selectedValueMb = options[which]
                // setNodeMaxOldSpaceMb 返回是否真正变更；只在变更时提示重启生效，避免重复选同一档位也弹提示。
                val changed = viewModel.setNodeMaxOldSpaceMb(selectedValueMb)
                if (changed) {
                    onServiceRestartRequired()
                    Toast.makeText(
                        activity,
                        R.string.bootstrap_settings_host_node_memory_limit_restart_hint,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNodeNewSpaceLimitDialog(currentValueMb: Int) {
        val options = NodeNewSpaceLimitOptions.optionsMb
        val optionLabels = options.map(::resolveNodeNewSpaceLimitLabel).toTypedArray()
        val checkedItem = options.indexOf(NodeNewSpaceLimitOptions.sanitize(currentValueMb)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_host_node_new_space_limit_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedItem) { dialog, which ->
                val selectedValueMb = options[which]
                // 与老生代一致：只在真正变更时提示重启生效。
                val changed = viewModel.setNodeMaxSemiSpaceMb(selectedValueMb)
                if (changed) {
                    onServiceRestartRequired()
                    Toast.makeText(
                        activity,
                        R.string.bootstrap_settings_host_node_new_space_limit_restart_hint,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resolveBrowserEngineLabel(engine: BrowserEngine): String {
        return when (engine) {
            BrowserEngine.SYSTEM_WEBVIEW -> activity.getString(R.string.bootstrap_settings_host_browser_engine_system_webview)
            BrowserEngine.GECKOVIEW -> activity.getString(R.string.bootstrap_settings_host_browser_engine_geckoview)
        }
    }

    private fun resolveNodeMemoryLimitLabel(valueMb: Int): String {
        val sanitized = NodeHeapLimitOptions.sanitize(valueMb)
        return if (NodeHeapLimitOptions.isExplicit(sanitized)) {
            activity.getString(R.string.bootstrap_settings_host_node_memory_limit_megabytes, sanitized)
        } else {
            activity.getString(R.string.bootstrap_settings_host_node_memory_limit_automatic)
        }
    }

    private fun resolveNodeNewSpaceLimitLabel(valueMb: Int): String {
        val sanitized = NodeNewSpaceLimitOptions.sanitize(valueMb)
        return if (NodeNewSpaceLimitOptions.isExplicit(sanitized)) {
            activity.getString(R.string.bootstrap_settings_host_node_new_space_limit_megabytes, sanitized)
        } else {
            activity.getString(R.string.bootstrap_settings_host_node_new_space_limit_automatic)
        }
    }

    private fun resolveHostDisplayModeLabel(mode: HostDisplayMode): String {
        return when (mode) {
            HostDisplayMode.NORMAL -> activity.getString(R.string.bootstrap_settings_host_display_mode_normal)
            HostDisplayMode.STATUS_BAR_HIDDEN -> activity.getString(R.string.bootstrap_settings_host_display_mode_status_bar_hidden)
            HostDisplayMode.IMMERSIVE -> activity.getString(R.string.bootstrap_settings_host_display_mode_immersive)
        }
    }

    private fun resolveLaunchModeLabel(mode: TavernServerLaunchMode): String {
        return when (mode) {
            TavernServerLaunchMode.AUTO -> activity.getString(R.string.bootstrap_settings_host_launch_mode_auto)
            TavernServerLaunchMode.FAST -> activity.getString(R.string.bootstrap_settings_host_launch_mode_fast)
            TavernServerLaunchMode.FULL -> activity.getString(R.string.bootstrap_settings_host_launch_mode_full)
        }
    }
}
