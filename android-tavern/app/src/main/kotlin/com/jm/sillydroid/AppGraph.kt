package com.jm.sillydroid

import android.app.DownloadManager
import android.app.Application
import android.app.Activity
import android.content.Intent
import com.jm.sillydroid.core.common.AndroidDispatcherProvider
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.core.model.settings.SettingsNavigationContract
import com.jm.sillydroid.core.model.update.AppUpdateBuildConfig
import com.jm.sillydroid.data.runtime.DefaultConsoleRuntimeRepository
import com.jm.sillydroid.data.extensions.ExtensionCommandExecutor
import com.jm.sillydroid.data.extensions.ExtensionsLocalDataSource
import com.jm.sillydroid.data.extensions.ExtensionsRepositoryImpl
import com.jm.sillydroid.data.extensions.RemoteManifestDataSource
import com.jm.sillydroid.data.logs.HostLogRepositoryImpl
import com.jm.sillydroid.data.logs.HostRuntimeLogManager
import com.jm.sillydroid.data.runtime.AssetRuntimeMetadataRepository
import com.jm.sillydroid.data.runtime.BootRuntimeConfigRepository
import com.jm.sillydroid.data.runtime.DefaultHostProcessManager
import com.jm.sillydroid.data.runtime.HostExtensionDirectoriesProvider
import com.jm.sillydroid.data.runtime.ProotExtensionCommandRunner
import com.jm.sillydroid.data.settings.BootstrapHostConfigStore
import com.jm.sillydroid.data.settings.TavernConfigRepository
import com.jm.sillydroid.data.settings.TavernDataArchiveManager
import com.jm.sillydroid.data.update.AppUpdateRepositoryImpl
import com.jm.sillydroid.data.update.AppUpdateStateStore
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.domain.bootstrap.ConsoleRuntimeRepository
import com.jm.sillydroid.domain.bootstrap.RuntimeMetadataRepository
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.logs.HostLogRepository
import com.jm.sillydroid.domain.notification.HostNotificationService
import com.jm.sillydroid.domain.notification.HostDownloadNotificationCoordinator
import com.jm.sillydroid.domain.extensions.ExtensionsRepository
import com.jm.sillydroid.domain.runtime.HostAppForegroundState
import com.jm.sillydroid.domain.runtime.RuntimeLogManager
import com.jm.sillydroid.domain.settings.DataArchiveRepository
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import com.jm.sillydroid.domain.settings.SettingsConfigRepository
import com.jm.sillydroid.domain.update.AppUpdateRepository
import com.jm.sillydroid.domain.update.AppUpdateStateRepository
import com.jm.sillydroid.ui.update.R as UpdateR

class AppGraph(private val application: Application) : SillyDroidAppGraph {
    override val dispatchers: DispatcherProvider = AndroidDispatcherProvider

    private val appForegroundStateStore = ApplicationForegroundStateStore().also { foregroundStateStore ->
        application.registerActivityLifecycleCallbacks(foregroundStateStore)
    }

    private val extensionDirectoriesProvider by lazy {
        HostExtensionDirectoriesProvider(application)
    }

    private val remoteManifestDataSource by lazy {
        RemoteManifestDataSource()
    }

    override val hostConfigStore: HostPreferencesRepository by lazy {
        BootstrapHostConfigStore(application)
    }

    override val hostLogRepository: HostLogRepository by lazy {
        HostLogRepositoryImpl(application)
    }

    override val hostNotificationService: HostNotificationService by lazy {
        HostNotificationServiceImpl(application)
    }

    override val hostDownloadNotificationCoordinator: HostDownloadNotificationCoordinator by lazy {
        HostDownloadNotificationCoordinatorImpl(
            context = application,
            hostNotificationService = hostNotificationService,
            downloadManager = application.getSystemService(DownloadManager::class.java)
        )
    }

    override val appForegroundState: HostAppForegroundState
        get() = appForegroundStateStore

    override val runtimeLogManager: RuntimeLogManager by lazy {
        HostRuntimeLogManager(application)
    }

    override val bootstrapController: BootstrapController by lazy {
        DefaultHostProcessManager(application, dispatchers)
    }

    override val runtimeConfigRepository: RuntimeConfigRepository by lazy {
        BootRuntimeConfigRepository(hostConfigStore)
    }

    override val runtimeMetadataRepository: RuntimeMetadataRepository by lazy {
        AssetRuntimeMetadataRepository(application)
    }

    override val consoleRuntimeRepository: ConsoleRuntimeRepository by lazy {
        DefaultConsoleRuntimeRepository(application)
    }

    private val appUpdateStateStore: AppUpdateStateStore by lazy {
        AppUpdateStateStore(application)
    }

    val appUpdateStateRepository: AppUpdateStateRepository by lazy {
        appUpdateStateStore
    }

    override val appUpdateRepository: AppUpdateRepository by lazy {
        AppUpdateRepositoryImpl(
            context = application,
            downloadManager = application.getSystemService(DownloadManager::class.java),
            stateStore = appUpdateStateStore,
            downloadDescription = application.getString(UpdateR.string.app_update_download_started)
        )
    }

    override val appUpdateBuildConfig: AppUpdateBuildConfig by lazy {
        AppUpdateBuildConfig(
            githubRepository = BuildConfig.SILLYDROID_GITHUB_REPOSITORY,
            latestReleaseMetadataUrl = BuildConfig.SILLYDROID_LATEST_RELEASE_METADATA_URL,
            buildType = BuildConfig.BUILD_TYPE,
            hostVersion = BuildConfig.SILLYDROID_HOST_VERSION,
            upstreamVersion = BuildConfig.SILLYDROID_UPSTREAM_VERSION
        )
    }

    private val extensionsLocalDataSource: ExtensionsLocalDataSource by lazy {
        ExtensionsLocalDataSource(extensionDirectoriesProvider)
    }

    private val extensionCommandExecutor: ExtensionCommandExecutor by lazy {
        ExtensionCommandExecutor(
            commandRunner = ProotExtensionCommandRunner(application, runtimeLogManager),
            remoteManifestDataSource = remoteManifestDataSource
        )
    }

    private val extensionsRepository: ExtensionsRepository by lazy {
        ExtensionsRepositoryImpl(
            localDataSource = extensionsLocalDataSource,
            remoteManifestDataSource = remoteManifestDataSource,
            extensionCommandExecutor = extensionCommandExecutor
        )
    }

    override fun tavernConfigRepository(): SettingsConfigRepository {
        return TavernConfigRepository(application)
    }

    override fun tavernDataArchiveManager(): DataArchiveRepository {
        return TavernDataArchiveManager(application)
    }

    override fun defaultExtensionRepositoryCount(): Int {
        return extensionsRepository.repositoryCount()
    }

    override fun extensionsRepository(): ExtensionsRepository {
        return extensionsRepository
    }

    override fun createSettingsIntent(
        activity: Activity,
        openExtensionsTab: Boolean,
        openDefaultExtensionsInstaller: Boolean
    ): Intent {
        return Intent().setClassName(
            activity,
            settingsActivityClassName
        ).putExtra(SettingsNavigationContract.openExtensionsTabKey, openExtensionsTab)
            .putExtra(SettingsNavigationContract.openDefaultExtensionsInstallerKey, openDefaultExtensionsInstaller)
    }

    private companion object {
        private const val settingsActivityClassName = "com.jm.sillydroid.feature.settings.BootstrapSettingsActivity"
    }
}
