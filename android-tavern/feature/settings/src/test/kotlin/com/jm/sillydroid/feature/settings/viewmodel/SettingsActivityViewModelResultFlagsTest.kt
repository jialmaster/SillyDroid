package com.jm.sillydroid.feature.settings.viewmodel

import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.BrowserDataClearTarget
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverrides
import com.jm.sillydroid.domain.settings.HostPreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsActivityViewModelResultFlagsTest {

    @Test
    fun `markResultFlags keeps force fresh webview load flag once requested`() {
        val viewModel = SettingsActivityViewModel(FakeHostPreferencesRepository())

        viewModel.markResultFlags(shouldForceFreshWebViewLoad = true)
        viewModel.markResultFlags(shouldReloadTavernUi = true)

        val state = viewModel.uiState.value
        assertTrue(state.shouldForceFreshWebViewLoad)
        assertTrue(state.shouldReloadTavernUi)
        assertFalse(state.shouldStartBootstrap)
    }

    @Test
    fun `markResultFlags stores selected browser clear mask`() {
        val viewModel = SettingsActivityViewModel(FakeHostPreferencesRepository())

        viewModel.markResultFlags(
            shouldForceFreshWebViewLoad = true,
            browserDataClearMask = BrowserDataClearTarget.RESOURCE_CACHE.mask
        )

        val state = viewModel.uiState.value
        assertTrue(state.shouldForceFreshWebViewLoad)
        assertEquals(BrowserDataClearTarget.RESOURCE_CACHE.mask, state.browserDataClearMask)
    }

    @Test
    fun `background health check defaults off and persists toggle`() {
        val repository = FakeHostPreferencesRepository()
        val viewModel = SettingsActivityViewModel(repository)

        assertFalse(viewModel.uiState.value.backgroundHealthCheckEnabled)

        viewModel.setBackgroundHealthCheckEnabled(true)

        assertTrue(repository.backgroundHealthCheckEnabled)
        assertTrue(viewModel.uiState.value.backgroundHealthCheckEnabled)
    }

    @Test
    fun `changing browser engine marks main activity recreate result`() {
        val repository = FakeHostPreferencesRepository()
        val viewModel = SettingsActivityViewModel(repository)

        viewModel.setBrowserEngine(BrowserEngine.GECKOVIEW)

        assertEquals(BrowserEngine.GECKOVIEW, repository.browserEngine)
        assertEquals(BrowserEngine.GECKOVIEW, viewModel.uiState.value.browserEngine)
        assertTrue(viewModel.uiState.value.shouldRecreateMainActivity)
    }

    @Test
    fun `runtime patch defaults off and persists toggle`() {
        val repository = FakeHostPreferencesRepository()
        val viewModel = SettingsActivityViewModel(repository)

        assertFalse(viewModel.uiState.value.tavernRuntimePatchEnabled)

        val changed = viewModel.setTavernRuntimePatchEnabled(true)

        assertTrue(changed)
        assertTrue(repository.tavernRuntimePatchEnabled)
        assertTrue(viewModel.uiState.value.tavernRuntimePatchEnabled)
    }

    @Test
    fun `runtime patch module toggle persists disabled ids`() {
        val repository = FakeHostPreferencesRepository()
        val viewModel = SettingsActivityViewModel(repository)
        val moduleId = "character-all-limited-concurrency"

        val disabledChanged = viewModel.setRuntimePatchModuleEnabled(moduleId, false)
        assertTrue(disabledChanged)
        assertFalse(viewModel.isRuntimePatchModuleEnabled(moduleId))
        assertEquals(setOf(moduleId), repository.tavernRuntimePatchDisabledModuleIds)

        val enabledChanged = viewModel.setRuntimePatchModuleEnabled(moduleId, true)
        assertTrue(enabledChanged)
        assertTrue(viewModel.isRuntimePatchModuleEnabled(moduleId))
        assertTrue(repository.tavernRuntimePatchDisabledModuleIds.isEmpty())
    }

    @Test
    fun `runtime patch setting override persists by module and setting key`() {
        val repository = FakeHostPreferencesRepository()
        val viewModel = SettingsActivityViewModel(repository)
        val moduleId = "character-all-limited-concurrency"

        val changed = viewModel.setRuntimePatchSettingOverride(moduleId, "concurrency", "4")

        assertTrue(changed)
        assertEquals("4", viewModel.resolveRuntimePatchSettingValue(moduleId, "concurrency", "auto"))
        assertEquals(mapOf(moduleId to mapOf("concurrency" to "4")), repository.tavernRuntimePatchSettingOverrides)

        val removed = viewModel.setRuntimePatchSettingOverride(moduleId, "concurrency", "")

        assertTrue(removed)
        assertEquals("auto", viewModel.resolveRuntimePatchSettingValue(moduleId, "concurrency", "auto"))
        assertTrue(repository.tavernRuntimePatchSettingOverrides.isEmpty())
    }

    private class FakeHostPreferencesRepository : HostPreferencesRepository {
        override var servicePort: Int = 8000
        override var nodeMaxOldSpaceMb: Int = 0
        override var nodeMaxSemiSpaceMb: Int = 0
        override var hostDisplayMode: HostDisplayMode = HostDisplayMode.NORMAL
        override var browserEngine: BrowserEngine = BrowserEngine.SYSTEM_WEBVIEW
        override var browserZoomPercent: Int = 100
        override var launchWebViewOnReady: Boolean = true
        override var backgroundHealthCheckEnabled: Boolean = false
        override var tavernRuntimePatchEnabled: Boolean = false
        override var tavernRuntimePatchDisabledModuleIds: Set<String> = emptySet()
        override var tavernRuntimePatchSettingOverrides: RuntimePatchSettingOverrides = emptyMap()
        override var webViewPullRefreshEnabled: Boolean = true
        override var debugDiagnosticsEnabled: Boolean = false
        override var unrestrictedFileImportSelectionEnabled: Boolean = false
        override var terminalFontSizePx: Int = 14
        override var terminalCursorBlinkEnabled: Boolean = true
        override var terminalExtraKeysEnabled: Boolean = true
        override var floatingLogBubbleEnabled: Boolean = false
        override var floatingLogRefreshIntervalMillis: Int = 1_000
        override var floatingLogBubblePosition: com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition? = null
        override var defaultExtensionsPromptConsumed: Boolean = false
        override var crashLogUploadEnabled: Boolean = false
        override var crashLogUploadPromptConsumed: Boolean = false
        override var lastCrashLogAutoUploadKey: String? = null
        override var pendingRendererGoneAutoUploadKey: String? = null
        override var pendingRendererGoneAutoUploadCrashType: String? = null
        override var pendingRendererGoneAutoUploadNotes: String? = null
    }
}
