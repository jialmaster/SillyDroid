package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository

class BootRuntimeConfigRepository(
    private val hostPreferences: HostPreferencesRepository
) : RuntimeConfigRepository {

    override val defaultServicePort: Int
        get() = BootConfig.defaultServicePort

    override val systemNotificationChannelId: String
        get() = BootConfig.systemNotificationChannelId

    override val notificationId: Int
        get() = BootConfig.notificationId

    override fun localServiceUrl(): String {
        return "http://127.0.0.1:${hostPreferences.servicePort}"
    }
}
