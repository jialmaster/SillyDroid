package com.jm.sillydroid.feature.main.ui.home.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用一个手动可推进的 [FakeScheduler] 验证 watchdog 的状态机，
 * 不依赖 `webView.postDelayed` 等真实 Android handler。
 */
class RestoredWebViewWatchdogTest {

    private class ScheduledTask(
        val delayMillis: Long,
        val task: Runnable,
        var cancelled: Boolean = false,
        var fired: Boolean = false
    )

    private class FakeScheduler : RestoredWebViewWatchdog.Scheduler {
        val scheduled: MutableList<ScheduledTask> = mutableListOf()

        override fun schedule(
            delayMillis: Long,
            task: Runnable
        ): RestoredWebViewWatchdog.Cancellable {
            val entry = ScheduledTask(delayMillis = delayMillis, task = task)
            scheduled += entry
            return RestoredWebViewWatchdog.Cancellable { entry.cancelled = true }
        }

        fun fireLast() {
            val entry = scheduled.last()
            check(!entry.cancelled) { "last task was already cancelled" }
            check(!entry.fired) { "last task already fired" }
            entry.fired = true
            entry.task.run()
        }
    }

    @Test
    fun `start schedules with configured delay and url`() {
        val scheduler = FakeScheduler()
        val watchdog = RestoredWebViewWatchdog(scheduler, timeoutMillis = 6_000L)

        watchdog.start("http://127.0.0.1:8000/") { /* unused */ }

        assertEquals(1, scheduler.scheduled.size)
        assertEquals(6_000L, scheduler.scheduled.last().delayMillis)
        assertTrue(watchdog.isScheduled)
        assertEquals("http://127.0.0.1:8000/", watchdog.pendingUrl)
    }

    @Test
    fun `timeout fires onTimeout with target url then resets state`() {
        val scheduler = FakeScheduler()
        val watchdog = RestoredWebViewWatchdog(scheduler, timeoutMillis = 100L)
        val fired = mutableListOf<String>()

        watchdog.start("http://127.0.0.1:8000/") { url -> fired += url }
        scheduler.fireLast()

        assertEquals(listOf("http://127.0.0.1:8000/"), fired)
        assertFalse(watchdog.isScheduled)
        assertNull(watchdog.pendingUrl)
    }

    @Test
    fun `cancel before timeout cancels scheduled task and clears state`() {
        val scheduler = FakeScheduler()
        val watchdog = RestoredWebViewWatchdog(scheduler, timeoutMillis = 100L)
        val fired = mutableListOf<String>()

        watchdog.start("http://127.0.0.1:8000/") { url -> fired += url }
        watchdog.cancel()

        assertTrue(scheduler.scheduled.last().cancelled)
        assertFalse(watchdog.isScheduled)
        assertNull(watchdog.pendingUrl)
        assertTrue("onTimeout should not have been invoked", fired.isEmpty())
    }

    @Test
    fun `cancel is idempotent and safe when nothing scheduled`() {
        val scheduler = FakeScheduler()
        val watchdog = RestoredWebViewWatchdog(scheduler, timeoutMillis = 100L)

        // 多次 cancel 不应抛异常或 schedule 出额外任务。
        watchdog.cancel()
        watchdog.cancel()

        assertEquals(0, scheduler.scheduled.size)
        assertFalse(watchdog.isScheduled)
    }

    @Test
    fun `start while pending replaces previous schedule`() {
        val scheduler = FakeScheduler()
        val watchdog = RestoredWebViewWatchdog(scheduler, timeoutMillis = 100L)
        val fired = mutableListOf<String>()

        watchdog.start("http://old.example/") { url -> fired += "old:$url" }
        watchdog.start("http://new.example/") { url -> fired += "new:$url" }

        assertEquals(2, scheduler.scheduled.size)
        assertTrue("first schedule should be cancelled", scheduler.scheduled[0].cancelled)
        assertFalse("new schedule should still be live", scheduler.scheduled[1].cancelled)
        assertEquals("http://new.example/", watchdog.pendingUrl)

        scheduler.fireLast()
        assertEquals(listOf("new:http://new.example/"), fired)
    }

    @Test
    fun `firing after cancel does nothing`() {
        // 模拟“调度器 race”：cancel 后 scheduler 仍把任务 fire 了一遍。
        // watchdog 的 once-shot 状态保护应让 onTimeout 不被调用。
        val scheduler = FakeScheduler()
        val watchdog = RestoredWebViewWatchdog(scheduler, timeoutMillis = 100L)
        val fired = mutableListOf<String>()

        watchdog.start("http://127.0.0.1:8000/") { url -> fired += url }
        watchdog.cancel()
        // 手动跑一次原 task：cancel 之后 pendingUrl == null，应短路。
        scheduler.scheduled.last().task.run()

        assertTrue(fired.isEmpty())
    }

    @Test
    fun `second start after timeout works as fresh schedule`() {
        val scheduler = FakeScheduler()
        val watchdog = RestoredWebViewWatchdog(scheduler, timeoutMillis = 100L)
        val fired = mutableListOf<String>()

        watchdog.start("http://first/") { url -> fired += "first:$url" }
        scheduler.fireLast()
        watchdog.start("http://second/") { url -> fired += "second:$url" }
        scheduler.fireLast()

        assertEquals(listOf("first:http://first/", "second:http://second/"), fired)
    }
}
