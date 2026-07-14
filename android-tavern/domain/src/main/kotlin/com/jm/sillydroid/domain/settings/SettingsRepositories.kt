package com.jm.sillydroid.domain.settings

import android.net.Uri
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.FloatingBrowserPosition
import com.jm.sillydroid.core.model.settings.FloatingLogBubblePosition
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.core.model.settings.LoadedTavernConfig
import com.jm.sillydroid.core.model.settings.TavernServerLaunchMode
import com.jm.sillydroid.core.model.settings.TavernConfigFieldSpec
import com.jm.sillydroid.core.model.settings.TavernConfigSectionSpec
import com.jm.sillydroid.core.model.settings.TavernDataArchiveKind
import com.jm.sillydroid.core.model.settings.TavernDataArchivePreview
import com.jm.sillydroid.core.model.settings.TavernDataImportResult
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingOverrides
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
    // 本地 Node 服务 V8 老生代堆上限（MB）；0 表示自动（不注入 --max-old-space-size）。
    var nodeMaxOldSpaceMb: Int
    // 本地 Node 服务 V8 新生代 semi-space 上限（MB）；0 表示自动（不注入 --max-semi-space-size）。
    var nodeMaxSemiSpaceMb: Int
    var hostDisplayMode: HostDisplayMode
    var browserEngine: BrowserEngine
    // 字体缩放：沿用 WebView textZoom / Gecko fontSizeFactor，只影响文字字号。
    var browserZoomPercent: Int
    // 界面密度：独立于字体缩放，System WebView 通过 HTML viewport width 调整同屏 CSS 像素容量。
    var browserPageZoomPercent: Int
    var launchWebViewOnReady: Boolean
    var backgroundHealthCheckEnabled: Boolean
    // Tavern 服务启动模式只调整宿主暴露给服务进程的命令环境，不改写酒馆源码或 config.yaml。
    var tavernServerLaunchMode: TavernServerLaunchMode
    // 兼容旧调用方：AUTO/FAST 都视为“快速路径”，只有 FULL 关闭快速路径。
    var tavernServerFastLaunchEnabled: Boolean
        get() = tavernServerLaunchMode != TavernServerLaunchMode.FULL
        set(value) {
            tavernServerLaunchMode = if (value) {
                TavernServerLaunchMode.AUTO
            } else {
                TavernServerLaunchMode.FULL
            }
        }
    // 是否启用 SillyDroid 对 Tavern 的运行时 patch 预设；默认关，开启后需重启本地服务生效。
    var tavernRuntimePatchEnabled: Boolean
    // 用户在 runtime patch 总开关开启后，手动关闭的模块 id；默认空表示使用预设默认模块。
    var tavernRuntimePatchDisabledModuleIds: Set<String>
    // Runtime patch 模块设置覆盖值，按 moduleId -> settingKey -> value 保存；具体类型由模块 manifest 声明并在 loader 侧校验。
    var tavernRuntimePatchSettingOverrides: RuntimePatchSettingOverrides
    var webViewPullRefreshEnabled: Boolean
    var debugDiagnosticsEnabled: Boolean
    var unrestrictedFileImportSelectionEnabled: Boolean
    var terminalFontSizePx: Int
    var terminalCursorBlinkEnabled: Boolean
    var terminalExtraKeysEnabled: Boolean
    // 后台悬浮浏览器必须在显式 overlay 授权后由设置页写入；默认关闭。
    var floatingBrowserEnabled: Boolean
    // 系统 overlay 球的位置独立保存，不能复用应用内悬浮日志球位置。
    var floatingBrowserPosition: FloatingBrowserPosition?
    var floatingLogBubbleEnabled: Boolean
    var floatingLogRefreshIntervalMillis: Int
    var floatingLogBubblePosition: FloatingLogBubblePosition?
    var defaultExtensionsPromptConsumed: Boolean
    var crashLogUploadEnabled: Boolean
    var crashLogUploadPromptConsumed: Boolean
    var lastCrashLogAutoUploadKey: String?
    var pendingRendererGoneAutoUploadKey: String?
    var pendingRendererGoneAutoUploadCrashType: String?
    var pendingRendererGoneAutoUploadNotes: String?
}

interface DataArchiveRepository {
    fun inspectDataArchive(sourceUri: Uri): TavernDataArchivePreview
    fun importDataArchive(sourceUri: Uri): TavernDataImportResult
    fun exportDataArchive(targetUri: Uri, kind: TavernDataArchiveKind = TavernDataArchiveKind.HOST_FULL_SNAPSHOT)
    fun clearManagedBootstrapState()
}
