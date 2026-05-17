package com.jm.sillydroid.feature.settings.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.color.MaterialColors
import com.jm.sillydroid.feature.settings.R

/**
 * 终端快捷键条不复用设置页的大号 MaterialButton。
 * 这里收敛成终端专用紧凑 strip，视觉和交互都尽量贴近 Termux extra keys。
 */
class TerminalExtraKeysStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    private val keyViews = linkedMapOf<TerminalExtraKeyAction, AppCompatTextView>()
    private val contentRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var onActionPressed: ((TerminalExtraKeyAction) -> Unit)? = null
    private var visibleActions: List<TerminalExtraKeyAction> = emptyList()
    private var repeatingAction: TerminalExtraKeyAction? = null
    private var repeatStarted = false
    private var suppressReleaseClickAction: TerminalExtraKeyAction? = null
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val action = repeatingAction ?: return
            repeatStarted = true
            onActionPressed?.invoke(action)
            postDelayed(this, terminalRepeatIntervalMillis)
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        background = AppCompatResources.getDrawable(context, R.drawable.bg_bootstrap_settings_nested_surface_rounded)
        val inset = resources.getDimensionPixelSize(R.dimen.sillydroid_terminal_extra_keys_bar_padding)
        setPadding(inset, inset, inset, inset)

        addView(
            contentRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
    }

    internal fun setOnActionPressedListener(listener: (TerminalExtraKeyAction) -> Unit) {
        onActionPressed = listener
    }

    internal fun render(state: TerminalExtraKeysState, actions: List<TerminalExtraKeyAction>) {
        if (visibleActions != actions) {
            visibleActions = actions.toList()
            rebuildVisibleKeys()
        }
        keyViews[TerminalExtraKeyAction.CTRL]?.isSelected = state.ctrlArmed
        keyViews[TerminalExtraKeyAction.ALT]?.isSelected = state.altArmed
    }

    private fun rebuildVisibleKeys() {
        contentRow.removeAllViews()
        visibleActions.forEachIndexed { index, action ->
            val keyView = keyViews.getOrPut(action) {
                createKeyView(action)
            }
            keyView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) {
                    marginStart = resources.getDimensionPixelSize(R.dimen.sillydroid_terminal_extra_key_spacing)
                }
            }
            contentRow.addView(keyView)
        }
    }

    private fun createKeyView(action: TerminalExtraKeyAction): AppCompatTextView {
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.sillydroid_terminal_extra_key_padding_horizontal)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.sillydroid_terminal_extra_key_padding_vertical)
        val textColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)

        return AppCompatTextView(context).apply {
            text = context.getString(action.labelRes())
            gravity = Gravity.CENTER
            includeFontPadding = false
            isSingleLine = true
            minWidth = resources.getDimensionPixelSize(R.dimen.sillydroid_terminal_extra_key_min_width)
            minHeight = resources.getDimensionPixelSize(R.dimen.sillydroid_terminal_extra_key_min_height)
            background = AppCompatResources.getDrawable(context, R.drawable.bg_bootstrap_settings_terminal_extra_key)
            setTextColor(textColor)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            isClickable = true
            isFocusable = false
            contentDescription = text
            setOnClickListener {
                if (suppressReleaseClickAction == action) {
                    suppressReleaseClickAction = null
                    return@setOnClickListener
                }
                onActionPressed?.invoke(action)
            }
            setOnTouchListener { _, event ->
                if (!action.supportsRepeat()) {
                    return@setOnTouchListener false
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> startRepeating(action)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRepeating(action)
                }
                false
            }
        }.also { keyView ->
            keyViews[action] = keyView
        }
    }

    private fun TerminalExtraKeyAction.labelRes(): Int {
        return when (this) {
            TerminalExtraKeyAction.ESC -> R.string.bootstrap_settings_terminal_shortcut_esc
            TerminalExtraKeyAction.TAB -> R.string.bootstrap_settings_terminal_shortcut_tab
            TerminalExtraKeyAction.CTRL -> R.string.bootstrap_settings_terminal_shortcut_ctrl
            TerminalExtraKeyAction.ALT -> R.string.bootstrap_settings_terminal_shortcut_alt
            TerminalExtraKeyAction.LEFT -> R.string.bootstrap_settings_terminal_shortcut_left
            TerminalExtraKeyAction.DOWN -> R.string.bootstrap_settings_terminal_shortcut_down
            TerminalExtraKeyAction.UP -> R.string.bootstrap_settings_terminal_shortcut_up
            TerminalExtraKeyAction.RIGHT -> R.string.bootstrap_settings_terminal_shortcut_right
            TerminalExtraKeyAction.COPY -> R.string.bootstrap_settings_terminal_shortcut_copy
        }
    }

    /**
     * 选择态方向键必须支持长按连发，才能在手机上连续调整选区；
     * 这里直接在条目自身做 repeat，而不是退回成“用户狂点很多次”的低效交互。
     */
    private fun startRepeating(action: TerminalExtraKeyAction) {
        stopRepeating(action)
        repeatingAction = action
        repeatStarted = false
        postDelayed(repeatRunnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun stopRepeating(action: TerminalExtraKeyAction) {
        removeCallbacks(repeatRunnable)
        if (repeatingAction == action && repeatStarted) {
            suppressReleaseClickAction = action
        }
        repeatingAction = null
        repeatStarted = false
    }

    private fun TerminalExtraKeyAction.supportsRepeat(): Boolean {
        return this == TerminalExtraKeyAction.LEFT ||
            this == TerminalExtraKeyAction.DOWN ||
            this == TerminalExtraKeyAction.UP ||
            this == TerminalExtraKeyAction.RIGHT
    }

    private companion object {
        const val terminalRepeatIntervalMillis = 60L
    }
}
