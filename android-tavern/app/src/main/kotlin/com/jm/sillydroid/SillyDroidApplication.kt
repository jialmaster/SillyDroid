package com.jm.sillydroid

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.jm.sillydroid.domain.app.HostFloatingBrowserController
import com.jm.sillydroid.domain.app.HostFloatingBrowserControllerProvider
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.feature.main.diagnostics.formatTrimMemoryLevel
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue
import com.jm.sillydroid.feature.main.floatingbrowser.FloatingBrowserRuntimeProvider
import com.jm.sillydroid.feature.main.floatingbrowser.FloatingBrowserRuntimeState
import com.jm.sillydroid.feature.main.floatingbrowser.FloatingBrowserService

/**
 * App 进程根对象，持有依赖图、崩溃诊断和唯一悬浮浏览器运行时。
 *
 * 允许：保存进程级 browser surface/session；不允许直接持有 Activity、窗口或用户页面内容。
 */
class SillyDroidApplication : Application(),
    SillyDroidAppGraphProvider,
    FloatingBrowserRuntimeProvider,
    HostFloatingBrowserControllerProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingFloatingBrowserShow: Runnable? = null
    lateinit var appGraph: AppGraph
        private set
    override val floatingBrowserRuntime: FloatingBrowserRuntimeState = FloatingBrowserRuntimeState()
    override val hostFloatingBrowserController: HostFloatingBrowserController = ProcessFloatingBrowserController()

    override val sillyDroidAppGraph: SillyDroidAppGraph
        get() = appGraph

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this, onAppForegroundChanged = ::handleAppForegroundChanged)
        appGraph.hostLogRepository.initializeForAppStart()
        appGraph.hostLogRepository.installCrashLogCapture()
        appGraph.hostLogRepository.refreshApplicationExitInfoAsync()
        installReleaseDiagnosticsLifecycleCallbacks()
        recordDetailedHostDiagnostic(
            category = "application",
            body = buildString {
                append("event=on_create")
                append(" process=${normalizeDiagnosticValue(resolveProcessName())}")
                append(" pid=${Process.myPid()}")
                append(" hostVersion=${normalizeDiagnosticValue(appGraph.appUpdateBuildConfig.hostVersion)}")
                append(" buildType=${normalizeDiagnosticValue(BuildConfig.BUILD_TYPE)}")
                append(" sdk=${Build.VERSION.SDK_INT}")
                append(" device=${normalizeDiagnosticValue("${Build.MANUFACTURER} ${Build.MODEL}")}")
            }
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::appGraph.isInitialized) {
            return
        }
        recordDetailedHostDiagnostic(
            category = "memory",
            body = "scope=application event=on_trim_memory level=${formatTrimMemoryLevel(level)} rawLevel=$level"
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!::appGraph.isInitialized) {
            return
        }
        recordDetailedHostDiagnostic(
            category = "memory",
            body = "scope=application event=on_low_memory"
        )
    }

    // 统一在 Application 层登记关键 Activity 生命周期，避免 release 现场只看到“黑屏/回首页”，
    // 却不知道宿主是否刚经历过 Activity 重建、状态保存或销毁。
    private fun installReleaseDiagnosticsLifecycleCallbacks() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    recordActivityLifecycle(
                        activity = activity,
                        event = "on_create",
                        extra = "savedStatePresent=${savedInstanceState != null}"
                    )
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                    recordActivityLifecycle(
                        activity = activity,
                        event = "on_save_instance_state",
                        extra = "bundleEmpty=${outState.isEmpty}"
                    )
                }

                override fun onActivityDestroyed(activity: Activity) {
                    recordActivityLifecycle(
                        activity = activity,
                        event = "on_destroy",
                        extra = "finishing=${activity.isFinishing} changingConfigurations=${activity.isChangingConfigurations}"
                    )
                }
            }
        )
    }

    /** App 前后台边界统一调度悬浮浏览器，覆盖 MainActivity 与设置页等所有 Activity 入口。 */
    private fun handleAppForegroundChanged(inForeground: Boolean) {
        if (inForeground) {
            cancelPendingFloatingBrowserShow()
            FloatingBrowserService.restoreForAppForeground()
        } else {
            scheduleFloatingBrowserShowFromAppBackground()
        }
    }

    /** App 整体进入后台后延迟确认，避开同进程 Activity 切换和系统授权页启动中的瞬时 stop。 */
    private fun scheduleFloatingBrowserShowFromAppBackground() {
        cancelPendingFloatingBrowserShow()
        val showRunnable = Runnable {
            pendingFloatingBrowserShow = null
            showFloatingBrowserIfStillEligible()
        }
        pendingFloatingBrowserShow = showRunnable
        mainHandler.postDelayed(showRunnable, FLOATING_BROWSER_BACKGROUND_SHOW_DELAY_MS)
    }

    /** 取消尚未执行的后台悬浮请求，保证回前台后旧任务不会再把浏览器迁走。 */
    private fun cancelPendingFloatingBrowserShow() {
        pendingFloatingBrowserShow?.let(mainHandler::removeCallbacks)
        pendingFloatingBrowserShow = null
    }

    /** 复核权限、设置、服务状态和抑制令牌后再显示悬浮浏览器。 */
    private fun showFloatingBrowserIfStillEligible() {
        if (appGraph.appForegroundState.isInForeground) {
            return
        }
        if (!appGraph.hostConfigStore.launchWebViewOnReady || !appGraph.hostConfigStore.floatingBrowserEnabled) {
            FloatingBrowserService.stop(applicationContext)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            appGraph.hostConfigStore.floatingBrowserEnabled = false
            recordFloatingBrowserDiagnostic("event=permission_revoked action=disable_feature source=application_background")
            FloatingBrowserService.stop(applicationContext)
            return
        }
        if (floatingBrowserRuntime.suppressionRegistry.isSuppressed()) {
            recordFloatingBrowserDiagnostic(
                "event=show_suppressed source=application_background reasons=${floatingBrowserRuntime.suppressionRegistry.diagnosticReasons()}"
            )
            return
        }
        runCatching { FloatingBrowserService.show(applicationContext) }
            .onFailure { error ->
                recordFloatingBrowserDiagnostic("event=service_show_failed source=application_background error=${error.javaClass.simpleName}")
            }
    }

    /** 在默认宿主日志中记录悬浮浏览器调度异常，便于 release 现场判断为什么没有出球。 */
    private fun recordFloatingBrowserDiagnostic(body: String) {
        if (!::appGraph.isInitialized) {
            return
        }
        appGraph.hostLogRepository.recordHostDiagnostic(category = "floating-browser", body = body)
    }

    private fun recordActivityLifecycle(activity: Activity, event: String, extra: String) {
        recordDetailedHostDiagnostic(
            category = "activity",
            body = buildString {
                append("activity=${normalizeDiagnosticValue(resolveActivityLabel(activity))}")
                append(" event=$event")
                append(" $extra")
            }
        )
    }

    // “调试模式”关闭时，只保留 startup/server/crash 等核心日志；
    // 这些 Activity/WebView 过程诊断属于高频细节，统一在这里收口，避免 release 常态下刷满宿主诊断文件。
    private fun recordDetailedHostDiagnostic(category: String, body: String) {
        if (!appGraph.hostConfigStore.debugDiagnosticsEnabled) {
            return
        }
        appGraph.hostLogRepository.recordHostDiagnostic(category = category, body = body)
    }

    private fun resolveProcessName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            packageName
        }
    }

    private fun resolveActivityLabel(activity: Activity): String {
        return activity::class.java.simpleName
            .ifBlank { activity.javaClass.name }
    }

    /**
     * Application 内部悬浮浏览器控制器。
     *
     * 允许：代理抑制令牌和服务生命周期；不允许创建浏览器会话或直接操作 WindowManager。
     */
    private inner class ProcessFloatingBrowserController : HostFloatingBrowserController {
        /** 系统页面或外部 App 流程期间阻止后台悬浮窗出现。 */
        override fun acquireSuppression(reason: String): AutoCloseable {
            return floatingBrowserRuntime.suppressionRegistry.acquire(reason)
        }

        /** 前台时预启动悬浮服务，后台真正显示时复用该服务实例。 */
        override fun prepare() {
            runCatching { FloatingBrowserService.prepare(applicationContext) }
                .onFailure { error ->
                    recordFloatingBrowserDiagnostic("event=service_prepare_failed source=process_controller error=${error.javaClass.simpleName}")
                }
        }

        /** 关闭悬浮浏览器服务；Node 后端由 StartupCoordinatorService 继续独立管理。 */
        override fun stop() {
            FloatingBrowserService.stop(applicationContext)
        }
    }

    private companion object {
        private const val FLOATING_BROWSER_BACKGROUND_SHOW_DELAY_MS = 250L
    }
}
