package com.jm.sillydroid.core.model.settings

/**
 * 宿主系统栏显示模式。
 *
 * NORMAL 保留顶部状态栏与底部手势区；
 * STATUS_BAR_HIDDEN 只隐藏顶部通知栏；
 * IMMERSIVE 同时隐藏顶部和底部系统栏，用户滑动边缘时可临时唤出。
 */
enum class HostDisplayMode {
    NORMAL,
    STATUS_BAR_HIDDEN,
    IMMERSIVE;

    companion object {
        fun fromStorageValue(rawValue: String?): HostDisplayMode {
            return entries.firstOrNull { entry ->
                entry.name.equals(rawValue.orEmpty().trim(), ignoreCase = true)
            } ?: NORMAL
        }
    }
}
