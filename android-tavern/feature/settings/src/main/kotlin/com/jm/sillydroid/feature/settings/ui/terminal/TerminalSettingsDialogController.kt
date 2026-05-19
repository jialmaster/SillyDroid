package com.jm.sillydroid.feature.settings.ui.terminal

import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.jm.sillydroid.core.model.settings.TerminalFontSizeOptions
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.feature.settings.R
import kotlin.math.roundToInt

/**
 * 终端设置弹窗只承接终端自身配置，避免把字号这类宿主终端参数再次塞回通用设置页里混在一起。
 * 当前先收口字号调节；后续若继续补终端专属项，也继续挂在这一个独立入口下。
 */
class TerminalSettingsDialogController(
    private val activity: AppCompatActivity,
    private val hostPreferencesRepository: HostPreferencesRepository,
    private val onTerminalFontSizeChanged: (Int) -> Unit,
    private val onTerminalCursorBlinkChanged: (Boolean) -> Unit,
    private val onTerminalExtraKeysChanged: (Boolean) -> Unit
) {
    fun show() {
        val contentView = activity.layoutInflater.inflate(R.layout.dialog_terminal_settings, null)
        val fontSizeValueView = contentView.findViewById<TextView>(R.id.terminalSettingsFontSizeValue)
        val fontSizeSlider = contentView.findViewById<Slider>(R.id.terminalSettingsFontSizeSlider)
        val previewView = contentView.findViewById<TextView>(R.id.terminalSettingsPreview)
        val resetButton = contentView.findViewById<MaterialButton>(R.id.terminalSettingsResetButton)
        val cursorBlinkSwitch = contentView.findViewById<MaterialSwitch>(R.id.terminalSettingsCursorBlinkSwitch)
        val extraKeysSwitch = contentView.findViewById<MaterialSwitch>(R.id.terminalSettingsExtraKeysSwitch)
        var currentFontSizePx = TerminalFontSizeOptions.sanitize(hostPreferencesRepository.terminalFontSizePx)

        // 预览必须和外面的 TerminalView 用同一套像素字号与等宽字体，
        // 否则 TextView 默认按 sp/比例字体渲染时，会看起来比真实终端大很多。
        fun render(fontSizePx: Int) {
            fontSizeValueView.text = activity.getString(
                R.string.bootstrap_settings_terminal_settings_font_size_value,
                fontSizePx
            )
            if (fontSizeSlider.value.roundToInt() != fontSizePx) {
                fontSizeSlider.value = fontSizePx.toFloat()
            }
            previewView.typeface = Typeface.MONOSPACE
            previewView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx.toFloat())
        }

        fun persistFontSize(fontSizePx: Int) {
            val sanitizedFontSizePx = TerminalFontSizeOptions.sanitize(fontSizePx)
            if (hostPreferencesRepository.terminalFontSizePx != sanitizedFontSizePx) {
                hostPreferencesRepository.terminalFontSizePx = sanitizedFontSizePx
            }
            onTerminalFontSizeChanged(sanitizedFontSizePx)
            render(sanitizedFontSizePx)
        }

        fontSizeSlider.valueFrom = TerminalFontSizeOptions.MIN_PX.toFloat()
        fontSizeSlider.valueTo = TerminalFontSizeOptions.MAX_PX.toFloat()
        fontSizeSlider.stepSize = 1f
        cursorBlinkSwitch.isChecked = hostPreferencesRepository.terminalCursorBlinkEnabled
        extraKeysSwitch.isChecked = hostPreferencesRepository.terminalExtraKeysEnabled
        render(currentFontSizePx)

        fontSizeSlider.addOnChangeListener { _, value, _ ->
            val nextFontSizePx = value.roundToInt()
            if (nextFontSizePx == currentFontSizePx) {
                return@addOnChangeListener
            }
            currentFontSizePx = nextFontSizePx
            persistFontSize(currentFontSizePx)
        }
        resetButton.setOnClickListener {
            val defaultFontSizePx = TerminalFontSizeOptions.DEFAULT_PX
            if (currentFontSizePx == defaultFontSizePx) {
                return@setOnClickListener
            }
            currentFontSizePx = defaultFontSizePx
            persistFontSize(currentFontSizePx)
        }
        cursorBlinkSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (hostPreferencesRepository.terminalCursorBlinkEnabled != isChecked) {
                hostPreferencesRepository.terminalCursorBlinkEnabled = isChecked
            }
            onTerminalCursorBlinkChanged(isChecked)
        }
        extraKeysSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (hostPreferencesRepository.terminalExtraKeysEnabled != isChecked) {
                hostPreferencesRepository.terminalExtraKeysEnabled = isChecked
            }
            onTerminalExtraKeysChanged(isChecked)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_terminal_settings_title)
            .setView(contentView)
            .setPositiveButton(R.string.bootstrap_settings_terminal_settings_close, null)
            .show()
    }
}
