package com.jm.sillydroid.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯 JVM 单测：[BootstrapAutoRestartBudget] 的预算窗口与 attempt 计数。
 */
class BootstrapAutoRestartBudgetTest {

    @Test
    fun `first attempt opens window and returns index 1`() {
        val budget = BootstrapAutoRestartBudget(attemptLimit = 3, windowMillis = 60_000L)
        val idx = budget.recordAttempt(nowMs = 1_000L)
        assertEquals(1, idx)
        val snap = budget.snapshot()
        assertEquals(1, snap.attemptCount)
        assertEquals(3, snap.attemptLimit)
        assertEquals(1_000L, snap.windowStartedAtMillis)
    }

    @Test
    fun `consecutive attempts within window increment until limit`() {
        // 注意：源实现用 windowStartedAtMs == 0L 作"窗口未开"哨兵，因此基准时间需 > 0。
        val budget = BootstrapAutoRestartBudget(attemptLimit = 3, windowMillis = 60_000L)
        assertEquals(1, budget.recordAttempt(1_000L))
        assertEquals(2, budget.recordAttempt(11_000L))
        assertEquals(3, budget.recordAttempt(21_000L))
    }

    @Test
    fun `attempt over limit within window returns null`() {
        val budget = BootstrapAutoRestartBudget(attemptLimit = 2, windowMillis = 60_000L)
        budget.recordAttempt(1_000L)
        budget.recordAttempt(11_000L)
        val rejected = budget.recordAttempt(21_000L)
        assertNull(rejected)
        // 拒绝后计数维持在上限，不会被改写
        assertEquals(2, budget.snapshot().attemptCount)
    }

    @Test
    fun `attempt strictly past window resets counter and reopens window`() {
        val budget = BootstrapAutoRestartBudget(attemptLimit = 2, windowMillis = 60_000L)
        budget.recordAttempt(1_000L)
        budget.recordAttempt(31_000L)
        // (61_002 - 1_000) > windowMillis（严格大于）→ 窗口重置
        val reset = budget.recordAttempt(61_002L)
        assertEquals(1, reset)
        assertEquals(61_002L, budget.snapshot().windowStartedAtMillis)
        assertEquals(1, budget.snapshot().attemptCount)
    }

    @Test
    fun `attempt exactly at window boundary stays in same window`() {
        val budget = BootstrapAutoRestartBudget(attemptLimit = 2, windowMillis = 60_000L)
        budget.recordAttempt(1_000L)
        // nowMs - windowStartedAtMs == windowMillis，不大于，仍在窗内
        val second = budget.recordAttempt(61_000L)
        assertEquals(2, second)
        assertEquals(1_000L, budget.snapshot().windowStartedAtMillis)
    }

    @Test
    fun `reset clears window and counter`() {
        val budget = BootstrapAutoRestartBudget(attemptLimit = 3, windowMillis = 60_000L)
        budget.recordAttempt(1_000L)
        budget.recordAttempt(2_000L)
        budget.reset()
        val snap = budget.snapshot()
        assertEquals(0, snap.attemptCount)
        assertEquals(0L, snap.windowStartedAtMillis)
        // reset 之后下一次 recordAttempt 应该重新开窗
        val next = budget.recordAttempt(5_000L)
        assertEquals(1, next)
        assertEquals(5_000L, budget.snapshot().windowStartedAtMillis)
    }

    @Test
    fun `snapshot reports the configured limit and window length`() {
        val budget = BootstrapAutoRestartBudget(attemptLimit = 5, windowMillis = 123_456L)
        val snap = budget.snapshot()
        assertEquals(0, snap.attemptCount)
        assertEquals(5, snap.attemptLimit)
        assertEquals(123_456L, snap.windowMillis)
        assertEquals(0L, snap.windowStartedAtMillis)
    }

    @Test
    fun `attempt with nowMs zero is accepted as first call but is fragile`() {
        // 行为现状：nowMs=0L 与 windowStartedAtMs 哨兵冲突
        // → 第一次 attempt 仍能返回 1，但下一次（即使 nowMs 仍 == 0）会被当作"新窗口"
        // 因此实际生产里禁止传 0；这里把现状锁住，提醒未来谁要修源实现需同步更新。
        val budget = BootstrapAutoRestartBudget(attemptLimit = 2, windowMillis = 60_000L)
        assertEquals(1, budget.recordAttempt(nowMs = 0L))
        // 下一次 nowMs=0L 仍被识别为"窗口未开" → 重置后还是 1，而非 2
        assertEquals(1, budget.recordAttempt(nowMs = 0L))
    }

    @Test
    fun `limit 1 budget rejects second attempt`() {
        val budget = BootstrapAutoRestartBudget(attemptLimit = 1, windowMillis = 60_000L)
        assertEquals(1, budget.recordAttempt(1_000L))
        assertNull(budget.recordAttempt(2_000L))
    }
}
