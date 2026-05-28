package com.jm.sillydroid.core.model.settings

enum class BrowserDataClearTarget(val mask: Int, val selectedByDefault: Boolean) {
    // WebView 只能按资源缓存整体清理；设置页文案里的 JS/CSS 缓存落到这里，不默认碰用户站点数据。
    RESOURCE_CACHE(mask = 1, selectedByDefault = true),
    SITE_STORAGE(mask = 1 shl 1, selectedByDefault = false),
    COOKIES(mask = 1 shl 2, selectedByDefault = false),
    HISTORY_AND_FORM_DATA(mask = 1 shl 3, selectedByDefault = false)
}

object BrowserDataClearOptions {
    val defaultMask: Int = maskOf(BrowserDataClearTarget.values().filter { it.selectedByDefault })
    val fullMask: Int = maskOf(BrowserDataClearTarget.values().asIterable())

    fun maskOf(targets: Iterable<BrowserDataClearTarget>): Int {
        return targets.fold(0) { mask, target -> mask or target.mask }
    }

    fun normalize(mask: Int): Int {
        return mask and fullMask
    }

    fun contains(mask: Int, target: BrowserDataClearTarget): Boolean {
        return normalize(mask) and target.mask != 0
    }

    fun normalizeOrDefault(mask: Int): Int {
        val normalized = normalize(mask)
        return if (normalized == 0) defaultMask else normalized
    }
}
