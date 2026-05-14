package com.jm.sillydroid

import android.app.Application

class SillyDroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HostLogManager.initializeForAppStart(this)
        CrashLogStore.install(this)
        ApplicationExitInfoLogStore.refreshAsync(this)
    }
}
