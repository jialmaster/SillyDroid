package com.jm.sillydroid.feature.main.ui.home.notification

import com.jm.sillydroid.core.model.notification.HostNotificationAction
import com.jm.sillydroid.core.model.notification.HostNotificationChannel
import com.jm.sillydroid.core.model.notification.HostNotificationKind
import com.jm.sillydroid.core.model.notification.HostNotificationSpec
import com.jm.sillydroid.core.model.notification.HostNotificationTapSpec
import com.jm.sillydroid.domain.notification.HostNotificationService
import com.jm.sillydroid.feature.main.model.notification.SystemNotificationRequest
import org.json.JSONObject

/**
 * WebView 通知桥只负责解析 payload 并转成统一通知 spec；
 * channel、builder、点击行为、notify/cancel 全部收敛到 HostNotificationService。
 */
open class SystemNotificationController(
    private val hostNotificationService: HostNotificationService,
    private val smallIconResId: Int
) {
    open fun canPost(): Boolean {
        return hostNotificationService.canPostNotifications()
    }

    open fun permissionState(): String {
        return if (canPost()) "granted" else "default"
    }

    open fun parseRequest(payload: String?): SystemNotificationRequest? {
        return parseSystemNotificationRequestPayload(payload)
    }

    open fun show(request: SystemNotificationRequest): Boolean {
        val notificationKey = resolveNotificationKey(request)
        if (notificationKey.isBlank()) {
            return false
        }

        hostNotificationService.post(
            HostNotificationSpec(
                notificationKey = notificationKey,
                kind = HostNotificationKind.WEB_MESSAGE,
                channel = HostNotificationChannel.MESSAGE_ALERTS,
                title = request.title,
                body = request.body,
                ongoing = false,
                autoCancel = true,
                tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_MAIN),
                smallIconResId = smallIconResId
            )
        )
        return true
    }

    private fun resolveNotificationKey(request: SystemNotificationRequest): String {
        return request.tag.ifBlank {
            request.notificationId.ifBlank { request.title }
        }.trim()
    }
}

internal fun parseSystemNotificationRequestPayload(payload: String?): SystemNotificationRequest? {
    val normalizedPayload = payload?.trim().orEmpty()
    if (normalizedPayload.isBlank()) {
        return null
    }

    val json = JSONObject(normalizedPayload)
    val notificationId = json.optString("notificationId").trim()
    val title = json.optString("title").trim().ifBlank { "通知" }
    val body = json.optString("body").trim()
    val tag = json.optString("tag").trim()
    if (body.isBlank()) {
        return null
    }

    return SystemNotificationRequest(
        notificationId = notificationId,
        title = title,
        body = body,
        tag = tag
    )
}
