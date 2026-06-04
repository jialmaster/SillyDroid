package com.jm.sillydroid.feature.settings.viewmodel

import com.jm.sillydroid.core.model.settings.BrowserDataClearTarget
import com.jm.sillydroid.core.model.settings.HostDisplayMode
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

    private class FakeHostPreferencesRepository : HostPreferencesRepository {
        override var servicePort: Int = 8000
        override var hostDisplayMode: HostDisplayMode = HostDisplayMode.NORMAL
        override var launchWebViewOnReady: Boolean = true
        override var backgroundHealthCheckEnabled: Boolean = false
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
    }
}
