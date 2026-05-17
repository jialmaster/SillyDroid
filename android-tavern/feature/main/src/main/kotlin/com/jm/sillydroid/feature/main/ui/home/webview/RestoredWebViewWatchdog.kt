package com.jm.sillydroid.feature.main.ui.home.webview

/**
 * WebView session 恢复后的 commit-visible 兜底守望器。
 *
 * 调度本身被抽出为 [Scheduler]，便于在 JVM 单测里注入假调度器推进时间，
 * 不依赖 `webView.postDelayed` 等真实 Android handler。
 *
 * 行为：
 * - [start] 时取消之前未触发的任务，再排一个新的 delay 任务；
 * - 任务到点会调用 `onTimeout(url)` 一次，随后 watchdog 复位；
 * - [cancel] 任何时刻显式取消，幂等；
 * - 不感知 Activity/WebView 生命周期，调用方在 `onTimeout` 里自行做识别（避免对已 destroy 的 WebView 误操作）。
 */
class RestoredWebViewWatchdog(
    private val scheduler: Scheduler,
    private val timeoutMillis: Long
) {
    fun interface Scheduler {
        fun schedule(delayMillis: Long, task: Runnable): Cancellable
    }

    fun interface Cancellable {
        fun cancel()
    }

    private var pending: Cancellable? = null
    private var pendingTargetUrl: String? = null

    val isScheduled: Boolean
        get() = pending != null

    val pendingUrl: String?
        get() = pendingTargetUrl

    fun start(targetUrl: String, onTimeout: (url: String) -> Unit) {
        cancel()
        pendingTargetUrl = targetUrl
        pending = scheduler.schedule(timeoutMillis) {
            val url = pendingTargetUrl
            pending = null
            pendingTargetUrl = null
            if (url != null) {
                onTimeout(url)
            }
        }
    }

    fun cancel() {
        val current = pending ?: return
        pending = null
        pendingTargetUrl = null
        current.cancel()
    }
}
