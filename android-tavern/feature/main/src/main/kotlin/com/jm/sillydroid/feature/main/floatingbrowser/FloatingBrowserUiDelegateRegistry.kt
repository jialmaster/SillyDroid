package com.jm.sillydroid.feature.main.floatingbrowser

import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeActions

/**
 * Activity 专属浏览器 UI delegate 注册表。
 *
 * 允许：当前 Activity 注册可见 UI 动作，进程级 JS bridge 每次调用时动态转发到最新 delegate。
 * 不允许：在 Activity 销毁后继续调用旧窗口、弹窗、文件授权或系统栏对象。
 */
class FloatingBrowserUiDelegateRegistry(
    private val isBrowserSessionActive: () -> Boolean
) {
    private var owner: Any? = null
    private var delegate: BrowserHostBridgeActions? = null
    private var lastHostVersionInfoJson: String = "{}"

    /** 注册当前 Activity 的 UI 动作，并缓存一份不含页面内容的宿主能力快照。 */
    @Synchronized
    fun register(owner: Any, delegate: BrowserHostBridgeActions) {
        this.owner = owner
        this.delegate = delegate
        runCatching { delegate.hostVersionInfoJson() }
            .getOrNull()
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> lastHostVersionInfoJson = value }
    }

    /** 仅由原注册 Activity 解除 delegate，避免旧 Activity 清掉新窗口的动作。 */
    @Synchronized
    fun unregister(owner: Any) {
        if (this.owner !== owner) {
            return
        }
        this.owner = null
        delegate = null
    }

    /**
     * 创建进程安全的 bridge 动作。
     *
     * 通知存活判断基于浏览器会话；所有 Activity 专属动作在无 delegate 时安全忽略。
     */
    fun createProcessActions(): BrowserHostBridgeActions {
        return BrowserHostBridgeActions(
            isHostActive = isBrowserSessionActive,
            runOnUiThread = { action -> currentDelegate()?.runOnUiThread?.invoke(action) },
            openSettings = { currentDelegate()?.openSettings?.invoke() },
            showFloatingLogsBubble = { currentDelegate()?.showFloatingLogsBubble?.invoke() },
            requestOpenCurrentPageInBrowser = { currentDelegate()?.requestOpenCurrentPageInBrowser?.invoke() },
            applyFloatingLogsBubbleEnabled = { enabled ->
                currentDelegate()?.applyFloatingLogsBubbleEnabled?.invoke(enabled)
            },
            applyBrowserPullRefreshEnabled = { enabled ->
                currentDelegate()?.applyBrowserPullRefreshEnabled?.invoke(enabled)
            },
            applySystemBarsBackgroundColor = { color ->
                currentDelegate()?.applySystemBarsBackgroundColor?.invoke(color)
            },
            applySystemBarsBackgroundColors = { statusColor, navigationColor ->
                currentDelegate()?.applySystemBarsBackgroundColors?.invoke(statusColor, navigationColor)
            },
            reloadTavern = { currentDelegate()?.reloadTavern?.invoke() },
            hostVersionInfoJson = ::resolveHostVersionInfoJson,
            recordWebPerformanceDiagnosticPayload = { payload ->
                currentDelegate()?.recordWebPerformanceDiagnosticPayload?.invoke(payload)
            },
            requestNotificationPermission = {
                currentDelegate()?.requestNotificationPermission?.invoke()
            },
            showDownloadFailure = { report ->
                currentDelegate()?.showDownloadFailure?.invoke(report)
            }
        )
    }

    /** 返回当前 delegate 快照，调用方不得长期保存该 Activity 动作对象。 */
    @Synchronized
    private fun currentDelegate(): BrowserHostBridgeActions? = delegate

    /** 有 Activity 时刷新宿主信息；无 Activity 时返回最后一份进程安全快照。 */
    @Synchronized
    private fun resolveHostVersionInfoJson(): String {
        val current = delegate
        if (current != null) {
            runCatching { current.hostVersionInfoJson() }
                .getOrNull()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> lastHostVersionInfoJson = value }
        }
        return lastHostVersionInfoJson
    }
}
