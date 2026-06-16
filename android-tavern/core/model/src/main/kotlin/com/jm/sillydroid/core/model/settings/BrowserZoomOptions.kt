package com.jm.sillydroid.core.model.settings

import kotlin.math.roundToInt

object BrowserZoomOptions {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 150
    const val MAX_VIEWPORT_DENSITY_PERCENT = 100
    const val STEP_PERCENT = 5
    const val DEFAULT_PERCENT = 100
    const val DEFAULT_TEXT_ZOOM_PERCENT = 100

    val percentOptions: List<Int> = (MIN_PERCENT..MAX_PERCENT step STEP_PERCENT).toList()
    val viewportDensityPercentOptions: List<Int> = (MIN_PERCENT..MAX_VIEWPORT_DENSITY_PERCENT step STEP_PERCENT).toList()

    fun sanitize(percent: Int): Int {
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        val stepped = MIN_PERCENT + ((clamped - MIN_PERCENT).toFloat() / STEP_PERCENT).roundToInt() * STEP_PERCENT
        return stepped.coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    fun percentFromSliderProgress(progress: Int): Int {
        return sanitize(MIN_PERCENT + progress * STEP_PERCENT)
    }

    fun sanitizeViewportDensity(percent: Int): Int {
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_VIEWPORT_DENSITY_PERCENT)
        val stepped = MIN_PERCENT + ((clamped - MIN_PERCENT).toFloat() / STEP_PERCENT).roundToInt() * STEP_PERCENT
        return stepped.coerceIn(MIN_PERCENT, MAX_VIEWPORT_DENSITY_PERCENT)
    }

    fun viewportDensityPercentFromSliderProgress(progress: Int): Int {
        return sanitizeViewportDensity(MIN_PERCENT + progress * STEP_PERCENT)
    }

    fun sliderProgress(percent: Int): Int {
        return (sanitize(percent) - MIN_PERCENT) / STEP_PERCENT
    }

    fun viewportDensitySliderProgress(percent: Int): Int {
        return (sanitizeViewportDensity(percent) - MIN_PERCENT) / STEP_PERCENT
    }

    fun toZoomFactor(percent: Int): Float {
        return sanitize(percent) / 100f
    }

    fun toViewportDensityFactor(percent: Int): Float {
        return sanitizeViewportDensity(percent) / 100f
    }
}
