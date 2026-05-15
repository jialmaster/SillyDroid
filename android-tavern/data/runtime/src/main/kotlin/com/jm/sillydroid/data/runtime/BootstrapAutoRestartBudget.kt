package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.core.model.bootstrap.BootstrapRestartBudgetSnapshot

/**
 * Pure auto-restart budget bookkeeping extracted from [BootstrapSessionManager].
 *
 * Tracks attempts within a sliding window. The window resets either on the first attempt
 * or once the elapsed time since [windowStartedAtMs] exceeds [windowMillis].
 *
 * 单一职责：只管"还能再重启几次"的算账，不参与协程调度 / 日志 / 事件分发，
 * 因此可以脱离 Android / coroutines 进行 JVM 单元测试。
 */
internal class BootstrapAutoRestartBudget(
    val attemptLimit: Int,
    val windowMillis: Long
) {
    private var windowStartedAtMs: Long = 0L
    private var attemptCount: Int = 0

    /**
     * Try to consume one attempt slot.
     *
     * @return new attempt index in `1..attemptLimit`, or `null` when the budget is exhausted
     *         within the current window.
     */
    fun recordAttempt(nowMs: Long): Int? {
        if (windowStartedAtMs == 0L || nowMs - windowStartedAtMs > windowMillis) {
            windowStartedAtMs = nowMs
            attemptCount = 0
        }
        if (attemptCount >= attemptLimit) return null
        attemptCount += 1
        return attemptCount
    }

    fun reset() {
        windowStartedAtMs = 0L
        attemptCount = 0
    }

    fun snapshot(): BootstrapRestartBudgetSnapshot = BootstrapRestartBudgetSnapshot(
        attemptCount = attemptCount,
        attemptLimit = attemptLimit,
        windowStartedAtMillis = windowStartedAtMs,
        windowMillis = windowMillis
    )
}
