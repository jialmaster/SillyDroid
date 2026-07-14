package com.jm.sillydroid.domain.app

import android.app.Activity
import android.content.Intent
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.domain.bootstrap.ConsoleRuntimeRepository
import com.jm.sillydroid.core.model.update.AppUpdateBuildConfig
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.domain.bootstrap.RuntimeMetadataRepository
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.extensions.ExtensionsRepository
import com.jm.sillydroid.domain.logs.HostLogRepository
import com.jm.sillydroid.domain.notification.HostNotificationService
import com.jm.sillydroid.domain.notification.HostDownloadNotificationCoordinator
import com.jm.sillydroid.domain.runtime.HostAppForegroundState
import com.jm.sillydroid.domain.runtime.RuntimeLogManager
import com.jm.sillydroid.domain.settings.DataArchiveRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.domain.settings.SettingsConfigRepository
import com.jm.sillydroid.domain.update.AppUpdateRepository

interface SillyDroidAppGraph {
    val dispatchers: DispatcherProvider
    val hostConfigStore: HostPreferencesRepository
    val hostLogRepository: HostLogRepository
    val hostNotificationService: HostNotificationService
    val hostDownloadNotificationCoordinator: HostDownloadNotificationCoordinator
    val appForegroundState: HostAppForegroundState
    val runtimeLogManager: RuntimeLogManager
    val bootstrapController: BootstrapController
    val runtimeConfigRepository: RuntimeConfigRepository
    val runtimeMetadataRepository: RuntimeMetadataRepository
    val consoleRuntimeRepository: ConsoleRuntimeRepository
    val appUpdateRepository: AppUpdateRepository
    val appUpdateBuildConfig: AppUpdateBuildConfig

    fun tavernConfigRepository(): SettingsConfigRepository
    fun tavernDataArchiveManager(): DataArchiveRepository
    fun extensionsRepository(): ExtensionsRepository
    fun defaultExtensionRepositoryCount(): Int
    fun createSettingsIntent(
        activity: Activity,
        openExtensionsTab: Boolean = false,
        openDefaultExtensionsInstaller: Boolean = false
    ): Intent
}

interface SillyDroidAppGraphProvider {
    val sillyDroidAppGraph: SillyDroidAppGraph
}

/**
 * 进程级后台悬浮浏览器控制器。
 *
 * 允许：设置页等非主界面请求预热、停止和短期抑制；不允许暴露具体浏览器 View 或窗口实现。
 */
interface HostFloatingBrowserController {
    /** 持有短期抑制令牌，关闭令牌前应用退后台不显示悬浮浏览器。 */
    fun acquireSuppression(reason: String): AutoCloseable

    /** 在 App 前台预启动悬浮浏览器服务，避免后台时机命中服务启动限制。 */
    fun prepare()

    /** 停止悬浮浏览器服务并移除窗口，不影响 Node 后端前台服务。 */
    fun stop()
}

/**
 * 暴露悬浮浏览器进程控制能力的 Application 契约。
 *
 * 允许：feature 模块通过 Application 获取控制器；不允许 feature 模块反向依赖具体 app 实现类。
 */
interface HostFloatingBrowserControllerProvider {
    val hostFloatingBrowserController: HostFloatingBrowserController
}
