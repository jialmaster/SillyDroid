package com.jm.sillydroid.core.ui.window

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors
import com.jm.sillydroid.core.model.settings.HostDisplayMode

/**
 * 统一把系统栏背景色和前景明暗同步到当前宿主或 WebView 页面背景。
 *
 * 这个宿主开启了 edge-to-edge，系统状态栏和底部手势区都会直接贴着页面背景显示；
 * 如果不同时控制“背景色 + 浅色/深色前景”，浅色模式下很容易出现白底白字。
 * 这里统一由宿主给系统栏落一个可读底色，并按亮度自动切换图标/手势条外观。
 */
object SystemBarAppearanceController {
    private const val LIGHT_SURFACE_LUMINANCE_THRESHOLD = 0.5

    fun applyForCurrentTheme(
        activity: Activity,
        mode: HostDisplayMode,
        @ColorInt lightSurfaceColor: Int,
        @ColorInt darkSurfaceColor: Int
    ) {
        val isNightMode = (
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES
        applyForColor(activity, mode, if (isNightMode) darkSurfaceColor else lightSurfaceColor)
    }

    fun applyForThemeSurface(
        activity: Activity,
        mode: HostDisplayMode,
        @AttrRes surfaceColorAttr: Int,
        @ColorInt fallbackColor: Int = Color.BLACK
    ) {
        applyForColor(
            activity = activity,
            mode = mode,
            backgroundColor = MaterialColors.getColor(activity, surfaceColorAttr, fallbackColor)
        )
    }

    fun applyForHexColor(
        activity: Activity,
        mode: HostDisplayMode,
        hexColor: String,
        @ColorInt fallbackColor: Int
    ) {
        val parsedColor = runCatching { Color.parseColor(hexColor.trim()) }
            .getOrDefault(fallbackColor)
        applyForColor(activity, mode, parsedColor)
    }

    fun applyForColor(
        activity: Activity,
        mode: HostDisplayMode,
        @ColorInt backgroundColor: Int
    ) {
        applyForColors(
            activity = activity,
            mode = mode,
            statusBarColor = backgroundColor,
            navigationBarColor = backgroundColor
        )
    }

    fun applyForColors(
        activity: Activity,
        mode: HostDisplayMode,
        @ColorInt statusBarColor: Int,
        @ColorInt navigationBarColor: Int
    ) {
        val opaqueStatusBarColor = ColorUtils.setAlphaComponent(statusBarColor, 255)
        val opaqueNavigationBarColor = ColorUtils.setAlphaComponent(navigationBarColor, 255)
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val shouldUseDarkStatusForeground =
            ColorUtils.calculateLuminance(opaqueStatusBarColor) >= LIGHT_SURFACE_LUMINANCE_THRESHOLD
        val shouldUseDarkNavigationForeground =
            ColorUtils.calculateLuminance(opaqueNavigationBarColor) >= LIGHT_SURFACE_LUMINANCE_THRESHOLD

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 手势导航下底部“小白条”后面的发白区域，很多机型其实是系统为了可读性强加的 contrast scrim。
            // 这里既然宿主已经自己提供了实色导航栏背景，就关闭系统额外加白遮罩，避免底部和页面断层。
            activity.window.isNavigationBarContrastEnforced = false
            activity.window.isStatusBarContrastEnforced = false
        }
        activity.window.statusBarColor = opaqueStatusBarColor
        activity.window.navigationBarColor = opaqueNavigationBarColor
        applyVisibilityMode(insetsController, mode)
        applyForegroundAppearance(
            insetsController = insetsController,
            shouldUseDarkStatusForeground = shouldUseDarkStatusForeground,
            shouldUseDarkNavigationForeground = shouldUseDarkNavigationForeground
        )
        // 某些 OEM 在 show/hide system bars 之后会把前景明暗标志刷回默认值；
        // 这里在下一帧再补打一遍，优先保证浅色背景下状态栏文字不是白的。
        activity.window.decorView.post {
            applyForegroundAppearance(
                insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView),
                shouldUseDarkStatusForeground = shouldUseDarkStatusForeground,
                shouldUseDarkNavigationForeground = shouldUseDarkNavigationForeground
            )
        }
    }

    private fun applyVisibilityMode(
        insetsController: WindowInsetsControllerCompat,
        mode: HostDisplayMode
    ) {
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        when (mode) {
            HostDisplayMode.NORMAL -> {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }

            HostDisplayMode.STATUS_BAR_HIDDEN -> {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.show(WindowInsetsCompat.Type.navigationBars())
            }

            HostDisplayMode.IMMERSIVE -> {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun applyForegroundAppearance(
        insetsController: WindowInsetsControllerCompat,
        shouldUseDarkStatusForeground: Boolean,
        shouldUseDarkNavigationForeground: Boolean
    ) {
        // Android 的 "light system bars" 标志实际含义是“使用深色前景”，所以这里按背景亮度反着算。
        insetsController.isAppearanceLightStatusBars = shouldUseDarkStatusForeground
        insetsController.isAppearanceLightNavigationBars = shouldUseDarkNavigationForeground
    }
}
