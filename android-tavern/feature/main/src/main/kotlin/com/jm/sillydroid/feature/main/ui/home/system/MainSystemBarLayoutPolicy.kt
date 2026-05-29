package com.jm.sillydroid.feature.main.ui.home.system

import com.jm.sillydroid.core.model.settings.HostDisplayMode

internal data class SystemBarEdgeInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal data class MainContentPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val statusBarBackgroundHeight: Int,
    val navigationBarBackgroundHeight: Int
)

/**
 * 横屏主界面按酒馆 WebView 使用场景默认隐藏顶部状态栏，避免视频/聊天横屏时顶部留系统栏空带。
 */
internal fun resolveMainHostDisplayMode(
    configuredMode: HostDisplayMode,
    isLandscape: Boolean
): HostDisplayMode {
    if (!isLandscape || configuredMode != HostDisplayMode.NORMAL) {
        return configuredMode
    }

    return HostDisplayMode.STATUS_BAR_HIDDEN
}

/**
 * 主界面 WebView 的系统栏 padding 规则集中在这里，保证横屏手势栏不会把内容从左右边缘挤出空白。
 */
internal fun calculateMainContentPadding(
    initialPadding: SystemBarEdgeInsets,
    systemBarsInsets: SystemBarEdgeInsets,
    imeBottomInset: Int,
    displayMode: HostDisplayMode,
    isLandscape: Boolean
): MainContentPadding {
    val topInset = when (displayMode) {
        HostDisplayMode.NORMAL -> systemBarsInsets.top
        HostDisplayMode.STATUS_BAR_HIDDEN,
        HostDisplayMode.IMMERSIVE -> 0
    }
    val bottomSystemInset = when {
        displayMode == HostDisplayMode.IMMERSIVE -> 0
        isLandscape -> 0
        else -> systemBarsInsets.bottom
    }
    val sideSystemInset = when (displayMode) {
        HostDisplayMode.NORMAL -> systemBarsInsets
        HostDisplayMode.STATUS_BAR_HIDDEN,
        HostDisplayMode.IMMERSIVE -> SystemBarEdgeInsets(left = 0, top = 0, right = 0, bottom = 0)
    }

    return MainContentPadding(
        left = initialPadding.left + sideSystemInset.left,
        top = initialPadding.top + topInset,
        right = initialPadding.right + sideSystemInset.right,
        bottom = initialPadding.bottom + bottomSystemInset + imeBottomInset,
        statusBarBackgroundHeight = topInset,
        navigationBarBackgroundHeight = bottomSystemInset
    )
}
