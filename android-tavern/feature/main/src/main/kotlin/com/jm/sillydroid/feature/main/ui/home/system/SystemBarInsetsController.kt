package com.jm.sillydroid.feature.main.ui.home.system

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.feature.main.ui.home.HomeViewModel

/**
 * 把 system bars / display cutout / IME inset 的 padding 调整逻辑收口在这里。
 *
 * MainActivity 只负责构造时绑定 contentRoot 与回调；IME 状态变化时通过 [onImeChanged] 通知
 * 上层（一般用于让 WebView 暂停下拉刷新），bounds 改变时通过 [onContentBoundsChanged] 通知
 * 悬浮日志按钮重新计算可视范围。
 */
class SystemBarInsetsController(
    private val contentRoot: View,
    private val homeViewModel: HomeViewModel,
    private val displayModeProvider: () -> HostDisplayMode,
    private val onImeChanged: (Boolean) -> Unit,
    private val onContentBoundsChanged: () -> Unit,
) {
    fun install() {
        val initialLeftPadding = contentRoot.paddingLeft
        val initialTopPadding = contentRoot.paddingTop
        val initialRightPadding = contentRoot.paddingRight
        val initialBottomPadding = contentRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val systemBarsInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val displayMode = displayModeProvider()
            val imeVisibleNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisibleNow != homeViewModel.isImeVisible) {
                homeViewModel.isImeVisible = imeVisibleNow
                onImeChanged(imeVisibleNow)
            }
            val imeInsets = if (imeVisibleNow) {
                insets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                null
            }
            val topInset = when (displayMode) {
                // 普通模式保留顶部安全区；另外两档都要求让 WebView 顶到最上面，不再留状态栏空带。
                HostDisplayMode.NORMAL -> systemBarsInsets.top
                HostDisplayMode.STATUS_BAR_HIDDEN,
                HostDisplayMode.IMMERSIVE -> 0
            }
            val bottomSystemInset = when (displayMode) {
                // 沉浸式全屏下底部也不再为系统栏预留空白，手势唤出的 transient bars 直接压在内容上。
                HostDisplayMode.IMMERSIVE -> 0
                HostDisplayMode.NORMAL,
                HostDisplayMode.STATUS_BAR_HIDDEN -> systemBarsInsets.bottom
            }
            view.setPadding(
                initialLeftPadding + systemBarsInsets.left,
                initialTopPadding + topInset,
                initialRightPadding + systemBarsInsets.right,
                initialBottomPadding + bottomSystemInset + (imeInsets?.bottom ?: 0)
            )
            onContentBoundsChanged()
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    fun refresh() {
        ViewCompat.requestApplyInsets(contentRoot)
    }
}
