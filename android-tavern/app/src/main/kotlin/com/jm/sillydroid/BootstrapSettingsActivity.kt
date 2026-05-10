package com.jm.sillydroid

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.net.Uri
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BootstrapSettingsActivity : AppCompatActivity() {
    companion object {
        private const val resultShouldStartKey = "bootstrap-settings.result.start"
        private const val resultShouldReloadTavernUiKey = "bootstrap-settings.result.reload-tavern-ui"
        private const val openExtensionsTabKey = "bootstrap-settings.open-extensions-tab"
        private const val openDefaultExtensionsInstallerKey = "bootstrap-settings.open-default-extensions-installer"

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
    private lateinit var tabLayout: TabLayout
    private lateinit var rootView: View
    private lateinit var topShellView: View
    private lateinit var scrollView: NestedScrollView
    private lateinit var actionBarView: View
    private lateinit var loadingIndicator: LinearProgressIndicator
    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var dataPanelView: View
    private lateinit var quickFieldContainer: LinearLayout
    private lateinit var floatingLogsSwitch: MaterialSwitch
    private lateinit var extensionsPanelView: View
    private lateinit var extensionsListContainer: LinearLayout
    private lateinit var extensionsEmptyView: TextView
    private lateinit var extensionsInstallButton: ImageButton
    private lateinit var extensionsReloadButton: ImageButton
    private lateinit var extensionsProgressIndicator: LinearProgressIndicator
    private lateinit var extensionsProgressLabel: TextView
    private lateinit var logsPanelView: View
    private lateinit var logsScrollView: NestedScrollView
    private lateinit var logsScrollToBottomButton: android.widget.ImageButton
    private lateinit var logsMetaView: TextView
    private lateinit var logsEmptyView: TextView
    private lateinit var logsContentView: TextView
    private lateinit var logsSelectButton: MaterialButton
    private lateinit var logsExportButton: MaterialButton
    private lateinit var logsReloadButton: MaterialButton
    private lateinit var settingsPanelView: View
    private lateinit var aboutPanelView: View
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

    private val configRepository by lazy { TavernConfigRepository(this) }
    private val archiveManager by lazy { TavernDataArchiveManager(this) }
    private val hostConfigStore by lazy { BootstrapHostConfigStore(this) }
    private val processManager by lazy<HostProcessManager> { DefaultHostProcessManager(this) }
    private val downloadManager by lazy { getSystemService(DownloadManager::class.java) }
    private lateinit var screenController: BootstrapSettingsScreenController
    private lateinit var formController: BootstrapSettingsFormController
    private lateinit var settingsCoordinator: BootstrapSettingsSettingsCoordinator
    private lateinit var dataCoordinator: BootstrapSettingsDataCoordinator
    private lateinit var extensionsCoordinator: BootstrapSettingsExtensionsCoordinator
    private lateinit var logsCoordinator: BootstrapSettingsLogsCoordinator
    private lateinit var appUpdateCoordinator: AppUpdateCoordinator
    private var shouldStartBootstrap = false
    private var shouldReloadTavernUi = false

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

    private var pendingLogExportFileName: String? = null
    private val exportLogLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { targetUri ->
        val requestedFileName = pendingLogExportFileName
        pendingLogExportFileName = null
        if (targetUri != null && requestedFileName != null) {
            logsCoordinator.exportCurrentLog(targetUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_bootstrap_settings)
        bindViews()
        initializeControllers()
        screenController.initialize()
        extensionsCoordinator.initialize()
        logsCoordinator.initialize()
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
        floatingLogsSwitch.isChecked = hostConfigStore.floatingLogBubbleEnabled
        floatingLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
            hostConfigStore.floatingLogBubbleEnabled = isChecked
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

        screenController.setBusy(true)
        settingsCoordinator.loadConfiguration()

        if (intent.getBooleanExtra(openExtensionsTabKey, false) || intent.getBooleanExtra(openDefaultExtensionsInstallerKey, false)) {
            tabLayout.post {
                tabLayout.getTabAt(1)?.select()
                if (intent.getBooleanExtra(openDefaultExtensionsInstallerKey, false)) {
                    extensionsCoordinator.promptDefaultRepositoriesSelection()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appUpdateCoordinator.onStart()
    }

    override fun onStop() {
        appUpdateCoordinator.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        appUpdateCoordinator.onDestroy()
        super.onDestroy()
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
        tabLayout = findViewById(R.id.bootstrapSettingsTabs)
        scrollView = findViewById(R.id.bootstrapSettingsScrollView)
        actionBarView = findViewById(R.id.bootstrapSettingsActionBar)
        loadingIndicator = findViewById(R.id.bootstrapSettingsLoading)
        searchLayout = findViewById(R.id.bootstrapSettingsSearchLayout)
        searchInput = findViewById(R.id.bootstrapSettingsSearchInput)
        dataPanelView = findViewById(R.id.bootstrapSettingsDataPanel)
        quickFieldContainer = findViewById(R.id.bootstrapSettingsQuickFieldContainer)
        floatingLogsSwitch = findViewById(R.id.bootstrapSettingsFloatingLogsSwitch)
        extensionsPanelView = findViewById(R.id.bootstrapSettingsExtensionsPanel)
        extensionsListContainer = findViewById(R.id.bootstrapSettingsExtensionsListContainer)
        extensionsEmptyView = findViewById(R.id.bootstrapSettingsExtensionsEmpty)
        extensionsInstallButton = findViewById(R.id.bootstrapSettingsExtensionsInstallButton)
        extensionsReloadButton = findViewById(R.id.bootstrapSettingsExtensionsReloadButton)
        extensionsProgressIndicator = findViewById(R.id.bootstrapSettingsExtensionsProgress)
        extensionsProgressLabel = findViewById(R.id.bootstrapSettingsExtensionsProgressLabel)
        logsPanelView = findViewById(R.id.bootstrapSettingsLogsPanel)
        logsScrollView = findViewById(R.id.bootstrapSettingsLogsScrollView)
        logsScrollToBottomButton = findViewById(R.id.bootstrapSettingsLogsScrollToBottomButton)
        logsMetaView = findViewById(R.id.bootstrapSettingsLogsMeta)
        logsEmptyView = findViewById(R.id.bootstrapSettingsLogsEmpty)
        logsContentView = findViewById(R.id.bootstrapSettingsLogsContent)
        logsSelectButton = findViewById(R.id.bootstrapSettingsLogsSelectButton)
        logsExportButton = findViewById(R.id.bootstrapSettingsLogsExportButton)
        logsReloadButton = findViewById(R.id.bootstrapSettingsLogsReloadButton)
        settingsPanelView = findViewById(R.id.bootstrapSettingsSettingsPanel)
        aboutPanelView = findViewById(R.id.bootstrapSettingsAboutPanel)
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
            actionBarView = actionBarView,
            tabLayout = tabLayout,
            dataPanelView = dataPanelView,
            extensionsPanelView = extensionsPanelView,
            logsPanelView = logsPanelView,
            logsScrollView = logsScrollView,
            settingsPanelView = settingsPanelView,
            aboutPanelView = aboutPanelView,
            configPathView = configPathView,
            warningView = warningView,
            loadingIndicator = loadingIndicator,
            searchLayout = searchLayout,
            floatingLogsSwitch = floatingLogsSwitch,
            restoreDefaultsButton = restoreDefaultsButton,
            importButton = importButton,
            exportButton = exportButton,
            clearDataButton = clearDataButton,
            saveStartButton = saveStartButton,
            onTabChanged = { index ->
                if (this::extensionsCoordinator.isInitialized && index == 1) {
                    extensionsCoordinator.reloadExtensions()
                } else if (this::logsCoordinator.isInitialized && index == 2) {
                    logsCoordinator.reloadLatestLog()
                }
            }
        )
        appUpdateCoordinator = AppUpdateCoordinator(
            activity = this,
            downloadManager = downloadManager,
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
            onFieldEdited = { changedFieldPath -> settingsCoordinator.clearBlockingFeedback(changedFieldPath) },
            onFormChanged = { settingsCoordinator.refreshDirtyState() }
        )
        settingsCoordinator = BootstrapSettingsSettingsCoordinator(
            activity = this,
            configRepository = configRepository,
            formController = formController,
            screenController = screenController,
            processManager = processManager,
            onStartBootstrapConfirmed = {
                updateResultFlags(shouldStartBootstrap = true)
                finish()
            }
        )
        dataCoordinator = BootstrapSettingsDataCoordinator(
            activity = this,
            configRepository = configRepository,
            archiveManager = archiveManager,
            hostConfigStore = hostConfigStore,
            processManager = processManager,
            setBusy = screenController::setBusy,
            applyDraft = settingsCoordinator::applyDraftConfiguration,
            replaceLoadedConfiguration = settingsCoordinator::replaceLoadedConfiguration,
            showDataError = settingsCoordinator::showValidationMessage,
            showBanner = { message -> screenController.showBanner(message) },
            showMessage = screenController::showMessage,
            updateDirtyState = settingsCoordinator::refreshDirtyState,
            onBootstrapRestartRequired = {
                updateResultFlags(shouldStartBootstrap = true)
                finish()
            },
            onTavernUiReloadRequired = {
                updateResultFlags(shouldReloadTavernUi = true)
            }
        )
        extensionsCoordinator = BootstrapSettingsExtensionsCoordinator(
            activity = this,
            listContainer = extensionsListContainer,
            emptyView = extensionsEmptyView,
            installButton = extensionsInstallButton,
            reloadButton = extensionsReloadButton,
            progressIndicator = extensionsProgressIndicator,
            progressLabel = extensionsProgressLabel,
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
            metaView = logsMetaView,
            emptyView = logsEmptyView,
            contentView = logsContentView,
            logsScrollView = logsScrollView,
            scrollToBottomButton = logsScrollToBottomButton,
            selectButton = logsSelectButton,
            exportButton = logsExportButton,
            reloadButton = logsReloadButton,
            setBusy = screenController::setBusy,
            showError = settingsCoordinator::showValidationMessage,
            showMessage = screenController::showMessage,
            requestExport = ::requestLogExport
        )
    }

    private fun updateResultFlags(
        shouldStartBootstrap: Boolean = this.shouldStartBootstrap,
        shouldReloadTavernUi: Boolean = this.shouldReloadTavernUi
    ) {
        this.shouldStartBootstrap = this.shouldStartBootstrap || shouldStartBootstrap
        this.shouldReloadTavernUi = this.shouldReloadTavernUi || shouldReloadTavernUi
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(resultShouldStartKey, this.shouldStartBootstrap)
                .putExtra(resultShouldReloadTavernUiKey, this.shouldReloadTavernUi)
        )
    }

    private fun requestLogExport(fileName: String) {
        pendingLogExportFileName = fileName
        exportLogLauncher.launch(fileName)
    }
}
