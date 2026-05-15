package com.jm.sillydroid.feature.main.ui.home.webview

import android.os.SystemClock
import android.util.Log

class WebReloadTracer(
    private val logTag: String
) {
    private data class Trace(
        val id: Long,
        val source: String,
        val startedAtElapsedMillis: Long
    )

    private var nextTraceId = 0L
    private var activeTrace: Trace? = null

    fun begin(source: String) {
        activeTrace = Trace(
            id = ++nextTraceId,
            source = source,
            startedAtElapsedMillis = SystemClock.elapsedRealtime()
        )
    }

    fun beginIfSourceChanged(source: String) {
        if (activeTrace?.source != source) {
            begin(source)
        }
    }

    fun log(phase: String, url: String? = null, extra: String? = null) {
        val trace = activeTrace ?: return
        val elapsedMillis = SystemClock.elapsedRealtime() - trace.startedAtElapsedMillis
        val message = buildString {
            append("PullRefreshTrace")
            append(" id=")
            append(trace.id)
            append(" source=")
            append(trace.source)
            append(" phase=")
            append(phase)
            append(" elapsedMs=")
            append(elapsedMillis)
            if (!url.isNullOrBlank()) {
                append(" url=")
                append(url)
            }
            if (!extra.isNullOrBlank()) {
                append(" extra=")
                append(extra)
            }
        }
        Log.d(logTag, message)
    }

    fun clear() {
        activeTrace = null
    }
}
