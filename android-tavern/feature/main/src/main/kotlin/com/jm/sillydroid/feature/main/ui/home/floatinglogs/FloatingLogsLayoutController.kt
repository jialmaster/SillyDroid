package com.jm.sillydroid.feature.main.ui.home.floatinglogs

import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import kotlin.math.abs

class FloatingLogsLayoutController(
    private val contentRoot: View,
    private val bubble: View,
    private val panel: View,
    private val savedPosition: () -> FloatingLogBubblePosition?,
    private val savePosition: (FloatingLogBubblePosition) -> Unit,
    private val panelWidthPx: () -> Int,
    private val panelHeightPx: () -> Int,
    private val panelHorizontalMarginPx: () -> Int,
    private val panelVerticalMarginPx: () -> Int,
    private val panelGapPx: () -> Float
) {
    private enum class DockSide {
        LEFT,
        RIGHT
    }

    private data class PanelSize(
        val width: Int,
        val height: Int,
        val layoutChanged: Boolean
    )

    private var dockSide = DockSide.RIGHT
    private val bubbleRevealInterpolator = OvershootInterpolator(0.9f)
    private val bubbleDockInterpolator = OvershootInterpolator(0.55f)

    fun moveBubbleTo(targetX: Float, targetY: Float) {
        if (!canResolveBounds()) {
            return
        }

        val minX = contentRoot.paddingLeft.toFloat()
        val maxX = (contentRoot.width - contentRoot.paddingRight - bubble.width).toFloat().coerceAtLeast(minX)
        val minY = contentRoot.paddingTop.toFloat()
        val maxY = (contentRoot.height - contentRoot.paddingBottom - bubble.height).toFloat().coerceAtLeast(minY)

        bubble.x = targetX.coerceIn(minX, maxX)
        bubble.y = targetY.coerceIn(minY, maxY)
    }

    fun dockToNearestSide(bubbleCenterX: Float) {
        dockSide = if (bubbleCenterX <= contentRoot.width / 2f) DockSide.LEFT else DockSide.RIGHT
    }

    fun alignBubbleToDockState(animated: Boolean) {
        if (panel.isVisible) {
            revealBubble(animated) {
                repositionPanel()
            }
        } else {
            dockBubble(animated)
        }
    }

    fun dockBubble(animated: Boolean) {
        animateBubbleX(
            targetX = resolveDockedBubbleX(dockSide),
            animated = animated,
            durationMs = 220L,
            interpolator = bubbleDockInterpolator,
            endAction = null
        )
    }

    fun revealBubble(animated: Boolean, onEnd: (() -> Unit)? = null) {
        animateBubbleX(
            targetX = resolveExposedBubbleX(dockSide),
            animated = animated,
            durationMs = 240L,
            interpolator = bubbleRevealInterpolator,
            endAction = onEnd
        )
    }

    fun restoreBubblePosition() {
        val position = savedPosition()
        if (!canResolveBounds()) {
            return
        }

        dockSide = when {
            position == null -> dockSide
            position.horizontalFraction < 0.5f -> DockSide.LEFT
            else -> DockSide.RIGHT
        }

        val minY = contentRoot.paddingTop.toFloat()
        val maxY = (contentRoot.height - contentRoot.paddingBottom - bubble.height).toFloat().coerceAtLeast(minY)
        val rangeY = (maxY - minY).coerceAtLeast(0f)
        val targetY = if (position == null) {
            bubble.y.coerceIn(minY, maxY)
        } else {
            minY + rangeY * position.verticalFraction
        }

        moveBubbleTo(
            targetX = resolveExposedBubbleX(dockSide),
            targetY = targetY
        )
        alignBubbleToDockState(animated = false)
    }

    fun persistBubblePosition() {
        if (!canResolveBounds()) {
            return
        }

        val minY = contentRoot.paddingTop.toFloat()
        val maxY = (contentRoot.height - contentRoot.paddingBottom - bubble.height).toFloat().coerceAtLeast(minY)
        val rangeY = maxY - minY

        savePosition(
            FloatingLogBubblePosition(
                horizontalFraction = if (dockSide == DockSide.LEFT) 0f else 1f,
                verticalFraction = if (rangeY <= 0f) 1f else ((bubble.y - minY) / rangeY).coerceIn(0f, 1f)
            )
        )
    }

    fun repositionPanel(onPositioned: (() -> Unit)? = null) {
        panel.post {
            if (!panel.isVisible || contentRoot.width <= 0 || contentRoot.height <= 0) {
                return@post
            }
            val panelSize = updatePanelLayout() ?: return@post
            if (panelSize.layoutChanged) {
                panel.post {
                    repositionPanel(onPositioned)
                }
                return@post
            }
            if (bubble.width <= 0 || bubble.height <= 0) {
                return@post
            }

            val horizontalInset = panelHorizontalMarginPx() / 2f
            val verticalInset = panelVerticalMarginPx() / 2f
            val minX = contentRoot.paddingLeft + horizontalInset
            val maxX = (contentRoot.width - contentRoot.paddingRight - panelSize.width - horizontalInset).toFloat().coerceAtLeast(minX)
            val minY = contentRoot.paddingTop + verticalInset
            val maxY = (contentRoot.height - contentRoot.paddingBottom - panelSize.height - verticalInset).toFloat().coerceAtLeast(minY)

            val preferredX = bubble.x + (bubble.width - panelSize.width) / 2f
            val preferredAboveY = bubble.y - panelSize.height - panelGapPx()
            val preferredBelowY = bubble.y + bubble.height + panelGapPx()
            val resolvedY = when {
                preferredAboveY >= minY -> preferredAboveY
                preferredBelowY <= maxY -> preferredBelowY
                else -> maxY
            }

            panel.x = preferredX.coerceIn(minX, maxX)
            panel.y = resolvedY.coerceIn(minY, maxY)
            onPositioned?.invoke()
        }
    }

    private fun updatePanelLayout(): PanelSize? {
        if (contentRoot.width <= 0 || contentRoot.height <= 0) {
            return null
        }

        val horizontalMargin = panelHorizontalMarginPx()
        val verticalMargin = panelVerticalMarginPx()
        val availableWidth = (contentRoot.width - contentRoot.paddingLeft - contentRoot.paddingRight - horizontalMargin).coerceAtLeast(0)
        val availableHeight = (contentRoot.height - contentRoot.paddingTop - contentRoot.paddingBottom - verticalMargin).coerceAtLeast(0)
        val targetWidth = panelWidthPx().coerceAtMost(availableWidth)
        val targetHeight = panelHeightPx().coerceAtMost(availableHeight)
        if (targetWidth <= 0 || targetHeight <= 0) {
            return null
        }

        val layoutParams = panel.layoutParams
        var layoutChanged = false
        if (layoutParams.width != targetWidth || layoutParams.height != targetHeight) {
            layoutParams.width = targetWidth
            layoutParams.height = targetHeight
            panel.layoutParams = layoutParams
            layoutChanged = true
        }
        return PanelSize(width = targetWidth, height = targetHeight, layoutChanged = layoutChanged)
    }

    private fun animateBubbleX(
        targetX: Float,
        animated: Boolean,
        durationMs: Long,
        interpolator: OvershootInterpolator,
        endAction: (() -> Unit)?
    ) {
        if (bubble.width <= 0 || contentRoot.width <= 0) {
            bubble.x = targetX
            endAction?.invoke()
            return
        }

        bubble.animate().cancel()
        if (!animated || abs(bubble.x - targetX) < 1f) {
            bubble.x = targetX
            endAction?.invoke()
            return
        }

        bubble.animate()
            .x(targetX)
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .withEndAction {
                endAction?.invoke()
            }
            .start()
    }

    private fun resolveDockedBubbleX(side: DockSide): Float {
        val minX = contentRoot.paddingLeft.toFloat()
        val maxX = (contentRoot.width - contentRoot.paddingRight - bubble.width).toFloat().coerceAtLeast(minX)
        val hiddenWidthPx = (bubble.width / 2f).coerceAtLeast(1f)
        return when (side) {
            DockSide.LEFT -> minX - hiddenWidthPx
            DockSide.RIGHT -> maxX + hiddenWidthPx
        }
    }

    private fun resolveExposedBubbleX(side: DockSide): Float {
        val minX = contentRoot.paddingLeft.toFloat()
        val maxX = (contentRoot.width - contentRoot.paddingRight - bubble.width).toFloat().coerceAtLeast(minX)
        return when (side) {
            DockSide.LEFT -> minX
            DockSide.RIGHT -> maxX
        }
    }

    private fun canResolveBounds(): Boolean {
        return contentRoot.width > 0 &&
            contentRoot.height > 0 &&
            bubble.width > 0 &&
            bubble.height > 0
    }
}
