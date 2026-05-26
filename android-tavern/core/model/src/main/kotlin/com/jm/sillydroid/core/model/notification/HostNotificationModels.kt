package com.jm.sillydroid.core.model.notification

enum class HostNotificationKind {
    FOREGROUND_BOOTSTRAP,
    WEB_MESSAGE,
    BROWSER_DOWNLOAD,
    APP_UPDATE_DOWNLOAD,
    APP_UPDATE_READY_TO_INSTALL
}

enum class HostNotificationChannel {
    FOREGROUND_RUNTIME,
    MESSAGE_ALERTS,
    DOWNLOADS_INSTALL
}

enum class HostNotificationAction {
    OPEN_MAIN,
    OPEN_DOWNLOADS,
    OPEN_FILE,
    OPEN_UPDATE_INSTALLER,
    OPEN_APP_INSTALL_PERMISSION_SETTINGS
}

sealed interface HostNotificationProgress {
    data object None : HostNotificationProgress
    data object Indeterminate : HostNotificationProgress
    data class Determinate(
        val current: Int,
        val max: Int
    ) : HostNotificationProgress
}

data class HostNotificationTapSpec(
    val action: HostNotificationAction,
    val payload: String? = null,
    val mimeType: String? = null
)

data class HostForegroundBehavior(
    val serviceNotificationId: Int,
    val foregroundServiceType: Int? = null
)

data class HostNotificationSpec(
    val notificationKey: String,
    val kind: HostNotificationKind,
    val channel: HostNotificationChannel,
    val title: String,
    val body: String,
    val progress: HostNotificationProgress = HostNotificationProgress.None,
    val ongoing: Boolean = false,
    val autoCancel: Boolean = true,
    val tapSpec: HostNotificationTapSpec? = null,
    val smallIconResId: Int,
    val foregroundBehavior: HostForegroundBehavior? = null
)
