package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue

/**
 * 纯后台模式下的浏览器 host。
 *
 * 这个实现不创建 WebView/GeckoView 内核，只保留外部浏览器打开能力，
 * 避免后台模式也提前初始化浏览器 provider 或 Gecko runtime。
 */
class DisabledBrowserHost(
    private val activity: AppCompatActivity,
    override val browserEngine: BrowserEngine,
    private val criticalHostDiagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> },
) : TavernBrowserHost {
    private val browserFrame: ViewGroup = activity.findViewById(R.id.webViewRefreshLayout)
    private val placeholderView: View = View(activity)
    private var lastLocalUrl: String = ""

    override val browserContainer: View
        get() = browserFrame

    override val browserSurface: View
        get() = placeholderView

    override fun currentBrowserRuntimeInfo(): BrowserRuntimeInfo {
        return BrowserRuntimeInfo(
            engine = browserEngine,
            runtimeName = "Disabled",
            packageName = "",
            versionName = "",
            versionCode = "",
            coreName = "",
            coreVersion = "",
            coreMajorVersion = 0,
            recommendedCoreMajorVersion = 0,
            outdated = false,
            userAgent = ""
        )
    }

    override fun currentBrowserZoomPercent(): Int = 100

    override fun currentBrowserPageZoomPercent(): Int = 100

    override fun configure() {
        recordDiagnostic(event = "configured")
    }

    override fun setBrowserZoomPercent(percent: Int): Boolean = false

    override fun setBrowserPageZoomPercent(percent: Int): Boolean = false

    override fun showBrowser(baseUrl: String) {
        lastLocalUrl = baseUrl
        browserFrame.isVisible = false
        placeholderView.isVisible = false
        recordDiagnostic(event = "show_browser_blocked", extra = "localUrl=${normalizeDiagnosticValue(baseUrl)}")
    }

    override fun hideForBootstrapRestart() {
        browserFrame.isVisible = false
        placeholderView.isVisible = false
    }

    override fun reloadTavernUiIfPossible(snapshot: BootstrapSessionSnapshot) {
        if (snapshot.localUrl.isNotBlank()) {
            lastLocalUrl = snapshot.localUrl
        }
        recordDiagnostic(event = "reload_blocked", extra = "snapshotReady=${snapshot.isReady}")
    }

    override fun reloadTavernWebView(source: String): Boolean {
        recordDiagnostic(event = "reload_blocked", extra = "source=$source")
        return false
    }

    override fun updateRefreshLayoutEnabled() {
        browserFrame.isEnabled = false
    }

    override fun resetRefreshOnBootstrapEvent() = Unit

    override fun onImeVisibilityChanged(visible: Boolean) = Unit

    override fun onTrimMemory(level: Int) = Unit

    override fun onLowMemory() = Unit

    override fun onDestroy() = Unit

    override fun openUrlInExternalBrowser(url: String): Boolean {
        val target = url.trim().ifBlank { lastLocalUrl }
        if (target.isBlank()) {
            Toast.makeText(activity, R.string.browser_open_external_failed, Toast.LENGTH_SHORT).show()
            return false
        }
        lastLocalUrl = target
        return launchExternalBrowser(Uri.parse(target))
    }

    override fun openCurrentPageInExternalBrowser(): Boolean {
        return openUrlInExternalBrowser(lastLocalUrl)
    }

    private fun launchExternalBrowser(targetUri: Uri): Boolean {
        return try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, targetUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.browser_open_external_failed, Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun recordDiagnostic(event: String, extra: String = "") {
        criticalHostDiagnosticSink.record(
            "browser",
            buildString {
                append("event=browser_disabled_$event")
                append(" engine=${browserEngine.name}")
                if (extra.isNotBlank()) {
                    append(' ')
                    append(extra)
                }
            }
        )
    }
}
