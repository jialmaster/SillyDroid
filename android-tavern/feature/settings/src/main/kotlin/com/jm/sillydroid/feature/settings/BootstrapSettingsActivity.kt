package com.jm.sillydroid.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.R as MaterialR
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jm.sillydroid.core.ui.window.SystemBarAppearanceController
import com.jm.sillydroid.core.model.bootstrap.shouldPreferTavernServerLog
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.ui.scroll.DraggableScrollThumbController
import com.jm.sillydroid.core.model.settings.SettingsNavigationContract
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.feature.settings.model.SettingsActivityUiState
import com.jm.sillydroid.feature.settings.model.SettingsTab
import com.jm.sillydroid.feature.settings.ui.about.BootstrapSettingsAboutController
import com.jm.sillydroid.feature.settings.ui.data.BootstrapSettingsDataCoordinator
import com.jm.sillydroid.feature.settings.ui.extensions.BootstrapSettingsExtensionsCoordinator
import com.jm.sillydroid.feature.settings.ui.form.BootstrapSettingsFormController
import com.jm.sillydroid.feature.settings.ui.logs.BootstrapSettingsLogsCoordinator
import com.jm.sillydroid.feature.settings.ui.screen.BootstrapSettingsScreenController
import com.jm.sillydroid.feature.settings.ui.screen.SettingsActivityStateController
import com.jm.sillydroid.feature.settings.ui.settings.BootstrapSettingsSettingsCoordinator
import com.jm.sillydroid.feature.settings.ui.terminal.HostConsoleSessionStoreRegistry
import com.jm.sillydroid.feature.settings.ui.terminal.TerminalExtraKeysStripView
import com.jm.sillydroid.feature.settings.ui.terminal.TerminalPageController
import com.jm.sillydroid.feature.settings.ui.terminal.TermuxHostConsoleSessionFactory
import com.jm.sillydroid.feature.settings.viewmodel.SettingsActivityViewModel
import com.jm.sillydroid.feature.settings.viewmodel.SettingsActivityViewModelFactory
import com.termux.view.TerminalView
import com.jm.sillydroid.ui.update.AppUpdateCoordinator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class BootstrapSettingsActivity : AppCompatActivity() {
    companion object {
        private const val resultShouldStartKey = SettingsNavigationContract.resultShouldStartKey
        private const val resultShouldReloadTavernUiKey = SettingsNavigationContract.resultShouldReloadTavernUiKey
        private const val openExtensionsTabKey = SettingsNavigationContract.openExtensionsTabKey
        private const val openDefaultExtensionsInstallerKey = SettingsNavigationContract.openDefaultExtensionsInstallerKey

        fun createIntent(
            activity: Activity,
            openExtensionsTab: Boolean = false,
            openDefaultExtensionsInstaller: Boolean = false
        ): Intent {
            return Intent(activity, BootstrapSettingsActivity::class.java)
                .putExtra(openExtensionsTabKey, openExtensionsTab)
                .putExtra(openDefaultExtensionsInstallerKey, openDefaultExtensionsInstaller)
        }

        fun shouldStartBootstrap(data: Intent?): Boolean {
            return data?.getBooleanExtra(resultShouldStartKey, false) == true
        }

        fun shouldReloadTavernUi(data: Intent?): Boolean {
            return data?.getBooleanExtra(resultShouldReloadTavernUiKey, false) == true
        }
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var toolbarTitleView: TextView
    private lateinit var toolbarAboutEntryView: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var rootView: View
    private lateinit var topShellView: View
    private lateinit var scrollView: NestedScrollView
    private lateinit var loadingIndicator: LinearProgressIndicator
    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var dataPanelView: View
    private lateinit var quickFieldContainer: LinearLayout
    private lateinit var floatingLogsSwitch: MaterialSwitch
    private lateinit var pullRefreshSwitch: MaterialSwitch
    private lateinit var displayModeRow: View
    private lateinit var displayModeValueView: TextView
    private lateinit var debugDiagnosticsSwitch: MaterialSwitch
    private lateinit var unrestrictedFileImportSelectionSwitch: MaterialSwitch
    private lateinit var extensionsPanelView: View
    private lateinit var extensionsListContainer: LinearLayout
    private lateinit var extensionsEmptyView: TextView
    private lateinit var extensionsInstallButton: ImageButton
    private lateinit var extensionsBatchDeleteButton: ImageButton
    private lateinit var extensionsReloadButton: ImageButton
    private lateinit var extensionsProgressIndicator: LinearProgressIndicator
    private lateinit var extensionsProgressLabel: TextView
    private lateinit var logsPanelView: View
    private lateinit var logsScrollView: NestedScrollView
    private lateinit var logsScrollToBottomButton: android.widget.ImageButton
    private lateinit var logsScrollbarThumb: View
    private lateinit var logsMetaView: TextView
    private lateinit var logsEmptyView: TextView
    private lateinit var logsContentView: TextView
    private lateinit var logsSelectButton: MaterialButton
    private lateinit var logsExportButton: MaterialButton
    private lateinit var logsReloadButton: MaterialButton
    private lateinit var logsClearButton: MaterialButton
    private lateinit var terminalPanelView: View
    private lateinit var terminalView: TerminalView
    private lateinit var terminalStatusView: TextView
    private lateinit var terminalRetryButton: MaterialButton
    private lateinit var terminalCtrlCButton: MaterialButton
    private lateinit var terminalClearButton: MaterialButton
    private lateinit var terminalResetButton: MaterialButton
    private lateinit var terminalSelectButton: MaterialButton
    private lateinit var terminalSettingsButton: MaterialButton
    private lateinit var terminalExtraKeysStripView: TerminalExtraKeysStripView
    private lateinit var settingsPanelView: View
    private lateinit var aboutPanelView: View
    private lateinit var bottomActionBarView: View
    private lateinit var aboutGithubButton: ImageButton
    private lateinit var aboutVersionView: TextView
    private lateinit var aboutUpdateStatusView: TextView
    private lateinit var aboutUpdateButton: MaterialButton
    private lateinit var sectionContainer: LinearLayout
    private lateinit var configPathView: TextView
    private lateinit var warningView: TextView
    private lateinit var restoreDefaultsButton: ImageButton
    private lateinit var importButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var clearDataButton: MaterialButton
    private lateinit var saveStartButton: MaterialButton

    private val appGraph: SillyDroidAppGraph
        get() = (application as SillyDroidAppGraphProvider).sillyDroidAppGraph
    private val configRepository by lazy { appGraph.tavernConfigRepository() }
    private val archiveManager by lazy { appGraph.tavernDataArchiveManager() }
    private val hostConfigStore by lazy { appGraph.hostConfigStore }
    private val hostLogRepository by lazy { appGraph.hostLogRepository }
    private val processManager by lazy<BootstrapController> { appGraph.bootstrapController }
    private val runtimeConfigRepository by lazy { appGraph.runtimeConfigRepository }
    private val settingsActivityViewModel by lazy {
        ViewModelProvider(
            this,
            SettingsActivityViewModelFactory(hostConfigStore)
        )[SettingsActivityViewModel::class.java]
    }
    private lateinit var screenController: BootstrapSettingsScreenController
    private lateinit var stateController: SettingsActivityStateController
    private lateinit var aboutController: BootstrapSettingsAboutController
    private lateinit var formController: BootstrapSettingsFormController
    private lateinit var settingsCoordinator: BootstrapSettingsSettingsCoordinator
    private lateinit var dataCoordinator: BootstrapSettingsDataCoordinator
    private lateinit var extensionsCoordinator: BootstrapSettingsExtensionsCoordinator
    private lateinit var logsCoordinator: BootstrapSettingsLogsCoordinator
    private lateinit var terminalPageController: TerminalPageController
    private lateinit var appUpdateCoordinator: AppUpdateCoordinator
    private val consoleSessionStore by lazy {
        HostConsoleSessionStoreRegistry.getOrCreate(
            consoleRuntimeRepository = appGraph.consoleRuntimeRepository,
            sessionFactory = TermuxHostConsoleSessionFactory(applicationContext),
            dispatchers = appGraph.dispatchers
        )
    }

    private val exportArchiveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { targetUri ->
        if (targetUri != null) {
            dataCoordinator.exportArchive(targetUri)
        }
    }

    private val importArchiveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { sourceUri ->
        if (sourceUri != null) {
            dataCoordinator.inspectArchive(sourceUri) { preview ->
                screenController.confirmImport(preview) {
                    dataCoordinator.importArchive(sourceUri, preview)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_bootstrap_settings)
        // 设置页顶部本来就是 Material surface；这里直接让系统栏跟 surface 颜色对齐，
        // 避免浅色主题下出现白底白字，也避免顶部留一条和页面断开的系统底色。
        applySettingsSurfaceSystemBars()
        bindViews()
        initializeControllers()
        stateController.initialize()
        aboutController.initialize()
        screenController.initialize()
        extensionsCoordinator.initialize()
        logsCoordinator.initialize()
        terminalPageController.initialize()
        appUpdateCoordinator.initialize()
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbarTitleView.text = getString(R.string.bootstrap_settings_title)
        toolbar.setNavigationOnClickListener {
            settingsCoordinator.attemptFinish()
        }
        onBackPressedDispatcher.addCallback(this) {
            settingsCoordinator.attemptFinish()
        }

        restoreDefaultsButton.setOnClickListener {
            screenController.confirmRestoreDefaults {
                dataCoordinator.restoreDefaults()
            }
        }
        importButton.setOnClickListener {
            importArchiveLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        exportButton.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            exportArchiveLauncher.launch(getString(R.string.bootstrap_settings_export_name, timestamp))
        }
        clearDataButton.setOnClickListener {
            screenController.confirmClearData {
                dataCoordinator.clearDataAndRestart {
                    setResult(Activity.RESULT_OK, Intent().putExtra(resultShouldStartKey, true))
                    finish()
                }
            }
        }
        saveStartButton.setOnClickListener {
            settingsCoordinator.saveAndStart()
        }
        searchInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            val hasMatch = formController.applySearchQuery(query)
            searchLayout.error = if (query.isNotBlank() && !hasMatch) {
                getString(R.string.bootstrap_settings_search_no_result)
            } else {
                null
            }
        }

        // 设置页仍然等真实配置异步回填，但“启动端口”先放默认值占位，避免首屏出现空白洞。
        formController.renderStartupPortPlaceholder()
        screenController.setBusy(true)
        settingsCoordinator.loadConfiguration()

        if (intent.getBooleanExtra(openExtensionsTabKey, false) || intent.getBooleanExtra(openDefaultExtensionsInstallerKey, false)) {
            tabLayout.post {
                SettingsTab.EXTENSIONS.tabPosition?.let { position ->
                    tabLayout.getTabAt(position)?.select()
                }
                if (intent.getBooleanExtra(openDefaultExtensionsInstallerKey, false)) {
                    extensionsCoordinator.promptDefaultRepositoriesSelection()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            settingsCoordinator.attemptFinish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    private fun bindViews() {
        rootView = findViewById(R.id.bootstrapSettingsRoot)
        topShellView = findViewById(R.id.bootstrapSettingsTopShell)
        toolbar = findViewById(R.id.bootstrapSettingsToolbar)
        toolbarTitleView = findViewById(R.id.bootstrapSettingsToolbarTitle)
        toolbarAboutEntryView = findViewById(R.id.bootstrapSettingsToolbarAboutEntry)
        tabLayout = findViewById(R.id.bootstrapSettingsTabs)
        scrollView = findViewById(R.id.bootstrapSettingsScrollView)
        loadingIndicator = findViewById(R.id.bootstrapSettingsLoading)
        searchLayout = findViewById(R.id.bootstrapSettingsSearchLayout)
        searchInput = findViewById(R.id.bootstrapSettingsSearchInput)
        dataPanelView = findViewById(R.id.bootstrapSettingsDataPanel)
        quickFieldContainer = findViewById(R.id.bootstrapSettingsQuickFieldContainer)
        floatingLogsSwitch = findViewById(R.id.bootstrapSettingsFloatingLogsSwitch)
        pullRefreshSwitch = findViewById(R.id.bootstrapSettingsPullRefreshSwitch)
        displayModeRow = findViewById(R.id.bootstrapSettingsDisplayModeRow)
        displayModeValueView = findViewById(R.id.bootstrapSettingsDisplayModeValue)
        debugDiagnosticsSwitch = findViewById(R.id.bootstrapSettingsDebugDiagnosticsSwitch)
        unrestrictedFileImportSelectionSwitch = findViewById(R.id.bootstrapSettingsUnrestrictedFileImportSelectionSwitch)
        extensionsPanelView = findViewById(R.id.bootstrapSettingsExtensionsPanel)
        extensionsListContainer = findViewById(R.id.bootstrapSettingsExtensionsListContainer)
        extensionsEmptyView = findViewById(R.id.bootstrapSettingsExtensionsEmpty)
        extensionsInstallButton = findViewById(R.id.bootstrapSettingsExtensionsInstallButton)
        extensionsBatchDeleteButton = findViewById(R.id.bootstrapSettingsExtensionsBatchDeleteButton)
        extensionsReloadButton = findViewById(R.id.bootstrapSettingsExtensionsReloadButton)
        extensionsProgressIndicator = findViewById(R.id.bootstrapSettingsExtensionsProgress)
        extensionsProgressLabel = findViewById(R.id.bootstrapSettingsExtensionsProgressLabel)
        logsPanelView = findViewById(R.id.bootstrapSettingsLogsPanel)
        logsScrollView = findViewById(R.id.bootstrapSettingsLogsScrollView)
        logsScrollToBottomButton = findViewById(R.id.bootstrapSettingsLogsScrollToBottomButton)
        logsScrollbarThumb = findViewById(R.id.bootstrapSettingsLogsScrollbarThumb)
        logsMetaView = findViewById(R.id.bootstrapSettingsLogsMeta)
        logsEmptyView = findViewById(R.id.bootstrapSettingsLogsEmpty)
        logsContentView = findViewById(R.id.bootstrapSettingsLogsContent)
        logsSelectButton = findViewById(R.id.bootstrapSettingsLogsSelectButton)
        logsExportButton = findViewById(R.id.bootstrapSettingsLogsExportButton)
        logsReloadButton = findViewById(R.id.bootstrapSettingsLogsReloadButton)
        logsClearButton = findViewById(R.id.bootstrapSettingsLogsClearButton)
        terminalPanelView = findViewById(R.id.bootstrapSettingsTerminalPanel)
        terminalView = findViewById(R.id.bootstrapSettingsTerminalView)
        terminalStatusView = findViewById(R.id.bootstrapSettingsTerminalStatus)
        terminalRetryButton = findViewById(R.id.bootstrapSettingsTerminalRetryButton)
        terminalCtrlCButton = findViewById(R.id.bootstrapSettingsTerminalCtrlCButton)
        terminalClearButton = findViewById(R.id.bootstrapSettingsTerminalClearButton)
        terminalResetButton = findViewById(R.id.bootstrapSettingsTerminalResetButton)
        terminalSelectButton = findViewById(R.id.bootstrapSettingsTerminalSelectButton)
        terminalSettingsButton = findViewById(R.id.bootstrapSettingsTerminalSettingsButton)
        terminalExtraKeysStripView = findViewById(R.id.bootstrapSettingsTerminalExtraKeysStrip)
        settingsPanelView = findViewById(R.id.bootstrapSettingsSettingsPanel)
        aboutPanelView = findViewById(R.id.bootstrapSettingsAboutPanel)
        bottomActionBarView = findViewById(R.id.bootstrapSettingsBottomActionBar)
        aboutGithubButton = findViewById(R.id.bootstrapSettingsAboutGithubButton)
        aboutVersionView = findViewById(R.id.bootstrapSettingsAboutVersion)
        aboutUpdateStatusView = findViewById(R.id.bootstrapSettingsAboutUpdateStatus)
        aboutUpdateButton = findViewById(R.id.bootstrapSettingsAboutUpdateButton)
        sectionContainer = findViewById(R.id.bootstrapSettingsSectionContainer)
        configPathView = findViewById(R.id.bootstrapSettingsConfigPath)
        warningView = findViewById(R.id.bootstrapSettingsWarning)
        restoreDefaultsButton = findViewById(R.id.bootstrapSettingsRestoreDefaultsButton)
        importButton = findViewById(R.id.bootstrapSettingsImportButton)
        exportButton = findViewById(R.id.bootstrapSettingsExportButton)
        clearDataButton = findViewById(R.id.bootstrapSettingsClearDataButton)
        saveStartButton = findViewById(R.id.bootstrapSettingsSaveButton)
    }

    private fun initializeControllers() {
        screenController = BootstrapSettingsScreenController(
            activity = this,
            rootView = rootView,
            topShellView = topShellView,
            scrollView = scrollView,
            tabLayout = tabLayout,
            toolbarAboutEntryView = toolbarAboutEntryView,
            dataPanelView = dataPanelView,
            extensionsPanelView = extensionsPanelView,
            logsPanelView = logsPanelView,
            logsScrollView = logsScrollView,
            terminalPanelView = terminalPanelView,
            settingsPanelView = settingsPanelView,
            aboutPanelView = aboutPanelView,
            bottomActionBarView = bottomActionBarView,
            configPathView = configPathView,
            warningView = warningView,
            loadingIndicator = loadingIndicator,
            searchLayout = searchLayout,
            floatingLogsSwitch = floatingLogsSwitch,
            pullRefreshSwitch = pullRefreshSwitch,
            hostDisplayModeRow = displayModeRow,
            unrestrictedFileImportSelectionSwitch = unrestrictedFileImportSelectionSwitch,
            restoreDefaultsButton = restoreDefaultsButton,
            importButton = importButton,
            exportButton = exportButton,
            clearDataButton = clearDataButton,
            saveStartButton = saveStartButton,
            onTabChanged = { tab ->
                settingsActivityViewModel.selectTab(tab)
                if (this::extensionsCoordinator.isInitialized && tab == SettingsTab.EXTENSIONS) {
                    extensionsCoordinator.reloadExtensions()
                } else if (this::logsCoordinator.isInitialized && tab == SettingsTab.LOGS) {
                    logsCoordinator.reloadLatestLog()
                }
                if (this::terminalPageController.isInitialized) {
                    terminalPageController.onTabChanged(tab)
                }
            }
        )
        stateController = SettingsActivityStateController(
            activity = this,
            viewModel = settingsActivityViewModel,
            floatingLogsSwitch = floatingLogsSwitch,
            pullRefreshSwitch = pullRefreshSwitch,
            hostDisplayModeRow = displayModeRow,
            hostDisplayModeValueView = displayModeValueView,
            debugDiagnosticsSwitch = debugDiagnosticsSwitch,
            unrestrictedFileImportSelectionSwitch = unrestrictedFileImportSelectionSwitch,
            applyHostDisplayMode = ::applySettingsSurfaceSystemBars,
            renderResultFlags = ::renderResultFlags
        )
        aboutController = BootstrapSettingsAboutController(
            activity = this,
            githubButton = aboutGithubButton,
            githubRepository = appGraph.appUpdateBuildConfig.githubRepository,
            externalBrowserFailureMessage = { getString(R.string.browser_open_external_failed) }
        )
        appUpdateCoordinator = AppUpdateCoordinator(
            activity = this,
            appUpdateRepository = appGraph.appUpdateRepository,
            runtimeMetadataRepository = appGraph.runtimeMetadataRepository,
            buildConfig = appGraph.appUpdateBuildConfig,
            dispatchers = appGraph.dispatchers,
            aboutUi = AppUpdateCoordinator.AboutUi(
                versionView = aboutVersionView,
                statusView = aboutUpdateStatusView,
                actionButton = aboutUpdateButton
            )
        )
        formController = BootstrapSettingsFormController(
            activity = this,
            configRepository = configRepository,
            quickFieldContainer = quickFieldContainer,
            sectionContainer = sectionContainer,
            scrollView = scrollView,
            defaultServicePort = runtimeConfigRepository.defaultServicePort,
            onFieldEdited = { changedFieldPath -> settingsCoordinator.clearBlockingFeedback(changedFieldPath) },
            onFormChanged = { settingsCoordinator.refreshDirtyState() }
        )
        settingsCoordinator = BootstrapSettingsSettingsCoordinator(
            activity = this,
            dispatchers = appGraph.dispatchers,
            configRepository = configRepository,
            formController = formController,
            screenController = screenController,
            stopBootstrapForSettings = { message -> processManager.stopForSettingsAndAwait(message) },
            defaultServicePort = runtimeConfigRepository.defaultServicePort,
            onStartBootstrapConfirmed = {
                updateResultFlags(shouldStartBootstrap = true)
                finish()
            }
        )
        dataCoordinator = BootstrapSettingsDataCoordinator(
            activity = this,
            dispatchers = appGraph.dispatchers,
            configRepository = configRepository,
            archiveManager = archiveManager,
            hostConfigStore = hostConfigStore,
            stopBootstrapForSettings = { message -> processManager.stopForSettingsAndAwait(message) },
            defaultServicePort = runtimeConfigRepository.defaultServicePort,
            setBusy = screenController::setBusy,
            applyDraft = settingsCoordinator::applyDraftConfiguration,
            replaceLoadedConfiguration = settingsCoordinator::replaceLoadedConfiguration,
            showDataError = settingsCoordinator::showValidationMessage,
            showBanner = { message -> screenController.showBanner(message) },
            showMessage = screenController::showMessage,
            updateDirtyState = settingsCoordinator::refreshDirtyState,
            restartBootstrap = processManager::restart,
            onBootstrapRestartRequired = {
                processManager.restart()
                finish()
            }
        )
        extensionsCoordinator = BootstrapSettingsExtensionsCoordinator(
            activity = this,
            dispatchers = appGraph.dispatchers,
            listContainer = extensionsListContainer,
            emptyView = extensionsEmptyView,
            installButton = extensionsInstallButton,
            batchDeleteButton = extensionsBatchDeleteButton,
            reloadButton = extensionsReloadButton,
            progressIndicator = extensionsProgressIndicator,
            progressLabel = extensionsProgressLabel,
            extensionsRepository = appGraph.extensionsRepository(),
            setBusy = screenController::setBusy,
            showError = settingsCoordinator::showValidationMessage,
            showBanner = { message -> screenController.showBanner(message) },
            showMessage = screenController::showMessage,
            onTavernUiReloadRequired = {
                updateResultFlags(shouldReloadTavernUi = true)
            }
        )
        logsCoordinator = BootstrapSettingsLogsCoordinator(
            activity = this,
            dispatchers = appGraph.dispatchers,
            metaView = logsMetaView,
            emptyView = logsEmptyView,
            contentView = logsContentView,
            logsScrollView = logsScrollView,
            scrollThumbController = DraggableScrollThumbController(
                scrollView = logsScrollView,
                thumbView = logsScrollbarThumb,
                minThumbHeightPx = resources.getDimensionPixelSize(R.dimen.sillydroid_logs_scrollbar_min_height)
            ),
            scrollToBottomButton = logsScrollToBottomButton,
            selectButton = logsSelectButton,
            exportButton = logsExportButton,
            reloadButton = logsReloadButton,
            clearButton = logsClearButton,
            hostLogRepository = hostLogRepository,
            preferTavernServerLog = { processManager.currentSnapshot().shouldPreferTavernServerLog() },
            setBusy = screenController::setBusy,
            showError = settingsCoordinator::showValidationMessage,
            showMessage = screenController::showMessage
        )
        terminalPageController = TerminalPageController(
            activity = this,
            terminalPanelView = terminalPanelView,
            terminalView = terminalView,
            statusView = terminalStatusView,
            retryButton = terminalRetryButton,
            ctrlCButton = terminalCtrlCButton,
            clearButton = terminalClearButton,
            resetButton = terminalResetButton,
            selectButton = terminalSelectButton,
            settingsButton = terminalSettingsButton,
            extraKeysStripView = terminalExtraKeysStripView,
            hostPreferencesRepository = hostConfigStore,
            sessionStore = consoleSessionStore
        )
    }

    private fun updateResultFlags(
        shouldStartBootstrap: Boolean = false,
        shouldReloadTavernUi: Boolean = false
    ) {
        settingsActivityViewModel.markResultFlags(
            shouldStartBootstrap = shouldStartBootstrap,
            shouldReloadTavernUi = shouldReloadTavernUi
        )
        renderResultFlags(settingsActivityViewModel.uiState.value)
    }

    private fun renderResultFlags(state: SettingsActivityUiState) {
        if (!state.shouldStartBootstrap && !state.shouldReloadTavernUi) {
            return
        }

        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(resultShouldStartKey, state.shouldStartBootstrap)
                .putExtra(resultShouldReloadTavernUiKey, state.shouldReloadTavernUi)
        )
    }

    private fun applySettingsSurfaceSystemBars(mode: HostDisplayMode = hostConfigStore.hostDisplayMode) {
        // 设置页本身也属于宿主界面；这里按用户选择的显示模式统一处理系统栏显示状态，
        // 但背景继续跟随设置页 surface，避免切到设置页后出现和主界面无关的系统底色。
        SystemBarAppearanceController.applyForThemeSurface(
            activity = this,
            mode = mode,
            surfaceColorAttr = MaterialR.attr.colorSurfaceContainerLowest
        )
    }
}
