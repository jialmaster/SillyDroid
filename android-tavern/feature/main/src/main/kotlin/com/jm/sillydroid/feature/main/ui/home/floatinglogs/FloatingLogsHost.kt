package com.jm.sillydroid.feature.main.ui.home.floatinglogs

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.ui.scroll.DraggableScrollThumbController
import com.jm.sillydroid.domain.logs.HostLogRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.main.R

/**
 * 把悬浮日志相关的视图、布局控制器、内容控制器全部封装在这里，
 * MainActivity 只持有一个 host 实例和必要回调，避免十几个 lateinit view 字段散落在 Activity 里。
 */
class FloatingLogsHost(
    private val activity: AppCompatActivity,
    private val contentRoot: View,
    private val dispatchers: DispatcherProvider,
    private val preferences: HostPreferencesRepository,
    private val logRepository: HostLogRepository,
    private val currentSnapshot: () -> BootstrapSessionSnapshot,
    private val canOpenSettings: (BootstrapSessionSnapshot) -> Boolean,
    private val openSettings: () -> Unit,
    private val reloadTavernWebView: () -> Boolean
) {
    private val bubble: ImageButton = activity.findViewById(R.id.floatingLogsBubble)
    private val panel: View = activity.findViewById(R.id.floatingLogsPanel)
    private val meta: TextView = activity.findViewById(R.id.floatingLogsMeta)
    private val empty: TextView = activity.findViewById(R.id.floatingLogsEmpty)
    private val content: TextView = activity.findViewById(R.id.floatingLogsContent)
    private val scroll: NestedScrollView = activity.findViewById(R.id.floatingLogsScroll)
    private val scrollbarThumb: View = activity.findViewById(R.id.floatingLogsScrollbarThumb)
    private val selectButton: MaterialButton = activity.findViewById(R.id.floatingLogsSelectButton)
    private val intervalButton: MaterialButton = activity.findViewById(R.id.floatingLogsIntervalButton)
    private val closeButton: ImageButton = activity.findViewById(R.id.floatingLogsCloseButton)
    private val reloadWebViewButton: MaterialButton = activity.findViewById(R.id.floatingLogsReloadWebViewButton)
    private val downloadButton: MaterialButton = activity.findViewById(R.id.floatingLogsDownloadButton)
    private val clearButton: MaterialButton = activity.findViewById(R.id.floatingLogsClearButton)
    private val openSettingsButton: MaterialButton = activity.findViewById(R.id.floatingLogsOpenSettingsButton)
    private val scrollToBottomButton: ImageButton = activity.findViewById(R.id.floatingLogsScrollToBottomButton)

    private val layoutController: FloatingLogsLayoutController by lazy {
        FloatingLogsLayoutController(
            contentRoot = contentRoot,
            bubble = bubble,
            panel = panel,
            savedPosition = { preferences.floatingLogBubblePosition },
            savePosition = { position -> preferences.floatingLogBubblePosition = position },
            panelWidthPx = { activity.resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_width) },
            panelHeightPx = { activity.resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_height) },
            panelHorizontalMarginPx = { activity.resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_horizontal_margin) },
            panelVerticalMarginPx = { activity.resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_panel_vertical_margin) },
            panelGapPx = { 12f * activity.resources.displayMetrics.density }
        )
    }

    private val controller: FloatingLogsController by lazy {
        FloatingLogsController(
            activity = activity,
            scope = activity.lifecycleScope,
            dispatchers = dispatchers,
            preferences = preferences,
            logRepository = logRepository,
            layoutController = layoutController,
            views = FloatingLogsViews(
                contentRoot = contentRoot,
                bubble = bubble,
                panel = panel,
                meta = meta,
                empty = empty,
                content = content,
                scroll = scroll,
                scrollbarThumb = scrollbarThumb,
                selectButton = selectButton,
                intervalButton = intervalButton,
                closeButton = closeButton,
                reloadWebViewButton = reloadWebViewButton,
                downloadButton = downloadButton,
                clearButton = clearButton,
                openSettingsButton = openSettingsButton,
                scrollToBottomButton = scrollToBottomButton
            ),
            text = FloatingLogsText(
                autoSelectLabel = { activity.getString(R.string.floating_logs_panel_auto_select_label) },
                selectDialogTitle = { activity.getString(R.string.floating_logs_panel_select_label) },
                intervalDialogTitle = { activity.getString(R.string.floating_logs_panel_interval_label) },
                realtimeIntervalLabel = { activity.getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_realtime) },
                oneSecondIntervalLabel = { activity.getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_one_second) },
                threeSecondsIntervalLabel = { activity.getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_three_seconds) },
                fiveSecondsIntervalLabel = { activity.getString(R.string.bootstrap_settings_host_floating_logs_refresh_interval_five_seconds) },
                logsMeta = { displayName, updatedAt -> activity.getString(R.string.bootstrap_settings_logs_meta, displayName, updatedAt) },
                emptyContent = { activity.getString(R.string.bootstrap_settings_logs_empty_content) },
                downloadSuccess = { zipFileName, zipPath -> activity.getString(R.string.floating_logs_download_success, zipFileName, zipPath) },
                downloadFailed = { activity.getString(R.string.floating_logs_download_failed) },
                clearConfirmTitle = { activity.getString(R.string.bootstrap_settings_logs_clear_confirm_title) },
                clearConfirmMessage = { activity.getString(R.string.bootstrap_settings_logs_clear_confirm_message) },
                clearConfirmPositiveLabel = { activity.getString(R.string.bootstrap_settings_logs_clear) },
                clearSuccess = { activity.getString(R.string.bootstrap_settings_logs_clear_success) },
                clearFailed = { activity.getString(R.string.bootstrap_settings_logs_clear_failed) }
            ),
            scrollThumbController = DraggableScrollThumbController(
                scrollView = scroll,
                thumbView = scrollbarThumb,
                minThumbHeightPx = activity.resources.getDimensionPixelSize(R.dimen.sillydroid_floating_logs_scrollbar_min_height)
            ),
            currentSnapshot = currentSnapshot,
            canOpenSettings = canOpenSettings,
            openSettings = openSettings,
            reloadTavernWebView = reloadTavernWebView
        )
    }

    fun configure() = controller.configure()
    fun refreshVisibility() = controller.refreshVisibility()
    fun onContentBoundsChanged() = controller.onContentBoundsChanged()
    fun renderSessionSummary(snapshot: BootstrapSessionSnapshot) = controller.renderSessionSummary(snapshot)
    fun syncSettingsEntryState(snapshot: BootstrapSessionSnapshot) = controller.syncSettingsEntryState(snapshot)
    fun showBubble() = controller.showBubble()
    fun setBubbleEnabled(enabled: Boolean) = controller.setBubbleEnabled(enabled)
}
