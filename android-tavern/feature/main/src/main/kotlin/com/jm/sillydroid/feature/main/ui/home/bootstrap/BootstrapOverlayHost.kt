package com.jm.sillydroid.feature.main.ui.home.bootstrap

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.SettingsNavigationContract
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel
import com.jm.sillydroid.feature.main.ui.home.floatinglogs.FloatingLogsHost
import com.jm.sillydroid.feature.main.ui.home.webview.TavernWebViewHost
import com.jm.sillydroid.ui.update.AppUpdateCoordinator
import kotlinx.coroutines.launch

/**
 * 把 bootstrap 启动 overlay（状态文案 / 重试 / 设置入口 / 更新按钮 / 进度）+ AppUpdateCoordinator
 * + 设置页 ActivityResult launcher + 启动事件订阅 全部封装。
 *
 * MainActivity 只持有一个实例，并在 onCreate 调用 [installAppUpdateCoordinator] / [bindButtons]
 * / [observe]。设置页入口、重试、render 都通过本 host 暴露。
 *
 * 注意 [openBootstrapSettings]：进入设置页本身**不**应该停掉本地 Tavern 服务；只有真正需要时（如保存
 * 修改后的配置、导入数据）由 settings 内部的 coordinator 通过 BootstrapController.stopForSettingsAndAwait(...)
 * 自行 pause。这样：
 *   - 用户进设置只是看一眼/改主题等无害动作 → 服务不会被打断；
 *   - 真正会改变 server 行为的操作 → 仍走原 pause/restart 流程。
 */
class BootstrapOverlayHost(
    private val activity: AppCompatActivity,
    private val homeViewModel: HomeViewModel,
    private val processManager: BootstrapController,
    private val appGraph: SillyDroidAppGraph,
    private val webViewHost: TavernWebViewHost,
    private val floatingLogsHost: FloatingLogsHost,
    private val onMaybePromptDefaultExtensionsAfterBootstrapReady: () -> Unit,
) {
    private val overlay: View = activity.findViewById(R.id.bootstrapOverlay)
    private val status: TextView = activity.findViewById(R.id.bootstrapStatus)
    private val retryButton: Button = activity.findViewById(R.id.bootstrapRetry)
    private val updateButtonContainer: View = activity.findViewById(R.id.bootstrapUpdateButtonContainer)
    private val updateButton: ImageButton = activity.findViewById(R.id.bootstrapUpdateButton)
    private val updateBadge: View = activity.findViewById(R.id.bootstrapUpdateBadge)
    private val settingsButton: ImageButton = activity.findViewById(R.id.bootstrapSettingsButton)
    private val progress: ProgressBar = activity.findViewById(R.id.bootstrapProgress)
    private val progressLabel: TextView = activity.findViewById(R.id.bootstrapProgressLabel)

    private lateinit var appUpdateCoordinator: AppUpdateCoordinator

    private val renderer by lazy {
        BootstrapOverlayRenderer(
            views = BootstrapOverlayViews(
                overlay = overlay,
                status = status,
                retryButton = retryButton,
                settingsButton = settingsButton,
                progress = progress,
                progressLabel = progressLabel,
                webViewRefreshLayout = webViewHost.webViewRefreshLayout,
                webView = { webViewHost.webView }
            ),
            text = BootstrapOverlayText(
                pausedMessage = { activity.getString(R.string.bootstrap_paused_message) },
                pausedDetails = { activity.getString(R.string.bootstrap_paused_details) },
                resumeLabel = { activity.getString(R.string.bootstrap_resume) },
                retryLabel = { activity.getString(R.string.bootstrap_retry) },
                progressLabel = { percent -> activity.getString(R.string.bootstrap_progress_label, percent) },
                progressIndeterminate = { activity.getString(R.string.bootstrap_progress_indeterminate) },
                startupElapsed = { seconds -> activity.getString(R.string.bootstrap_startup_elapsed, seconds) },
                tavernLogTail = { line -> activity.getString(R.string.bootstrap_startup_tavern_log_tail, line) }
            ),
            syncSettingsEntryState = ::syncBootstrapSettingsEntryState,
            showWebView = { url -> webViewHost.showWebView(url) },
            updateWebViewRefreshLayoutEnabled = { webViewHost.updateRefreshLayoutEnabled() },
            setPullGestureRefreshing = { refreshing -> homeViewModel.isPullGestureRefreshing = refreshing },
            onReadyMonitoring = onMaybePromptDefaultExtensionsAfterBootstrapReady
        )
    }

    private val settingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        homeViewModel.isOpeningBootstrapSettings = false
        if (result.resultCode == Activity.RESULT_OK && shouldStartBootstrapFromSettingsResult(result.data)) {
            homeViewModel.resetForBootstrapRestart()
            overlay.isVisible = true
            webViewHost.hideForBootstrapRestart()
            startBootstrap(true)
            return@registerForActivityResult
        }

        val currentSnapshot = processManager.currentSnapshot()
        render(currentSnapshot)
        if (result.resultCode == Activity.RESULT_OK && shouldReloadTavernUiFromSettingsResult(result.data)) {
            webViewHost.reloadTavernUiIfPossible(currentSnapshot)
        }
    }

    fun installAppUpdateCoordinator() {
        appUpdateCoordinator = AppUpdateCoordinator(
            activity = activity,
            appUpdateRepository = appGraph.appUpdateRepository,
            runtimeMetadataRepository = appGraph.runtimeMetadataRepository,
            buildConfig = appGraph.appUpdateBuildConfig,
            dispatchers = appGraph.dispatchers,
            overlayUi = AppUpdateCoordinator.OverlayUi(
                container = updateButtonContainer,
                button = updateButton,
                badgeView = updateBadge
            )
        )
        appUpdateCoordinator.initialize()
    }

    fun bindButtons() {
        retryButton.setOnClickListener { startBootstrap(true) }
        settingsButton.setOnClickListener { openBootstrapSettings() }
    }

    fun render(snapshot: BootstrapSessionSnapshot) {
        floatingLogsHost.renderSessionSummary(snapshot)
        renderer.render(snapshot)
    }

    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.bootstrapSnapshot.collect { snapshot ->
                    render(snapshot)
                }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                processManager.events.collect { event ->
                    when (event) {
                        is BootstrapEvent.AutoRestartScheduled,
                        is BootstrapEvent.SettingsPauseRequested -> webViewHost.resetRefreshOnBootstrapEvent()
                        else -> Unit
                    }
                }
            }
        }
    }

    fun startBootstrap(forceRestart: Boolean) {
        processManager.start(forceRestart)
    }

    fun canOpenBootstrapSettings(snapshot: BootstrapSessionSnapshot): Boolean {
        return snapshot.derivedUiFlags.canOpenSettings && !homeViewModel.isOpeningBootstrapSettings
    }

    fun syncBootstrapSettingsEntryState(snapshot: BootstrapSessionSnapshot) {
        val settingsEnabled = canOpenBootstrapSettings(snapshot)
        settingsButton.isEnabled = settingsEnabled
        settingsButton.alpha = if (settingsEnabled) 1f else 0.35f
        floatingLogsHost.syncSettingsEntryState(snapshot)
    }

    fun openBootstrapSettings(openDefaultExtensionsInstaller: Boolean = false) {
        if (homeViewModel.isOpeningBootstrapSettings) {
            return
        }

        val snapshot = processManager.currentSnapshot()
        if (!canOpenBootstrapSettings(snapshot)) {
            syncBootstrapSettingsEntryState(snapshot)
            return
        }

        homeViewModel.isOpeningBootstrapSettings = true
        syncBootstrapSettingsEntryState(snapshot)
        val settingsIntent = appGraph.createSettingsIntent(
            activity = activity,
            openExtensionsTab = openDefaultExtensionsInstaller,
            openDefaultExtensionsInstaller = openDefaultExtensionsInstaller
        )
        if (activity.isFinishing || activity.isDestroyed) {
            homeViewModel.isOpeningBootstrapSettings = false
            syncBootstrapSettingsEntryState(processManager.currentSnapshot())
            return
        }
        settingsLauncher.launch(settingsIntent)
    }

    private fun shouldStartBootstrapFromSettingsResult(data: Intent?): Boolean {
        return data?.getBooleanExtra(SettingsNavigationContract.resultShouldStartKey, false) == true
    }

    private fun shouldReloadTavernUiFromSettingsResult(data: Intent?): Boolean {
        return data?.getBooleanExtra(SettingsNavigationContract.resultShouldReloadTavernUiKey, false) == true
    }
}
