package com.jm.sillydroid

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.jm.sillydroid.domain.runtime.HostAppForegroundState
import java.util.concurrent.atomic.AtomicInteger

/**
 * 记录 App 进程内 started Activity 数量，并只在 0/1 边界上发出前后台变化。
 *
 * 允许：把多个 Activity 视为同一个 App 前台会话；不允许把单个 Activity 的 stop 当作 App 后台。
 */
class ApplicationForegroundTransitionCounter(
    private val onForegroundChanged: (Boolean) -> Unit = {}
) {
    private val startedActivityCount = AtomicInteger(0)

    /** 当前进程是否至少有一个 started Activity。 */
    val isInForeground: Boolean
        get() = startedActivityCount.get() > 0

    /** Activity started 时递增计数；只有 0 -> 1 才代表 App 回到前台。 */
    fun onActivityStarted() {
        if (startedActivityCount.getAndIncrement() == 0) {
            onForegroundChanged(true)
        }
    }

    /** Activity stopped 时递减计数；只有 1 -> 0 才代表 App 整体离开前台。 */
    fun onActivityStopped() {
        val beforeStop = startedActivityCount.getAndUpdate { current -> (current - 1).coerceAtLeast(0) }
        if (beforeStop == 1) {
            onForegroundChanged(false)
        }
    }
}

/**
 * Application.ActivityLifecycleCallbacks 适配器，供依赖图暴露进程级前后台状态。
 *
 * 允许：接收系统生命周期回调并转给纯计数器；不允许在这里启动窗口、服务或浏览器迁移。
 */
class ApplicationForegroundStateStore(
    onForegroundChanged: (Boolean) -> Unit = {}
) : Application.ActivityLifecycleCallbacks, HostAppForegroundState {
    private val transitionCounter = ApplicationForegroundTransitionCounter(onForegroundChanged)

    override val isInForeground: Boolean
        get() = transitionCounter.isInForeground

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        transitionCounter.onActivityStarted()
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        transitionCounter.onActivityStopped()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
