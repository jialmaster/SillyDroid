package com.jm.sillydroid

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.feature.main.diagnostics.formatTrimMemoryLevel
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue

class SillyDroidApplication : Application(), SillyDroidAppGraphProvider {
    lateinit var appGraph: AppGraph
        private set

    override val sillyDroidAppGraph: SillyDroidAppGraph
        get() = appGraph

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)
        appGraph.hostLogRepository.initializeForAppStart()
        appGraph.hostLogRepository.installCrashLogCapture()
        appGraph.hostLogRepository.refreshApplicationExitInfoAsync()
        installReleaseDiagnosticsLifecycleCallbacks()
        recordHostDiagnostic(
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
        recordHostDiagnostic(
            category = "memory",
            body = "scope=application event=on_trim_memory level=${formatTrimMemoryLevel(level)} rawLevel=$level"
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!::appGraph.isInitialized) {
            return
        }
        recordHostDiagnostic(
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

    private fun recordActivityLifecycle(activity: Activity, event: String, extra: String) {
        recordHostDiagnostic(
            category = "activity",
            body = buildString {
                append("activity=${normalizeDiagnosticValue(resolveActivityLabel(activity))}")
                append(" event=$event")
                append(" $extra")
            }
        )
    }

    private fun recordHostDiagnostic(category: String, body: String) {
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
}
