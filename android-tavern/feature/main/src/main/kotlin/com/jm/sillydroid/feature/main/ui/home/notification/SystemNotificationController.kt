package com.jm.sillydroid.feature.main.ui.home.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jm.sillydroid.feature.main.model.notification.SystemNotificationRequest
import org.json.JSONObject
import kotlin.math.absoluteValue

class SystemNotificationController(
    private val context: Context,
    private val channelId: String,
    private val channelTitle: String,
    private val channelDescription: String,
    private val smallIconResId: Int,
    private val launchIntent: Intent,
    private val pendingIntentRequestCode: Int
) {
    private val appContext = context.applicationContext

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            channelId,
            channelTitle,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = channelDescription
        }

        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun permissionState(): String {
        return if (canPost()) "granted" else "default"
    }

    fun parseRequest(payload: String?): SystemNotificationRequest? {
        val normalizedPayload = payload?.trim().orEmpty()
        if (normalizedPayload.isBlank()) {
            return null
        }

        val json = JSONObject(normalizedPayload)
        return SystemNotificationRequest(
            notificationId = json.optString("notificationId").trim(),
            title = json.optString("title").trim().ifBlank { "通知" },
            body = json.optString("body").trim(),
            tag = json.optString("tag").trim()
        )
    }

    fun show(request: SystemNotificationRequest): Boolean {
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(smallIconResId)
            .setContentTitle(request.title)
            .setContentText(request.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.body))
            .setAutoCancel(true)
            .setContentIntent(createContentIntent())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notifyTag = request.tag.ifBlank { request.notificationId }
        NotificationManagerCompat.from(appContext).notify(
            notifyTag.ifBlank { null },
            resolveRequestCode(request),
            notification
        )
        return true
    }

    private fun createContentIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, pendingIntentRequestCode, launchIntent, flags)
    }

    private fun resolveRequestCode(request: SystemNotificationRequest): Int {
        val seed = request.notificationId
            .ifBlank { request.tag }
            .ifBlank { request.title }
            .hashCode()
        return if (seed == Int.MIN_VALUE) 0 else seed.absoluteValue
    }
}
