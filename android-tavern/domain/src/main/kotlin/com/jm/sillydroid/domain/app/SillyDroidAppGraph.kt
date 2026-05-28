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
