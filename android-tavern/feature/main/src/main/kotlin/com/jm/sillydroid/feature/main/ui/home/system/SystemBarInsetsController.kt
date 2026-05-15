package com.jm.sillydroid.feature.main.ui.home.system

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
            view.setPadding(
                initialLeftPadding + systemBarsInsets.left,
                initialTopPadding + systemBarsInsets.top,
                initialRightPadding + systemBarsInsets.right,
                initialBottomPadding + systemBarsInsets.bottom + (imeInsets?.bottom ?: 0)
            )
            onContentBoundsChanged()
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }
}
