package com.jm.sillydroid.domain.bootstrap

interface RuntimeConfigRepository {
    val defaultServicePort: Int
    val systemNotificationChannelId: String
    val notificationId: Int
    fun localServiceUrl(): String
    fun readinessUrl(): String = "${localServiceUrl()}/"
}
