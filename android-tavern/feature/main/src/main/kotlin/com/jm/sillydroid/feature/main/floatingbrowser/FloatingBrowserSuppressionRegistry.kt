package com.jm.sillydroid.feature.main.floatingbrowser

import java.util.concurrent.atomic.AtomicLong

/**
 * 记录文件选择器等外部系统页面期间的悬浮窗抑制令牌。
 *
 * 令牌必须显式关闭；注册表不负责超时兜底，避免系统页面尚未结束时自行显示 overlay。
 */
class FloatingBrowserSuppressionRegistry {
    private val nextId = AtomicLong(0L)
    private val activeReasons = linkedMapOf<Long, String>()

    /** 为一个外部系统流程创建可关闭令牌。 */
    @Synchronized
    fun acquire(reason: String): AutoCloseable {
        val id = nextId.incrementAndGet()
        activeReasons[id] = reason.ifBlank { "unknown" }
        return AutoCloseable { release(id) }
    }

    /** 当前是否存在尚未结束的系统流程。 */
    @Synchronized
    fun isSuppressed(): Boolean = activeReasons.isNotEmpty()

    /** 返回稳定排序的诊断原因，不包含用户文件名或页面内容。 */
    @Synchronized
    fun diagnosticReasons(): String = activeReasons.values.distinct().sorted().joinToString(separator = "|")

    /** 释放指定令牌；重复释放保持幂等，不影响其他并行系统流程。 */
    @Synchronized
    private fun release(id: Long) {
        activeReasons.remove(id)
    }
}
