package com.jm.sillydroid.core.model.settings

/**
 * 统一收口本地 Node 服务的 V8 老生代堆上限（--max-old-space-size，单位 MB）。
 *
 * 设计约束：
 * - DEFAULT_MB = 0 表示“自动”，即宿主入口不向 Node 注入 --max-old-space-size，
 *   交给 V8 按设备可用内存自适应；这是默认值，避免在低内存机型上把堆上限钉得过高、
 *   推迟 GC 反而更易被 Android LMK 杀进程。
 * - 其余正档位是允许用户显式抬高 V8 堆上限的预设值，用于重角色库/长聊天场景缓解
 *   “V8 heap OOM”过早触发。它不能突破 Android 给进程的物理内存与后台存活限制，
 *   只是配套缓解项。
 * - sanitize 把任意输入吸附到最接近的合法档位；0（自动）与正档位之间不互相吸附，
 *   只有正值才会被吸附到最近的正档位，保证存储/设置 UI/入口脚本三处共用同一套口径，
 *   不各写一套 clamp 导致显示与实际注入不一致。
 */
object NodeHeapLimitOptions {
    /** 0 表示“自动”：不向 Node 注入 --max-old-space-size。 */
    const val AUTOMATIC_MB: Int = 0

    /** 默认采用“自动”，与历史行为（从不注入内存参数）保持一致。 */
    const val DEFAULT_MB: Int = AUTOMATIC_MB

    /** 允许显式设置的正档位（MB）。 */
    val explicitLimitOptionsMb: List<Int> = listOf(1024, 1536, 2048, 3072, 4096)

    /** 含“自动”在内的全部可选档位，供设置 UI 顺序渲染。 */
    val optionsMb: List<Int> = listOf(AUTOMATIC_MB) + explicitLimitOptionsMb

    /** 是否为“显式设置堆上限”（非自动）。 */
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
