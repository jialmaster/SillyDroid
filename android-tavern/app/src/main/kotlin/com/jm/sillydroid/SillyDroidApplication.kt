package com.jm.sillydroid

import android.app.Application
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider

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
    }
}
