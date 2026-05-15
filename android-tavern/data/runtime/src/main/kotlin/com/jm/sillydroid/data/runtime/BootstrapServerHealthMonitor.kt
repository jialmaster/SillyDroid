package com.jm.sillydroid.data.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 把原本散落在 [BootstrapSessionManager] 里的两条健康监控协程作业搬到独立类：
 *   - **Server exit monitor**：阻塞在 `process.waitFor()`，进程意外退出时回调 [Callback.onServerExit]。
 *   - **Ready watchdog**：周期性 HTTP 探活，连续失败到阈值时回调 [Callback.onReadyWatchdogTriggered]，
 *     单次失败回调 [Callback.onProbeFailed]（用于上层 emit 事件）。
 *
 * 保持单一职责：本类**只做检测和报告**，不做：
 *   - 具体的"重启调度"决策（由 manager 的 scheduleAutoRestart 完成）；
 *   - snapshot 发布与 BootstrapEvent 序列化（由 manager 完成）。
 *
 * 这样 monitor 可以单独单测：传入假的 [Process] / 假的 `isReadyProbe` lambda，验证 callback 触发顺序。
 */
internal class BootstrapServerHealthMonitor(
    private val scope: CoroutineScope,
    private val readyWatchdogIntervalMillis: Long,
    private val readyWatchdogFailureThreshold: Int,
    private val readyWatchdogSuccessLogEvery: Int,
    private val callback: Callback,
    private val isReadyProbe: (String) -> Boolean = { HealthProbe.isReady(it) }
) {
    /**
     * 监控向 manager 上报事件的回调界面。
     */
    interface Callback {
        /** 写一行启动日志（startup.log）。 */
        fun appendStartupLog(line: String)

        /**
         * 单次探针失败的事件钩子（连续失败计数尚未到阈值时也会调用）。
         * manager 通常用它向外 emit `BootstrapEvent.ProbeFailed`。
         */
        fun onProbeFailed(targetUrl: String, consecutiveFailures: Int, failureThreshold: Int)

        /** server 进程已退出（可能正常或异常）；manager 决定如何处理。 */
        fun onServerExit(exitCode: Int)

        /** ready watchdog 连续失败到阈值；manager 通常触发 auto-restart。 */
        fun onReadyWatchdogTriggered(localUrl: String, failureThreshold: Int)
    }

    private var serverMonitorJob: Job? = null
    private var readyWatchdogJob: Job? = null

    fun startServerMonitor(waitForExit: () -> Int) {
        serverMonitorJob?.cancel()
        callback.appendStartupLog("Server exit monitor started for the current Tavern process.")
        serverMonitorJob = scope.launch {
            val exitCode = waitForExit()
            if (!isActive) return@launch
            callback.appendStartupLog("Server process exited unexpectedly. exitCode=$exitCode")
            callback.onServerExit(exitCode)
        }
    }

    fun stopServerMonitor() {
        serverMonitorJob?.cancel()
        serverMonitorJob = null
    }

    fun startReadyWatchdog(readinessUrl: String, localUrl: String) {
        readyWatchdogJob?.cancel()
        callback.appendStartupLog(
            "Ready watchdog started. interval=${readyWatchdogIntervalMillis}ms threshold=$readyWatchdogFailureThreshold target=$readinessUrl"
        )
        readyWatchdogJob = scope.launch {
            var consecutiveFailures = 0
            var successfulProbeCount = 0
            while (isActive) {
                delay(readyWatchdogIntervalMillis)
                if (isReadyProbe(readinessUrl)) {
                    successfulProbeCount += 1
                    if (consecutiveFailures > 0) {
                        callback.appendStartupLog(
                            "Ready watchdog probe recovered after $consecutiveFailures failure(s): $readinessUrl"
                        )
                    } else if (successfulProbeCount == 1 ||
                        successfulProbeCount % readyWatchdogSuccessLogEvery == 0
                    ) {
                        callback.appendStartupLog(
                            "Ready watchdog probe ok (#$successfulProbeCount): $readinessUrl"
                        )
                    }
                    consecutiveFailures = 0
                    continue
                }

                consecutiveFailures += 1
                callback.appendStartupLog(
                    "Ready watchdog probe failed ($consecutiveFailures/$readyWatchdogFailureThreshold): $readinessUrl"
                )
                callback.onProbeFailed(readinessUrl, consecutiveFailures, readyWatchdogFailureThreshold)
                if (consecutiveFailures < readyWatchdogFailureThreshold) {
                    continue
                }

                callback.onReadyWatchdogTriggered(localUrl, readyWatchdogFailureThreshold)
                return@launch
            }
        }
    }

    fun stopReadyWatchdog(reason: String? = null) {
        if (readyWatchdogJob != null && !reason.isNullOrBlank()) {
            callback.appendStartupLog("Ready watchdog stopped: $reason")
        }
        readyWatchdogJob?.cancel()
        readyWatchdogJob = null
    }

    /** 同时停掉两条 job；reason 仅作用于 ready watchdog 的日志。 */
    fun stopAll(reason: String? = null) {
        stopServerMonitor()
        stopReadyWatchdog(reason)
    }
}
