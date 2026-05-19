package com.jm.sillydroid.feature.main.ui.home.floatinglogs

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jm.sillydroid.core.model.bootstrap.BootstrapLogKind
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.shouldPreferTavernServerLog
import com.jm.sillydroid.core.ui.logs.HostLogExportSelectionDialogText
import com.jm.sillydroid.core.ui.logs.showHostLogExportSelectionDialog
import com.jm.sillydroid.core.ui.scroll.DraggableScrollThumbController
import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogSnapshot
import com.jm.sillydroid.core.model.settings.FloatingLogRefreshIntervals
import com.jm.sillydroid.domain.logs.HostLogRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingLogsController(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val preferences: HostPreferencesRepository,
    private val logRepository: HostLogRepository,
    private val layoutController: FloatingLogsLayoutController,
    private val views: FloatingLogsViews,
    private val text: FloatingLogsText,
    private val scrollThumbController: DraggableScrollThumbController,
    private val currentSnapshot: () -> BootstrapSessionSnapshot,
    private val canOpenSettings: (BootstrapSessionSnapshot) -> Boolean,
    private val openSettings: () -> Unit,
    private val reloadTavernWebView: () -> Boolean
) : DefaultLifecycleObserver {
    private var refreshJob: Job? = null
    private var realtimeRenderJob: Job? = null
    private var realtimeRenderPending = false
    private var subscription: AutoCloseable? = null
    private var lastSnapshot: HostLogSnapshot? = null
    private var availableEntries: List<HostLogEntry> = emptyList()
    private var selectedLogFileName: String? = null
    private var lastPreferredLogKind: BootstrapLogKind? = null
    private var autoScrollEnabled = true
    private val bubbleTouchSlop by lazy { ViewConfiguration.get(activity).scaledTouchSlop }

    val isPanelVisible: Boolean
        get() = views.panel.isVisible

    fun configure() {
        val disableAutoScrollTouchListener = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                autoScrollEnabled = false
            }
            false
        }

        configureBubbleTouch()
        views.bubble.setOnClickListener {
            setPanelVisible(!views.panel.isVisible)
        }
        views.closeButton.setOnClickListener {
            setPanelVisible(false)
        }
        views.reloadWebViewButton.setOnClickListener {
            reloadTavernWebView()
        }
        views.scrollToBottomButton.setOnClickListener {
            autoScrollEnabled = true
            scrollToBottom()
            views.scrollToBottomButton.isVisible = false
        }
        views.panel.setOnTouchListener(disableAutoScrollTouchListener)
        views.scroll.setOnTouchListener(disableAutoScrollTouchListener)
        views.content.setOnTouchListener(disableAutoScrollTouchListener)
        views.scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (isScrolledToBottom()) {
                autoScrollEnabled = true
                views.scrollToBottomButton.isVisible = false
            } else {
                views.scrollToBottomButton.isVisible = true
            }
        }
        configureControlButtons()
        scrollThumbController.configure()
        updateControlLabels()
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (views.panel.isVisible) {
            startRefreshLoop()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        stopRefreshLoop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopRefreshLoop()
        scrollThumbController.close()
    }

    fun showBubble() {
        preferences.floatingLogBubbleEnabled = true
        refreshVisibility()
        revealBubble(animated = true)
    }

    fun setBubbleEnabled(enabled: Boolean) {
        preferences.floatingLogBubbleEnabled = enabled
        refreshVisibility()
        if (enabled) {
            revealBubble(animated = true)
        }
    }

    fun refreshVisibility() {
        syncSettingsEntryState(currentSnapshot())
        val enabled = preferences.floatingLogBubbleEnabled
        views.bubble.isVisible = enabled
        if (!enabled) {
            setPanelVisible(false)
            return
        }

        views.bubble.post {
            layoutController.restoreBubblePosition()
            if (views.panel.isVisible) {
                layoutController.repositionPanel()
            }
        }
    }

    fun setPanelVisible(visible: Boolean) {
        syncSettingsEntryState(currentSnapshot())
        val shouldShow = visible && preferences.floatingLogBubbleEnabled
        if (shouldShow) {
            revealBubble(animated = true) {
                views.panel.alpha = 0f
                views.panel.isVisible = true
                layoutController.repositionPanel {
                    views.panel.alpha = 1f
                    autoScrollEnabled = true
                    views.scrollToBottomButton.isVisible = false
                    scrollToBottom()
                    startRefreshLoop()
                }
            }
        } else {
            views.panel.alpha = 1f
            views.panel.isVisible = false
            stopRefreshLoop()
            layoutController.dockBubble(animated = true)
        }
    }

    fun revealBubble(animated: Boolean, onEnd: (() -> Unit)? = null) {
        layoutController.revealBubble(animated, onEnd)
    }

    fun onContentBoundsChanged() {
        if (!views.bubble.isVisible) {
            return
        }
        views.contentRoot.post {
            layoutController.restoreBubblePosition()
            if (views.panel.isVisible) {
                layoutController.repositionPanel()
            }
        }
    }

    fun renderSessionSummary(snapshot: BootstrapSessionSnapshot) {
        // 当 bootstrap 推进到不同阶段（例如 START_SERVER_PROCESS / WAIT_HTTP_READY /
        // READY_MONITORING）时，preferredKind 会从 STARTUP 切到 TAVERN_SERVER。
        // 之前只靠面板内部的轮询定时器去发现这个切换，体感上会一直停留在“启动日志”上。
        // 这里在 snapshot 推送时主动比对 preferredKind，发现变化且用户没有手动选定文件
        // 就立即触发一次实时刷新，让面板（含顶部文件标签）跟随阶段同步切到酒馆服务日志。
        val newPreferredKind = snapshot.currentLogTargets.preferredKind
        val previous = lastPreferredLogKind
        lastPreferredLogKind = newPreferredKind
        if (previous != null && previous != newPreferredKind && selectedLogFileName == null && views.panel.isVisible) {
            lastSnapshot = null
            requestRealtimeRefresh(resetAutoScroll = true)
        }
    }

    fun syncSettingsEntryState(snapshot: BootstrapSessionSnapshot) {
        val settingsEnabled = canOpenSettings(snapshot)
        views.openSettingsButton.isEnabled = settingsEnabled
        views.openSettingsButton.alpha = if (settingsEnabled) 1f else 0.35f
    }

    private fun configureBubbleTouch() {
        views.bubble.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var downViewX = 0f
            private var downViewY = 0f
            private var dragging = false
            private var longPressTriggered = false
            private var longPressRunnable: Runnable? = null

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        views.bubble.animate().cancel()
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downViewX = view.x
                        downViewY = view.y
                        dragging = false
                        longPressTriggered = false
                        longPressRunnable?.let(view::removeCallbacks)
                        longPressRunnable = Runnable {
                            if (!dragging && !longPressTriggered) {
                                longPressTriggered = true
                                if (canOpenSettings(currentSnapshot())) {
                                    openSettings()
                                }
                            }
                        }.also { runnable ->
                            view.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY
                        if (!dragging && (abs(deltaX) > bubbleTouchSlop || abs(deltaY) > bubbleTouchSlop)) {
                            dragging = true
                            longPressRunnable?.let(view::removeCallbacks)
                        }
                        if (dragging) {
                            layoutController.moveBubbleTo(downViewX + deltaX, downViewY + deltaY)
                            if (views.panel.isVisible) {
                                layoutController.repositionPanel()
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let(view::removeCallbacks)
                        longPressRunnable = null
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        if (!dragging && !longPressTriggered) {
                            view.performClick()
                        } else if (dragging) {
                            layoutController.dockToNearestSide(view.x + view.width / 2f)
                            layoutController.persistBubblePosition()
                            layoutController.alignBubbleToDockState(animated = true)
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let(view::removeCallbacks)
                        longPressRunnable = null
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        if (dragging) {
                            layoutController.dockToNearestSide(view.x + view.width / 2f)
                            layoutController.persistBubblePosition()
                            layoutController.alignBubbleToDockState(animated = true)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun configureControlButtons() {
        views.selectButton.setOnClickListener {
            showSelectDialog()
        }
        views.intervalButton.setOnClickListener {
            showIntervalDialog()
        }
        views.downloadButton.setOnClickListener {
            showExportDialog()
        }
        views.clearButton.setOnClickListener {
            confirmClearLogs()
        }
        views.openSettingsButton.setOnClickListener {
            setPanelVisible(false)
            openSettings()
        }
    }

    private fun startRefreshLoop() {
        stopRefreshLoop()
        if (!views.panel.isVisible) {
            return
        }

        if (preferences.floatingLogRefreshIntervalMillis == FloatingLogRefreshIntervals.REALTIME_MILLIS) {
            startRealtimeObserver()
            requestRealtimeRefresh()
            return
        }

        refreshJob = scope.launch {
            while (isActive && views.panel.isVisible) {
                renderLatestLog()
                delay(preferences.floatingLogRefreshIntervalMillis.toLong())
            }
        }
    }

    private fun stopRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = null
        realtimeRenderJob?.cancel()
        realtimeRenderJob = null
        realtimeRenderPending = false
        subscription?.close()
        subscription = null
    }

    private fun startRealtimeObserver() {
        if (subscription != null) {
            return
        }

        subscription = logRepository.subscribeToLogChanges(
            matcher = { path ->
                when {
                    path == null -> true
                    selectedLogFileName != null -> path.equals(selectedLogFileName, ignoreCase = true)
                    else -> path.endsWith(".log", ignoreCase = true)
                }
            }
        ) {
            if (views.panel.isVisible &&
                preferences.floatingLogRefreshIntervalMillis == FloatingLogRefreshIntervals.REALTIME_MILLIS
            ) {
                requestRealtimeRefresh()
            }
        }
    }

    private fun requestRealtimeRefresh(resetAutoScroll: Boolean = false) {
        if (resetAutoScroll) {
            autoScrollEnabled = true
        }
        if (!views.panel.isVisible) {
            return
        }

        if (realtimeRenderJob?.isActive == true) {
            realtimeRenderPending = true
            return
        }

        realtimeRenderJob = scope.launch {
            renderLatestLog()
        }.also { job ->
            job.invokeOnCompletion {
                realtimeRenderJob = null
                if (realtimeRenderPending) {
                    realtimeRenderPending = false
                    views.contentRoot.post {
                        requestRealtimeRefresh()
                    }
                }
            }
        }
    }

    private suspend fun renderLatestLog() {
        val preferTavernServerLog = currentSnapshot().shouldPreferTavernServerLog()
        val result = withContext(dispatchers.io) {
            val entries = logRepository.listEntries()
            val selectedEntry = selectedLogFileName?.let { selectedFileName ->
                entries.firstOrNull { entry -> entry.fileName == selectedFileName }
            }
            val snapshot = when {
                selectedEntry != null -> logRepository.readRealtimeSnapshot(selectedEntry)
                else -> logRepository.readPreferredRealtimeSnapshot(
                    preferTavernServerLog = preferTavernServerLog,
                    entries = entries
                )
            }
            Triple(entries, selectedEntry, snapshot)
        }

        val entries = result.first
        val selectedEntry = result.second
        val snapshot = result.third
        availableEntries = entries
        if (selectedLogFileName != null && selectedEntry == null) {
            selectedLogFileName = null
        }
        updateControlLabels()

        if (snapshot == lastSnapshot) {
            if (autoScrollEnabled) {
                scrollToBottom()
            }
            return
        }

        lastSnapshot = snapshot
        views.empty.isVisible = snapshot == null
        views.meta.isVisible = snapshot != null
        views.content.isVisible = snapshot != null

        if (snapshot == null) {
            views.meta.text = ""
            views.content.text = ""
            return
        }

        views.meta.text = text.logsMeta(snapshot.displayName, snapshot.updatedAt)
        views.content.text = snapshot.content.ifBlank { text.emptyContent() }
        if (autoScrollEnabled) {
            scrollToBottom()
        } else {
            views.scrollToBottomButton.isVisible = !isScrolledToBottom()
        }
    }

    private fun scrollToBottom() {
        views.content.post {
            if (!views.panel.isVisible) {
                return@post
            }

            val visibleHeight = views.scroll.height - views.scroll.paddingTop - views.scroll.paddingBottom
            if (visibleHeight <= 0) {
                return@post
            }

            val layoutBottom = views.content.layout?.let { layout ->
                if (views.content.lineCount > 0) {
                    layout.getLineBottom(views.content.lineCount - 1)
                } else {
                    views.content.height
                }
            } ?: views.content.height
            val targetScrollY = (layoutBottom + views.content.paddingBottom - visibleHeight).coerceAtLeast(0)
            views.scroll.scrollTo(0, targetScrollY)
        }
    }

    private fun isScrolledToBottom(): Boolean {
        val contentView = views.scroll.getChildAt(0) ?: return true
        val remainingScroll = contentView.bottom - (views.scroll.height + views.scroll.scrollY)
        val tolerancePx = (8 * activity.resources.displayMetrics.density).toInt()
        return remainingScroll <= tolerancePx
    }

    private fun refreshNow(resetAutoScroll: Boolean = false) {
        if (preferences.floatingLogRefreshIntervalMillis == FloatingLogRefreshIntervals.REALTIME_MILLIS) {
            requestRealtimeRefresh(resetAutoScroll)
            return
        }
        if (resetAutoScroll) {
            autoScrollEnabled = true
        }
        scope.launch {
            renderLatestLog()
        }
    }

    private fun updateControlLabels() {
        val selectedLogLabel = selectedLogFileName?.let { selectedFileName ->
            availableEntries.firstOrNull { entry -> entry.fileName == selectedFileName }?.displayName
        } ?: text.autoSelectLabel()

        views.selectButton.text = selectedLogLabel
        views.selectButton.isEnabled = availableEntries.isNotEmpty()
        views.intervalButton.text = refreshIntervalLabel(preferences.floatingLogRefreshIntervalMillis)
        views.intervalButton.isEnabled = true
    }

    private fun showSelectDialog() {
        if (availableEntries.isEmpty()) {
            return
        }

        val optionLabels = buildList {
            add(text.autoSelectLabel())
            availableEntries.forEach { entry ->
                add(entry.displayName)
            }
        }
        val checkedItem = selectedLogFileName?.let { selectedFileName ->
            availableEntries.indexOfFirst { entry -> entry.fileName == selectedFileName }
                .takeIf { index -> index >= 0 }
                ?.plus(1)
        } ?: 0

        MaterialAlertDialogBuilder(activity)
            .setTitle(text.selectDialogTitle())
            .setSingleChoiceItems(optionLabels.toTypedArray(), checkedItem) { dialog, which ->
                val selectedFileName = if (which == 0) {
                    null
                } else {
                    availableEntries.getOrNull(which - 1)?.fileName
                }
                if (selectedLogFileName != selectedFileName) {
                    selectedLogFileName = selectedFileName
                    updateControlLabels()
                    refreshNow(resetAutoScroll = true)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showIntervalDialog() {
        val intervalOptions = FloatingLogRefreshIntervals.options
        val optionLabels = intervalOptions.map(::refreshIntervalLabel)
        val checkedItem = intervalOptions.indexOf(preferences.floatingLogRefreshIntervalMillis).coerceAtLeast(0)

        MaterialAlertDialogBuilder(activity)
            .setTitle(text.intervalDialogTitle())
            .setSingleChoiceItems(optionLabels.toTypedArray(), checkedItem) { dialog, which ->
                val interval = intervalOptions.getOrNull(which) ?: return@setSingleChoiceItems
                if (preferences.floatingLogRefreshIntervalMillis != interval) {
                    preferences.floatingLogRefreshIntervalMillis = interval
                    updateControlLabels()
                    if (views.panel.isVisible) {
                        stopRefreshLoop()
                        startRefreshLoop()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshIntervalLabel(intervalMillis: Int): String {
        return when (intervalMillis) {
            FloatingLogRefreshIntervals.REALTIME_MILLIS -> text.realtimeIntervalLabel()
            FloatingLogRefreshIntervals.THREE_SECONDS_MILLIS -> text.threeSecondsIntervalLabel()
            FloatingLogRefreshIntervals.FIVE_SECONDS_MILLIS -> text.fiveSecondsIntervalLabel()
            else -> text.oneSecondIntervalLabel()
        }
    }

    private fun showExportDialog() {
        scope.launch {
            // 悬浮日志这里直接导出到下载目录，所以先在面板内完成一次类型确认，再执行真正写包。
            val result = withContext(dispatchers.io) {
                runCatching { logRepository.listExportOptions() }
            }
            result.onSuccess { options ->
                if (options.isEmpty()) {
                    Toast.makeText(activity, text.exportEmpty(), Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                showHostLogExportSelectionDialog(
                    activity = activity,
                    options = options,
                    text = HostLogExportSelectionDialogText(
                        title = text.exportDialogTitle(),
                        message = text.exportDialogMessage(),
                        sensitiveSuffix = text.exportSensitiveSuffix(),
                        confirmLabel = text.exportConfirmLabel()
                    )
                ) { selectedRelativePaths ->
                    exportLogsAsZip(selectedRelativePaths)
                }
            }.onFailure {
                Toast.makeText(activity, text.downloadFailed(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportLogsAsZip(selectedRelativePaths: Set<String>) {
        scope.launch {
            val result = withContext(dispatchers.io) {
                runCatching {
                    logRepository.exportToPublicDownloads(includedRelativePaths = selectedRelativePaths)
                }
            }
            result.onSuccess { export ->
                Toast.makeText(
                    activity,
                    text.downloadSuccess(export.bundleFileName, export.zipPath.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                Toast.makeText(activity, text.downloadFailed(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmClearLogs() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(text.clearConfirmTitle())
            .setMessage(text.clearConfirmMessage())
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(text.clearConfirmPositiveLabel()) { _, _ ->
                clearLogs()
            }
            .show()
    }

    private fun clearLogs() {
        scope.launch {
            val result = withContext(dispatchers.io) {
                runCatching {
                    logRepository.clearAllLogs()
                }
            }
            result.onSuccess {
                selectedLogFileName = null
                availableEntries = emptyList()
                lastSnapshot = null
                requestRealtimeRefresh(resetAutoScroll = true)
                Toast.makeText(activity, text.clearSuccess(), Toast.LENGTH_SHORT).show()
            }.onFailure { exception ->
                Toast.makeText(
                    activity,
                    exception.message ?: text.clearFailed(),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

data class FloatingLogsViews(
    val contentRoot: View,
    val bubble: ImageButton,
    val panel: View,
    val meta: TextView,
    val empty: TextView,
    val content: TextView,
    val scroll: NestedScrollView,
    val scrollbarThumb: View,
    val selectButton: MaterialButton,
    val intervalButton: MaterialButton,
    val closeButton: ImageButton,
    val reloadWebViewButton: MaterialButton,
    val downloadButton: MaterialButton,
    val clearButton: MaterialButton,
    val openSettingsButton: MaterialButton,
    val scrollToBottomButton: ImageButton
)

data class FloatingLogsText(
    val autoSelectLabel: () -> String,
    val selectDialogTitle: () -> String,
    val intervalDialogTitle: () -> String,
    val realtimeIntervalLabel: () -> String,
    val oneSecondIntervalLabel: () -> String,
    val threeSecondsIntervalLabel: () -> String,
    val fiveSecondsIntervalLabel: () -> String,
    val logsMeta: (displayName: String, updatedAt: String) -> String,
    val emptyContent: () -> String,
    val exportDialogTitle: () -> String,
    val exportDialogMessage: () -> String,
    val exportSensitiveSuffix: () -> String,
    val exportConfirmLabel: () -> String,
    val exportEmpty: () -> String,
    val downloadSuccess: (zipFileName: String, zipPath: String) -> String,
    val downloadFailed: () -> String,
    val clearConfirmTitle: () -> String,
    val clearConfirmMessage: () -> String,
    val clearConfirmPositiveLabel: () -> String,
    val clearSuccess: () -> String,
    val clearFailed: () -> String
)
