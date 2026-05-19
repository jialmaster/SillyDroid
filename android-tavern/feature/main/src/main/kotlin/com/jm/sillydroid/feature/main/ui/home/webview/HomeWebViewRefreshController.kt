package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import java.util.Locale
import org.json.JSONTokener

/**
 * 下拉刷新改回 Android 宿主自己接管：
 * 1. 只允许“顶部 1/3 起手”的手势进入下拉刷新判定，正文下半区完全不做 DOM 命中链判断；
 * 2. 命中顶部区域后，仅在 ACTION_DOWN 注入一次轻量脚本判断当前触点链是否还能向上滚动；
 * 3. 真正的提示动画、阈值判定和 reload 触发都在宿主侧完成，不再依赖 SwipeRefreshLayout 抢手势。
 */
class HomeWebViewRefreshController(
    private val refreshContainer: View,
    private val webViewProvider: () -> WebView,
    private val pullRefreshHintViews: PullRefreshHintViews,
    private val bootstrapOverlay: View,
    private val pullRefreshEnabled: () -> Boolean,
    private val pullGestureRefreshing: () -> Boolean,
    private val setPullGestureRefreshing: (Boolean) -> Unit,
    private val imeVisible: () -> Boolean,
    private val reloadTracer: WebReloadTracer,
    private val diagnosticSink: (String) -> Unit = {}
) {
    private val webView: WebView
        get() = webViewProvider()

    private val triggerZonePolicy = PullRefreshTriggerZonePolicy()
    private var pullRefreshArcDrawable: PullTopArcDrawable? = null

    private var pullGestureStartY = 0f
    private var gestureStartedInTriggerZone = false
    private var isPullGestureTracking = false
    private var isPullGestureArmed = false
    private var isTouchChainCanScrollUp = false
    private var isConfigured = false
    private var diagnosticSerial = 0L

    private val pullRefreshTriggerDistancePx by lazy {
        192f * refreshContainer.resources.displayMetrics.density
    }
    private val pullRefreshHintOffsetPx by lazy {
        20f * refreshContainer.resources.displayMetrics.density
    }

    fun configure(backgroundColor: Int) {
        refreshContainer.isEnabled = false
        refreshContainer.setBackgroundColor(backgroundColor)
        webView.setBackgroundColor(backgroundColor)
        pullRefreshArcDrawable = PullTopArcDrawable().also { drawable ->
            pullRefreshHintViews.arc.background = drawable
        }
        // 下拉刷新只由宿主自己的手势链管理，WebView 继续照常接收事件，不拦截页面默认行为。
        webView.setOnTouchListener { _, event ->
            handlePullRefreshTouch(event)
            false
        }
        isConfigured = true
        updateEnabled()
    }

    fun canHandlePullRefreshGesture(): Boolean {
        val currentWebView = webView
        return refreshContainer.isVisible &&
            currentWebView.isVisible &&
            pullRefreshEnabled() &&
            !bootstrapOverlay.isVisible &&
            !pullGestureRefreshing() &&
            !imeVisible()
    }

    fun updateEnabled() {
        refreshContainer.isEnabled = canHandlePullRefreshGesture()
        if (!refreshContainer.isEnabled && isConfigured) {
            reset()
        }
    }

    fun reset() {
        pullGestureStartY = 0f
        gestureStartedInTriggerZone = false
        isPullGestureTracking = false
        isPullGestureArmed = false
        isTouchChainCanScrollUp = false
        updatePullRefreshHint(progress = 0f, armed = false, dragging = false)
    }

    fun reload(source: String): Boolean {
        val currentWebView = webView
        if (!currentWebView.isVisible || bootstrapOverlay.isVisible) {
            reloadTracer.log(
                phase = "reload_blocked",
                extra = "webViewVisible=${currentWebView.isVisible},overlayVisible=${bootstrapOverlay.isVisible}"
            )
            return false
        }

        reloadTracer.beginIfSourceChanged(source)
        reloadTracer.log(phase = "reload_requested", url = currentWebView.url)
        currentWebView.reload()
        reloadTracer.log(phase = "reload_dispatched", url = currentWebView.url)
        return true
    }

    private fun handlePullRefreshTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> handleActionEnd(event)
        }
    }

    private fun handleActionDown(event: MotionEvent) {
        if (!canHandlePullRefreshGesture()) {
            reset()
            return
        }

        gestureStartedInTriggerZone = triggerZonePolicy.canStartGesture(
            initialDownY = event.y,
            containerHeight = refreshContainer.height
        )
        if (!gestureStartedInTriggerZone) {
            // 顶部 1/3 之外的拖动完全不进入 DOM 命中链判断，降低 WebView 注入压力。
            reset()
            return
        }

        pullGestureStartY = event.y
        isPullGestureTracking = true
        isPullGestureArmed = false
        // 默认先判为“本次触点链可能会消费拖动”，等顶部区域的 DOM 命中链结果回来了再放行。
        isTouchChainCanScrollUp = true
        updatePullRefreshHint(progress = 0f, armed = false, dragging = false)
        detectTouchChainCanScrollUp(rawX = event.x, rawY = event.y)
    }

    private fun handleActionMove(event: MotionEvent) {
        if (!isPullGestureTracking || !gestureStartedInTriggerZone) {
            return
        }

        val currentWebView = webView
        val canPull = canHandlePullRefreshGesture() &&
            !currentWebView.canScrollVertically(-1) &&
            !isTouchChainCanScrollUp
        val dragDistance = (event.y - pullGestureStartY).coerceAtLeast(0f)
        if (!canPull || dragDistance <= 0f) {
            isPullGestureArmed = false
            updatePullRefreshHint(progress = 0f, armed = false, dragging = false)
            return
        }

        val progress = (dragDistance / pullRefreshTriggerDistancePx).coerceAtMost(2.4f)
        isPullGestureArmed = dragDistance >= pullRefreshTriggerDistancePx
        updatePullRefreshHint(progress = progress, armed = isPullGestureArmed, dragging = true)
    }

    private fun handleActionEnd(event: MotionEvent) {
        if (!isPullGestureTracking || !gestureStartedInTriggerZone) {
            reset()
            return
        }

        val currentWebView = webView
        val shouldRefresh = event.actionMasked == MotionEvent.ACTION_UP &&
            isPullGestureArmed &&
            canHandlePullRefreshGesture() &&
            !isTouchChainCanScrollUp &&
            !currentWebView.canScrollVertically(-1)
        reset()
        if (shouldRefresh) {
            triggerPullRefresh()
        }
    }

    private fun triggerPullRefresh() {
        if (!canHandlePullRefreshGesture()) {
            return
        }

        val density = refreshContainer.resources.displayMetrics.density
        setPullGestureRefreshing(true)
        pullRefreshHintViews.container.isVisible = true
        pullRefreshHintViews.arc.isVisible = true
        pullRefreshHintViews.container.alpha = 1f
        pullRefreshHintViews.container.translationY = pullRefreshHintOffsetPx * 0.9f
        pullRefreshHintViews.text.text = refreshContainer.context.getString(
            com.jm.sillydroid.feature.main.R.string.webview_pull_refresh_refreshing
        )
        pullRefreshHintViews.text.alpha = 0f
        pullRefreshHintViews.icon.rotation = 320f
        pullRefreshHintViews.icon.alpha = 1f
        updatePullRefreshArc(
            depthPx = pullRefreshTriggerDistancePx * 0.42f,
            armed = true,
            color = resolvePullRefreshHintColor()
        )
        pullRefreshHintViews.arc.alpha = 0.42f
        pullRefreshHintViews.container.animate()
            .translationY(-18f * density)
            .alpha(0f)
            .setDuration(220)
            .start()
        pullRefreshHintViews.arc.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                pullRefreshHintViews.container.isVisible = false
                pullRefreshHintViews.arc.isVisible = false
            }
            .start()

        reloadTracer.begin(source = "android_pull_gesture")
        reloadTracer.log(phase = "on_refresh")
        if (!reload(source = "android_pull_gesture")) {
            reloadTracer.log(phase = "reload_rejected")
            reloadTracer.clear()
            setPullGestureRefreshing(false)
            updatePullRefreshHint(progress = 0f, armed = false, dragging = false)
        }
    }

    private fun updatePullRefreshHint(progress: Float, armed: Boolean, dragging: Boolean) {
        if (!webView.isVisible || bootstrapOverlay.isVisible) {
            pullRefreshHintViews.container.isVisible = false
            pullRefreshHintViews.arc.isVisible = false
            pullRefreshHintViews.container.alpha = 0f
            pullRefreshHintViews.text.alpha = 0f
            return
        }

        if (!dragging && !pullGestureRefreshing()) {
            pullRefreshHintViews.container.animate()
                .alpha(0f)
                .translationY(-46f * refreshContainer.resources.displayMetrics.density)
                .setDuration(120)
                .withEndAction {
                    pullRefreshHintViews.container.isVisible = false
                    pullRefreshHintViews.arc.isVisible = false
                    pullRefreshHintViews.arc.alpha = 0f
                    pullRefreshHintViews.text.alpha = 0f
                }
                .start()
            return
        }

        val clamped = progress.coerceIn(0f, 1f)
        val pullDistancePx = progress.coerceAtLeast(0f) * pullRefreshTriggerDistancePx
        val cappedDistancePx = pullDistancePx.coerceAtMost(pullRefreshTriggerDistancePx)
        val extraDistancePx = (pullDistancePx - pullRefreshTriggerDistancePx).coerceAtLeast(0f)
        val tensionPercent = (extraDistancePx / (pullRefreshTriggerDistancePx * 2.4f)).coerceIn(0f, 1f)
        val tensionMovePx = pullRefreshTriggerDistancePx *
            (tensionPercent - (tensionPercent * tensionPercent) / 2f) * 1.95f
        val visualOffsetPx = cappedDistancePx + tensionMovePx

        pullRefreshHintViews.container.isVisible = true
        pullRefreshHintViews.arc.isVisible = true
        pullRefreshHintViews.container.alpha = (0.35f + clamped * 0.65f).coerceIn(0f, 1f)
        pullRefreshHintViews.text.text = refreshContainer.context.getString(
            if (armed) {
                com.jm.sillydroid.feature.main.R.string.webview_pull_refresh_release
            } else {
                com.jm.sillydroid.feature.main.R.string.webview_pull_refresh_pull
            }
        )

        val iconBaseOffscreenY = -56f * refreshContainer.resources.displayMetrics.density
        pullRefreshHintViews.container.translationY = iconBaseOffscreenY + visualOffsetPx * 0.9f

        updatePullRefreshArc(
            depthPx = visualOffsetPx * 0.48f,
            armed = armed,
            color = resolvePullRefreshHintColor()
        )
        val arcAlphaProgress = (visualOffsetPx / (pullRefreshTriggerDistancePx * 1.35f)).coerceIn(0f, 1f)
        pullRefreshHintViews.arc.alpha = arcAlphaProgress * 0.42f

        pullRefreshHintViews.text.alpha = if (armed) {
            ((progress - 0.92f) / 0.35f).coerceIn(0f, 1f)
        } else {
            0f
        }
        val rotationProgress = clamped + tensionPercent * 0.9f
        pullRefreshHintViews.icon.rotation = 320f * rotationProgress
        pullRefreshHintViews.icon.alpha = if (armed) {
            1f
        } else {
            (0.38f + 0.52f * clamped).coerceIn(0f, 1f)
        }
    }

    private fun updatePullRefreshArc(depthPx: Float, armed: Boolean, color: Int) {
        pullRefreshArcDrawable?.update(depthPx = depthPx, armed = armed, color = color)
    }

    private fun resolvePullRefreshHintColor(): Int {
        return runCatching {
            Color.parseColor(if (isNightModeEnabled()) "#E5E7EB" else "#374151")
        }.getOrDefault(0xFF374151.toInt())
    }

    private fun isNightModeEnabled(): Boolean {
        return (refreshContainer.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun detectTouchChainCanScrollUp(rawX: Float, rawY: Float) {
        val currentWebView = webView
        val diagnosticId = ++diagnosticSerial
        val scriptTemplate = """
            (function(rawX, rawY) {
                try {
                    const NON_TEXT_INPUT_TYPES = new Set([
                        'button',
                        'checkbox',
                        'color',
                        'file',
                        'hidden',
                        'image',
                        'radio',
                        'range',
                        'reset',
                        'submit'
                    ]);
                    const dpr = window.devicePixelRatio || 1;
                    const x = rawX / dpr;
                    const y = rawY / dpr;
                    let node = document.elementFromPoint(x, y);
                    const isEditableElement = function(element) {
                        if (!(element instanceof Element)) return false;
                        if (element instanceof HTMLTextAreaElement) return true;
                        if (element instanceof HTMLInputElement) {
                            const inputType = String(element.type || 'text').toLowerCase();
                            return !NON_TEXT_INPUT_TYPES.has(inputType);
                        }
                        return element.isContentEditable === true;
                    };
                    const excludesNativeVerticalPan = function(touchAction) {
                        if (!touchAction || touchAction === 'auto' || touchAction === 'manipulation') {
                            return false;
                        }
                        if (touchAction === 'none' || touchAction === 'pan-x') {
                            return true;
                        }
                        return touchAction.includes('pan-x') && !touchAction.includes('pan-y');
                    };
                    const describeNode = function(element) {
                        if (!(element instanceof Element)) return null;
                        const style = window.getComputedStyle(element);
                        return {
                            tag: String(element.tagName || '').toLowerCase(),
                            id: String(element.id || ''),
                            cls: String(element.className || '').trim().slice(0, 120),
                            position: String(style?.position || '').toLowerCase(),
                            overflowY: String(style?.overflowY || '').toLowerCase(),
                            touchAction: String(style?.touchAction || '').toLowerCase().trim(),
                            scrollTop: Number(element.scrollTop || 0),
                            scrollHeight: Number(element.scrollHeight || 0),
                            clientHeight: Number(element.clientHeight || 0)
                        };
                    };
                    const chain = [];
                    let consumeReason = 'none';
                    while (node && node !== document.body && node !== document.documentElement) {
                        if (chain.length < 8) {
                            const description = describeNode(node);
                            if (description) chain.push(description);
                        }
                        const style = window.getComputedStyle(node);
                        const overflowY = String(style?.overflowY || '').toLowerCase();
                        const touchAction = String(style?.touchAction || '').toLowerCase().trim();
                        const canScroll = node.scrollHeight > node.clientHeight + 1;
                        const scrollable = overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay';
                        if (canScroll && scrollable && node.scrollTop > 0) {
                            consumeReason = 'scrollable_ancestor_can_scroll_up';
                            return JSON.stringify({ consume: true, reason: consumeReason, chain: chain });
                        }

                        if (isEditableElement(node)) {
                            consumeReason = 'editable_target';
                            return JSON.stringify({ consume: true, reason: consumeReason, chain: chain });
                        }
                        if (excludesNativeVerticalPan(touchAction)) {
                            consumeReason = 'touch_action_blocks_vertical_pan';
                            return JSON.stringify({ consume: true, reason: consumeReason, chain: chain });
                        }

                        node = node.parentElement;
                    }
                    return JSON.stringify({ consume: false, reason: consumeReason, chain: chain });
                } catch (e) {
                    return JSON.stringify({
                        consume: false,
                        reason: 'script_error',
                        error: String(e && e.message ? e.message : e),
                        chain: []
                    });
                }
            })(%1$.3f, %2$.3f);
        """.trimIndent()

        val script = String.format(Locale.US, scriptTemplate, rawX, rawY)
        currentWebView.evaluateJavascript(script) { result ->
            // 只认当前仍在跟踪、且仍然是顶部 1/3 起手的这次手势结果，避免异步回调污染后续拖动。
            if (!isPullGestureTracking || !gestureStartedInTriggerZone) {
                return@evaluateJavascript
            }
            val decoded = decodeEvaluateJavascriptString(result)
            diagnosticSink(
                "event=pull_refresh_probe id=$diagnosticId startY=${pullGestureStartY.toInt()} " +
                    "rawX=${rawX.toInt()} rawY=${rawY.toInt()} probe=${decoded.ifBlank { result }}"
            )
            isTouchChainCanScrollUp = decoded.contains("\"consume\":true")
        }
    }

    private fun decodeEvaluateJavascriptString(result: String?): String {
        if (result.isNullOrBlank() || result == "null") {
            return ""
        }
        return runCatching {
            val value = JSONTokener(result).nextValue()
            if (value is String) value else value.toString()
        }.getOrElse {
            result
        }
    }
}

data class PullRefreshHintViews(
    val container: LinearLayout,
    val arc: View,
    val icon: ImageView,
    val text: TextView
)

private class PullTopArcDrawable : Drawable() {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var color = 0xFF374151.toInt()
    private var depthPx = 0f

    fun update(depthPx: Float, armed: Boolean, color: Int) {
        this.depthPx = depthPx
        this.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) {
            return
        }

        val clampedDepth = depthPx.coerceIn(0f, height * 0.68f)
        paint.color = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))

        path.reset()
        path.moveTo(0f, 0f)
        val depthRatio = (clampedDepth / height).coerceIn(0f, 1f)
        val sideCtrlX = width * (0.05f + 0.11f * depthRatio)
        val sideCtrlY = clampedDepth * (0.82f + 0.14f * depthRatio)
        path.cubicTo(
            sideCtrlX,
            sideCtrlY,
            width - sideCtrlX,
            sideCtrlY,
            width,
            0f
        )
        path.lineTo(0f, 0f)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
