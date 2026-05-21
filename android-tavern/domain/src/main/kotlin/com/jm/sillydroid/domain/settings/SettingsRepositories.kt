package com.jm.sillydroid.domain.settings

import android.net.Uri
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.model.settings.LoadedTavernConfig
import com.jm.sillydroid.core.model.settings.TavernConfigFieldSpec
import com.jm.sillydroid.core.model.settings.TavernConfigSectionSpec
import com.jm.sillydroid.core.model.settings.TavernDataArchiveKind
import com.jm.sillydroid.core.model.settings.TavernDataArchivePreview
import com.jm.sillydroid.core.model.settings.TavernDataImportResult
import java.util.LinkedHashMap

interface SettingsConfigRepository {
    val sections: List<TavernConfigSectionSpec>
    val allFields: List<TavernConfigFieldSpec>
    val fieldsByPath: Map<String, TavernConfigFieldSpec>

    fun loadConfig(): LoadedTavernConfig
    fun loadDefaultConfig(): LoadedTavernConfig
    fun readValue(root: LinkedHashMap<String, Any?>, path: String): Any?
    fun writeValue(root: LinkedHashMap<String, Any?>, path: String, value: Any?)
    fun copyRoot(root: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?>
    fun validateConfig(root: LinkedHashMap<String, Any?>): String?
    fun saveConfig(root: LinkedHashMap<String, Any?>)
    fun readConfiguredPort(root: LinkedHashMap<String, Any?>): Int
    fun syncStoredPortFromFile()
}

interface HostPreferencesRepository {
    var servicePort: Int
    var hostDisplayMode: HostDisplayMode
    var webViewPullRefreshEnabled: Boolean
    var debugDiagnosticsEnabled: Boolean
    var unrestrictedFileImportSelectionEnabled: Boolean
    var terminalFontSizePx: Int
    var terminalCursorBlinkEnabled: Boolean
    var terminalExtraKeysEnabled: Boolean
    var floatingLogBubbleEnabled: Boolean
    var floatingLogRefreshIntervalMillis: Int
    var floatingLogBubblePosition: FloatingLogBubblePosition?
    var defaultExtensionsPromptConsumed: Boolean
}

interface DataArchiveRepository {
    fun inspectDataArchive(sourceUri: Uri): TavernDataArchivePreview
    fun importDataArchive(sourceUri: Uri): TavernDataImportResult
    fun exportDataArchive(targetUri: Uri, kind: TavernDataArchiveKind = TavernDataArchiveKind.HOST_FULL_SNAPSHOT)
    fun clearManagedBootstrapState()
}
