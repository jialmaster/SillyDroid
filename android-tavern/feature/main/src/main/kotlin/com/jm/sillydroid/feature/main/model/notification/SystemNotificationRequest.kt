package com.jm.sillydroid.feature.main.model.notification

data class SystemNotificationRequest(
    val notificationId: String,
    val title: String,
    val body: String,
    val tag: String
)
