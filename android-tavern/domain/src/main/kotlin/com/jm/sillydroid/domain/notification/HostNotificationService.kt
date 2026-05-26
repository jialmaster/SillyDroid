package com.jm.sillydroid.domain.notification

import android.app.Notification
import android.app.Service
import com.jm.sillydroid.core.model.notification.HostNotificationSpec

interface HostNotificationService {
    fun ensureChannels()
    fun canPostNotifications(): Boolean
    fun post(spec: HostNotificationSpec): Notification
    fun postForeground(service: Service, spec: HostNotificationSpec): Notification
    fun remove(notificationKey: String)
    fun removeGroup(prefix: String)
    fun buildNotification(spec: HostNotificationSpec): Notification
}
