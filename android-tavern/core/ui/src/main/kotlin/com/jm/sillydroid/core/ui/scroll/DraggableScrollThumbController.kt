package com.jm.sillydroid.core.ui.scroll

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.widget.NestedScrollView

/**
 * Android 原生 ScrollView / NestedScrollView 的系统滚动条只能显示当前位置，不能像桌面端那样直接拖拽。
 * 这里统一补一层可拖拽 thumb，让日志面板既保留自动滚动，又支持用户手动拖动定位。
 */
class DraggableScrollThumbController(
    private val scrollView: NestedScrollView,
    private val thumbView: View,
    private val minThumbHeightPx: Int
) : AutoCloseable {

    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
        syncThumbPosition()
    }
    private val layoutChangedListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        syncThumbPosition()
    }
    private var configured = false
    private var dragTouchOffsetY = 0f
    private var baseTopMargin = 0
    private var baseBottomMargin = 0

    fun configure() {
        if (configured) {
            syncThumbPosition()
            return
        }

        configured = true
        val layoutParams = thumbView.layoutParams as? ViewGroup.MarginLayoutParams
        baseTopMargin = layoutParams?.topMargin ?: 0
        baseBottomMargin = layoutParams?.bottomMargin ?: 0

        scrollView.viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
        scrollView.addOnLayoutChangeListener(layoutChangedListener)
        scrollView.getChildAt(0)?.addOnLayoutChangeListener(layoutChangedListener)
        thumbView.addOnLayoutChangeListener(layoutChangedListener)
        thumbView.setOnTouchListener(::handleThumbTouch)
        thumbView.post(::syncThumbPosition)
    }

    override fun close() {
        if (!configured) {
            return
        }

        configured = false
        if (scrollView.viewTreeObserver.isAlive) {
            scrollView.viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        }
        scrollView.removeOnLayoutChangeListener(layoutChangedListener)
        scrollView.getChildAt(0)?.removeOnLayoutChangeListener(layoutChangedListener)
        thumbView.removeOnLayoutChangeListener(layoutChangedListener)
        thumbView.setOnTouchListener(null)
    }

    private fun handleThumbTouch(view: View, event: MotionEvent): Boolean {
        val metrics = resolveMetrics() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragTouchOffsetY = event.y
                view.isPressed = true
                view.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val hostLocation = IntArray(2)
                metrics.hostView.getLocationOnScreen(hostLocation)
                val hostTopOnScreen = hostLocation[1] + baseTopMargin
                val desiredTop = event.rawY - hostTopOnScreen - dragTouchOffsetY
                val fraction = if (metrics.thumbTravelPx <= 0) {
                    0f
                } else {
                    (desiredTop / metrics.thumbTravelPx.toFloat()).coerceIn(0f, 1f)
                }

                scrollView.scrollTo(0, (metrics.maxScrollY * fraction).toInt())
                syncThumbPosition()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun syncThumbPosition() {
        val metrics = resolveMetrics() ?: run {
            thumbView.visibility = View.GONE
            thumbView.translationY = 0f
            return
        }

        val layoutParams = thumbView.layoutParams
        if (layoutParams.height != metrics.thumbHeightPx) {
            layoutParams.height = metrics.thumbHeightPx
            thumbView.layoutParams = layoutParams
        }

        val fraction = if (metrics.maxScrollY <= 0) {
            0f
        } else {
            scrollView.scrollY.coerceAtLeast(0).toFloat() / metrics.maxScrollY.toFloat()
        }
        thumbView.translationY = metrics.thumbTravelPx * fraction
        thumbView.visibility = View.VISIBLE
    }

    private fun resolveMetrics(): ThumbMetrics? {
        val contentView = scrollView.getChildAt(0) ?: return null
        val hostView = thumbView.parent as? ViewGroup ?: return null
        val viewportHeight = (scrollView.height - scrollView.paddingTop - scrollView.paddingBottom).coerceAtLeast(0)
        val contentHeight = contentView.height
        val maxScrollY = (contentView.height - scrollView.height).coerceAtLeast(0)
        if (viewportHeight <= 0 || contentHeight <= viewportHeight || maxScrollY <= 0 || hostView.height <= 0) {
            return null
        }

        val trackHeight = (hostView.height - baseTopMargin - baseBottomMargin).coerceAtLeast(minThumbHeightPx)
        val rawThumbHeight = (trackHeight.toFloat() * viewportHeight.toFloat() / contentHeight.toFloat()).toInt()
        val thumbHeightPx = rawThumbHeight.coerceIn(minThumbHeightPx, trackHeight)
        val thumbTravelPx = (trackHeight - thumbHeightPx).coerceAtLeast(0)
        return ThumbMetrics(
            hostView = hostView,
            maxScrollY = maxScrollY,
            thumbHeightPx = thumbHeightPx,
            thumbTravelPx = thumbTravelPx
        )
    }

    private data class ThumbMetrics(
        val hostView: ViewGroup,
        val maxScrollY: Int,
        val thumbHeightPx: Int,
        val thumbTravelPx: Int
    )
}
