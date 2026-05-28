package com.jm.sillydroid

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.jm.sillydroid.domain.runtime.HostAppForegroundState
import java.util.concurrent.atomic.AtomicInteger

class ApplicationForegroundStateStore : Application.ActivityLifecycleCallbacks, HostAppForegroundState {
    private val startedActivityCount = AtomicInteger(0)

    override val isInForeground: Boolean
        get() = startedActivityCount.get() > 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
