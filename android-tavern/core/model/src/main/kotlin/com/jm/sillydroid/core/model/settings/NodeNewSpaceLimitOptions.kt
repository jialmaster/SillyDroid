package com.jm.sillydroid.core.model.settings

/**
 * 统一收口本地 Node 服务的 V8 新生代 semi-space 上限（--max-semi-space-size，单位 MB）。
 *
 * 设计约束：
 * - DEFAULT_MB = 0 表示“自动”，即宿主入口不向 Node 注入 --max-semi-space-size，
 *   交给 V8 自适应（保持历史行为）。新生代偏小时 Scavenge（小 GC）会更频繁，
 *   长聊天/大列表下短命对象多，表现为周期性卡顿；显式抬高 semi-space 可用内存
 *   换 GC 频率，缓解卡顿，但同样不能突破 Android 给进程的物理内存限制。
 * - 其余正档位是允许用户显式抬高新生代的预设值。注意它的量级远小于老生代上限
 *   （--max-old-space-size），不要与老生代档位混用同一组数值。
 * - sanitize 与老生代口径一致：0（自动）与正档位互不吸附，正值吸附到最近正档位，
 *   并列取较小档位；保证存储/设置 UI/入口脚本三处共用同一套口径。
 */
object NodeNewSpaceLimitOptions {
    /** 0 表示“自动”：不向 Node 注入 --max-semi-space-size。 */
    const val AUTOMATIC_MB: Int = 0

    /** 默认采用“自动”，与历史行为（从不注入新生代参数）保持一致。 */
    const val DEFAULT_MB: Int = AUTOMATIC_MB

    /** 允许显式设置的正档位（MB）。新生代量级远小于老生代。 */
    val explicitLimitOptionsMb: List<Int> = listOf(16, 32, 64, 128)

    /** 含“自动”在内的全部可选档位，供设置 UI 顺序渲染。 */
    val optionsMb: List<Int> = listOf(AUTOMATIC_MB) + explicitLimitOptionsMb

    /** 是否为“显式设置新生代上限”（非自动）。 */
    fun isExplicit(valueMb: Int): Boolean = sanitize(valueMb) != AUTOMATIC_MB

    fun sanitize(valueMb: Int): Int {
        if (valueMb <= AUTOMATIC_MB) {
            return AUTOMATIC_MB
        }
        // 正值吸附到最近的正档位；并列时取较小档位，避免在低内存机型上意外抬高到更大值。
        return explicitLimitOptionsMb.minByOrNull { option ->
            val distance = kotlin.math.abs(option - valueMb)
            distance.toLong() * explicitLimitOptionsMb.size + option
        } ?: AUTOMATIC_MB
    }
}
