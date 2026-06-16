package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue

/**
 * 系统 WebView provider 初始化失败时的显式失败态 host。
 *
 * 这条路径只阻止 Activity 被 WebView 构造异常打崩，不伪装浏览器已经可用；
 * 用户可以打开设置切换 GeckoView，或临时用外部浏览器访问本地服务。
 */
class UnavailableBrowserHost(
    private val activity: AppCompatActivity,
    override val browserEngine: BrowserEngine,
    private val unavailableReason: Throwable,
    private val openSettings: () -> Unit,
    private val restoreHostSystemBarAppearance: () -> Unit = {},
    private val criticalHostDiagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> },
) : TavernBrowserHost {
    private val browserFrame: ViewGroup = activity.findViewById(R.id.webViewRefreshLayout)
    private val unavailableView: View = buildUnavailableView()
    private var lastLocalUrl: String = ""

    override val browserContainer: View
        get() = browserFrame

    override val browserSurface: View
        get() = unavailableView

    override fun currentBrowserRuntimeInfo(): BrowserRuntimeInfo {
        return BrowserRuntimeInfo(
            engine = browserEngine,
            runtimeName = "Unavailable",
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
        if (unavailableView.parent == null) {
            browserFrame.addView(unavailableView)
        }
        browserFrame.setBackgroundColor(ContextCompat.getColor(activity, R.color.tavern_webview_background))
        recordUnavailableDiagnostic(event = "configured")
    }

    override fun setBrowserZoomPercent(percent: Int): Boolean = false

    override fun setBrowserPageZoomPercent(percent: Int): Boolean = false

    override fun showBrowser(baseUrl: String) {
        lastLocalUrl = baseUrl
        browserFrame.isVisible = true
        unavailableView.isVisible = true
        recordUnavailableDiagnostic(event = "show_browser_blocked", extra = "localUrl=${normalizeDiagnosticValue(baseUrl)}")
    }

    override fun hideForBootstrapRestart() {
        browserFrame.isVisible = false
        unavailableView.isVisible = false
        restoreHostSystemBarAppearance()
    }

    override fun reloadTavernUiIfPossible(snapshot: BootstrapSessionSnapshot) {
        if (snapshot.localUrl.isNotBlank()) {
            lastLocalUrl = snapshot.localUrl
        }
        recordUnavailableDiagnostic(event = "reload_blocked", extra = "snapshotReady=${snapshot.isReady}")
    }

    override fun reloadTavernWebView(source: String): Boolean {
        recordUnavailableDiagnostic(event = "reload_blocked", extra = "source=$source")
        return false
    }

    override fun updateRefreshLayoutEnabled() {
        browserFrame.isEnabled = false
    }

    override fun resetRefreshOnBootstrapEvent() = Unit

    override fun onImeVisibilityChanged(visible: Boolean) = Unit

    override fun onTrimMemory(level: Int) {
        recordUnavailableDiagnostic(event = "on_trim_memory", extra = "level=$level")
    }

    override fun onLowMemory() {
        recordUnavailableDiagnostic(event = "on_low_memory")
    }

    override fun onDestroy() {
        runCatching { (unavailableView.parent as? ViewGroup)?.removeView(unavailableView) }
        recordUnavailableDiagnostic(event = "destroyed")
    }

    override fun openUrlInExternalBrowser(url: String): Boolean {
        val target = url.trim().ifBlank { lastLocalUrl }
        if (target.isBlank()) {
            Toast.makeText(activity, R.string.browser_open_external_failed, Toast.LENGTH_SHORT).show()
            return false
        }
        return launchExternalBrowser(Uri.parse(target))
    }

    override fun openCurrentPageInExternalBrowser(): Boolean {
        return openUrlInExternalBrowser(lastLocalUrl)
    }

    private fun buildUnavailableView(): View {
        return LinearLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(
                activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_3xl),
                activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_3xl),
                activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_3xl),
                activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_3xl)
            )
            setBackgroundColor(ContextCompat.getColor(activity, R.color.tavern_webview_background))

            addView(TextView(activity).apply {
                text = activity.getString(R.string.browser_unavailable_title)
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                setTextColor(ContextCompat.getColor(activity, R.color.bootstrap_overlay_title))
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.browser_unavailable_message)
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
                setTextColor(ContextCompat.getColor(activity, R.color.bootstrap_overlay_body))
                setPadding(0, activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_md), 0, 0)
            })
            addView(MaterialButton(activity).apply {
                text = activity.getString(R.string.browser_unavailable_open_settings)
                setPadding(
                    activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_md),
                    paddingTop,
                    activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_md),
                    paddingBottom
                )
                setOnClickListener { openSettings() }
            })
            addView(MaterialButton(activity).apply {
                text = activity.getString(R.string.browser_unavailable_open_external)
                setPadding(
                    activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_md),
                    paddingTop,
                    activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_md),
                    paddingBottom
                )
                setOnClickListener { openCurrentPageInExternalBrowser() }
            })
        }
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

    private fun recordUnavailableDiagnostic(event: String, extra: String = "") {
        criticalHostDiagnosticSink.record(
            "browser",
            buildString {
                append("event=browser_unavailable_$event")
                append(" engine=${browserEngine.name}")
                append(" reason=${normalizeDiagnosticValue(unavailableReason.javaClass.name)}")
                append(" message=${normalizeDiagnosticValue(unavailableReason.message)}")
                if (extra.isNotBlank()) {
                    append(' ')
                    append(extra)
                }
            }
        )
    }
}
