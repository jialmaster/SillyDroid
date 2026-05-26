package com.jm.sillydroid

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jm.sillydroid.core.model.notification.HostNotificationAction
import com.jm.sillydroid.core.model.notification.HostNotificationChannel
import com.jm.sillydroid.core.model.notification.HostNotificationKind
import com.jm.sillydroid.core.model.notification.HostNotificationProgress
import com.jm.sillydroid.core.model.notification.HostNotificationSpec
import com.jm.sillydroid.domain.notification.HostNotificationService
import java.io.File

class HostNotificationServiceImpl(
    context: Context
) : HostNotificationService {
    companion object {
        const val foregroundRuntimeChannelId = "foreground-runtime"
        const val messageAlertsChannelId = "message-alerts"
        const val downloadsInstallChannelId = "downloads-install"
        private const val foregroundBootstrapProgressPostIntervalMillis = 1_500L
    }

    private val appContext = context.applicationContext
    private val notificationManagerCompat by lazy { NotificationManagerCompat.from(appContext) }
    private val stateStore by lazy { HostNotificationStateStore(appContext) }
    private val foregroundPostStates = mutableMapOf<String, ForegroundPostState>()

    override fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(
                foregroundRuntimeChannelId,
                appContext.getString(R.string.host_notification_channel_foreground_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.host_notification_channel_foreground_description)
            },
            NotificationChannel(
                messageAlertsChannelId,
                appContext.getString(R.string.host_notification_channel_message_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.host_notification_channel_message_description)
            },
            NotificationChannel(
                downloadsInstallChannelId,
                appContext.getString(R.string.host_notification_channel_downloads_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.host_notification_channel_downloads_description)
            }
        )
        manager.createNotificationChannels(channels)
    }

    override fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun post(spec: HostNotificationSpec): Notification {
        ensureChannels()
        val notification = buildNotification(spec)
        val notificationTarget = resolveNotificationTarget(spec)
        val previousTarget = resolveStoredNotificationTarget(spec.notificationKey)
        if (previousTarget != null && previousTarget != notificationTarget) {
            notificationManagerCompat.cancel(previousTarget.tag, previousTarget.id)
        }
        notificationManagerCompat.notify(notificationTarget.tag, notificationTarget.id, notification)
        stateStore.markActive(spec.notificationKey, notificationTarget.tag, notificationTarget.id)
        return notification
    }

    override fun postForeground(service: Service, spec: HostNotificationSpec): Notification {
        val foregroundBehavior = requireNotNull(spec.foregroundBehavior) {
            "Foreground notifications must declare foregroundBehavior."
        }
        ensureChannels()
        val notification = buildNotification(spec)
        val now = SystemClock.elapsedRealtime()
        if (shouldSkipForegroundPost(spec, now)) {
            return notification
        }
        // 前台常驻通知必须由统一通知服务通过 startForeground 更新；
        // 启动服务不能再绕回普通 notify，否则系统维护的 FGS 记录会停留在旧进度文案。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                foregroundBehavior.serviceNotificationId,
                notification,
                foregroundBehavior.foregroundServiceType ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            service.startForeground(foregroundBehavior.serviceNotificationId, notification)
        }
        stateStore.markActive(spec.notificationKey, null, foregroundBehavior.serviceNotificationId)
        foregroundPostStates[spec.notificationKey] = ForegroundPostState(
            signature = ForegroundPostSignature.from(spec),
            postedAtElapsedMillis = now
        )
        legacyForegroundNotificationTarget(spec.notificationKey)?.let { legacyTarget ->
            if (legacyTarget.id != foregroundBehavior.serviceNotificationId || legacyTarget.tag != null) {
                notificationManagerCompat.cancel(legacyTarget.tag, legacyTarget.id)
            }
        }
        return notification
    }

    override fun remove(notificationKey: String) {
        val notificationTarget = resolveStoredNotificationTarget(notificationKey) ?: resolveNotificationTarget(notificationKey)
        notificationManagerCompat.cancel(notificationTarget.tag, notificationTarget.id)
        foregroundPostStates.remove(notificationKey)
        stateStore.markRemoved(notificationKey)
    }

    override fun removeGroup(prefix: String) {
        // 统一通知出口当前只为固定通知键服务；
        // 这里保留前缀删除接口，调用方在 V1 传确定键或统一前缀即可。
        if (prefix.isBlank()) {
            return
        }
        val knownKeys = HostNotificationStateStore(appContext).findKeysByPrefix(prefix)
        knownKeys.forEach(::remove)
    }

    override fun buildNotification(spec: HostNotificationSpec): Notification {
        val builder = NotificationCompat.Builder(appContext, resolveChannelId(spec.channel))
            .setSmallIcon(spec.smallIconResId)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(spec.autoCancel)
            .setCategory(resolveCategory(spec.channel))

        when (val progress = spec.progress) {
            is HostNotificationProgress.None -> builder.setProgress(0, 0, false)
            is HostNotificationProgress.Indeterminate -> builder.setProgress(0, 0, true)
            is HostNotificationProgress.Determinate -> {
                val normalizedMax = progress.max.coerceAtLeast(1)
                val normalizedCurrent = progress.current.coerceIn(0, normalizedMax)
                builder.setProgress(normalizedMax, normalizedCurrent, false)
            }
        }

        val contentIntent = createContentIntent(spec)
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        return builder.build()
    }

    private fun resolveChannelId(channel: HostNotificationChannel): String {
        return when (channel) {
            HostNotificationChannel.FOREGROUND_RUNTIME -> foregroundRuntimeChannelId
            HostNotificationChannel.MESSAGE_ALERTS -> messageAlertsChannelId
            HostNotificationChannel.DOWNLOADS_INSTALL -> downloadsInstallChannelId
        }
    }

    private fun resolveCategory(channel: HostNotificationChannel): String {
        return when (channel) {
            HostNotificationChannel.FOREGROUND_RUNTIME -> NotificationCompat.CATEGORY_SERVICE
            HostNotificationChannel.MESSAGE_ALERTS -> NotificationCompat.CATEGORY_MESSAGE
            HostNotificationChannel.DOWNLOADS_INSTALL -> NotificationCompat.CATEGORY_STATUS
        }
    }

    private fun createContentIntent(spec: HostNotificationSpec): PendingIntent? {
        val tapSpec = spec.tapSpec ?: return null
        val intent = when (tapSpec.action) {
            HostNotificationAction.OPEN_MAIN -> {
                Intent(appContext, com.jm.sillydroid.feature.main.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            HostNotificationAction.OPEN_DOWNLOADS -> {
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            HostNotificationAction.OPEN_FILE -> {
                val targetUri = tapSpec.payload?.trim().orEmpty()
                val contentUri = resolveOpenFileUri(targetUri) ?: return createDownloadsFallbackPendingIntent(spec)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        contentUri,
                        tapSpec.mimeType?.trim().orEmpty().ifBlank { appContext.contentResolver.getType(contentUri).orEmpty() }
                            .ifBlank { "*/*" }
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            HostNotificationAction.OPEN_UPDATE_INSTALLER -> {
                val apkPath = tapSpec.payload?.trim().orEmpty()
                val apkFile = File(apkPath)
                if (!apkFile.isFile) {
                    return null
                }

                val installUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile
                )
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        installUri,
                        tapSpec.mimeType?.trim().orEmpty().ifBlank { "application/vnd.android.package-archive" }
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            HostNotificationAction.OPEN_APP_INSTALL_PERMISSION_SETTINGS -> {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = android.net.Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        if (intent.resolveActivity(appContext.packageManager) == null) {
            return createDownloadsFallbackPendingIntent(spec)
        }
        val requestCode = resolveNotificationId(spec.notificationKey)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, requestCode, intent, flags)
    }

    private fun createDownloadsFallbackPendingIntent(spec: HostNotificationSpec): PendingIntent? {
        val fallbackIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (fallbackIntent.resolveActivity(appContext.packageManager) == null) {
            return null
        }
        val requestCode = resolveNotificationId(spec.notificationKey)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, requestCode, fallbackIntent, flags)
    }

    private fun resolveOpenFileUri(rawPayload: String): Uri? {
        if (rawPayload.isBlank()) {
            return null
        }
        val parsedUri = runCatching { Uri.parse(rawPayload) }.getOrNull()
        if (parsedUri != null && !parsedUri.scheme.isNullOrBlank()) {
            if (parsedUri.scheme.equals("file", ignoreCase = true)) {
                val targetFile = File(parsedUri.path.orEmpty())
                if (!targetFile.isFile) {
                    return null
                }
                return FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    targetFile
                )
            }
            return parsedUri
        }

        val targetFile = File(rawPayload)
        if (!targetFile.isFile) {
            return null
        }
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            targetFile
        )
    }

    private fun resolveNotificationId(notificationKey: String): Int {
        val seed = notificationKey.hashCode()
        return if (seed == Int.MIN_VALUE) 0 else kotlin.math.abs(seed)
    }

    private fun resolveNotificationTarget(spec: HostNotificationSpec): NotificationTarget {
        spec.foregroundBehavior?.let { behavior ->
            return NotificationTarget(
                tag = null,
                id = behavior.serviceNotificationId
            )
        }
        return resolveNotificationTarget(spec.notificationKey)
    }

    private fun resolveNotificationTarget(notificationKey: String): NotificationTarget {
        return NotificationTarget(
            tag = notificationKey,
            id = resolveNotificationId(notificationKey)
        )
    }

    private fun resolveStoredNotificationTarget(notificationKey: String): NotificationTarget? {
        val storedTarget = stateStore.findTarget(notificationKey) ?: return legacyForegroundNotificationTarget(notificationKey)
        return NotificationTarget(
            tag = storedTarget.tag,
            id = storedTarget.id
        )
    }

    private fun legacyForegroundNotificationTarget(notificationKey: String): NotificationTarget? {
        if (notificationKey != "foreground-bootstrap") {
            return null
        }
        return resolveNotificationTarget(notificationKey)
    }

    private fun shouldSkipForegroundPost(spec: HostNotificationSpec, now: Long): Boolean {
        val previous = foregroundPostStates[spec.notificationKey] ?: return false
        val nextSignature = ForegroundPostSignature.from(spec)
        if (previous.signature == nextSignature) {
            return true
        }

        // 启动阶段 snapshot 会连续发布给 UI；通知只需要节流展示进度。
        // ready / 失败 / 暂停这类清进度或改变状态的通知不能节流，必须立即覆盖常驻通知。
        if (previous.signature.isBootstrapProgressState() && nextSignature.isBootstrapProgressState()) {
            return now - previous.postedAtElapsedMillis < foregroundBootstrapProgressPostIntervalMillis
        }
        return false
    }

    private data class ForegroundPostState(
        val signature: ForegroundPostSignature,
        val postedAtElapsedMillis: Long
    )

    private data class ForegroundPostSignature(
        val kind: HostNotificationKind,
        val channel: HostNotificationChannel,
        val title: String,
        val body: String,
        val progress: HostNotificationProgress,
        val ongoing: Boolean,
        val autoCancel: Boolean
    ) {
        fun isBootstrapProgressState(): Boolean {
            return kind == HostNotificationKind.FOREGROUND_BOOTSTRAP &&
                progress !is HostNotificationProgress.None
        }

        companion object {
            fun from(spec: HostNotificationSpec): ForegroundPostSignature {
                return ForegroundPostSignature(
                    kind = spec.kind,
                    channel = spec.channel,
                    title = spec.title,
                    body = spec.body,
                    progress = spec.progress,
                    ongoing = spec.ongoing,
                    autoCancel = spec.autoCancel
                )
            }
        }
    }

    private data class NotificationTarget(
        val tag: String?,
        val id: Int
    )
}
