package com.jm.sillydroid.data.settings

import android.content.Context
import com.jm.sillydroid.core.model.bootstrap.defaultBootstrapServicePort
import com.jm.sillydroid.core.model.settings.LoadedTavernConfig
import com.jm.sillydroid.core.model.settings.TavernConfigFieldSpec
import com.jm.sillydroid.core.model.settings.TavernConfigSectionSpec
import com.jm.sillydroid.domain.settings.SettingsConfigRepository
import java.io.File
import java.util.LinkedHashMap
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class TavernConfigRepository(context: Context) : SettingsConfigRepository {
    companion object {
        val managedTopLevelDirectories: List<String> = listOf("config", "data", "plugins", "extensions")
    }

    private val appContext = context.applicationContext
    private val yaml = Yaml(
        DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 1
            splitLines = false
        }
    )

    override val sections: List<TavernConfigSectionSpec>
        get() = TavernConfigSchema.sections

    override val allFields: List<TavernConfigFieldSpec>
        get() = TavernConfigSchema.allFields

    override val fieldsByPath: Map<String, TavernConfigFieldSpec>
        get() = TavernConfigSchema.fieldsByPath

    override fun loadConfig(): LoadedTavernConfig {
        val configFile = ensureUserConfigFile()
        val persistedText = configFile.readText()
        return try {
            val root = if (persistedText.isBlank()) {
                loadDefaultConfigRoot()
            } else {
                parseConfigText(persistedText)
            }
            LoadedTavernConfig(
                root = root,
                filePath = configFile.absolutePath,
                warningMessage = if (persistedText.isBlank()) "当前用户配置为空，已按默认模板载入。" else null
            )
        } catch (_: Exception) {
            LoadedTavernConfig(
                root = loadDefaultConfigRoot(),
                filePath = configFile.absolutePath,
                warningMessage = "当前 config.yaml 无法解析，已按默认模板载入。保存后会覆盖损坏配置。"
            )
        }
    }

    override fun loadDefaultConfig(): LoadedTavernConfig {
        val configFile = ensureUserConfigFile()
        return LoadedTavernConfig(
            root = loadDefaultConfigRoot(),
            filePath = configFile.absolutePath,
            warningMessage = "当前表单已切换为默认配置草稿，尚未写回磁盘。"
        )
    }

    fun ensureUserConfigFile(): File {
        val paths = TavernStoragePaths.from(appContext)
        val configDirectory = File(paths.serverDataDir, "config")
        val configFile = File(configDirectory, "config.yaml")
        if (!configFile.isFile) {
            val defaultConfigFile = resolveDefaultConfigFile(paths)
            if (!defaultConfigFile.isFile) {
                // 环境尚未初始化（server 尚未解包），不在此处触发 extractBootstrap，
                // 交由 StartupCoordinatorService 负责初始化流程。
                throw SettingsDataException("Tavern 环境尚未初始化，请先完成启动流程再打开设置。")
            }
            configDirectory.mkdirs()
            defaultConfigFile.copyTo(configFile, overwrite = true)
        }
        return configFile
    }

    override fun readValue(root: LinkedHashMap<String, Any?>, path: String): Any? {
        var current: Any? = root
        for (segment in path.split('.')) {
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }

    override fun writeValue(root: LinkedHashMap<String, Any?>, path: String, value: Any?) {
        val segments = path.split('.')
        var current = root as MutableMap<String, Any?>
        for (segment in segments.dropLast(1)) {
            val next = current[segment]
            val nextMap = if (next is Map<*, *>) {
                normalizeMap(next)
            } else {
                linkedMapOf<String, Any?>()
            }
            current[segment] = nextMap
            current = nextMap
        }
        current[segments.last()] = normalizeNode(value)
    }

    override fun copyRoot(root: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
        return normalizeMap(root)
    }

    override fun validateConfig(root: LinkedHashMap<String, Any?>): String? {
        return try {
            val renderedYaml = renderConfig(root)
            val reparsed = yaml.load<Any?>(renderedYaml)
            if (reparsed !is Map<*, *>) {
                "配置文件序列化结果不是合法对象。"
            } else {
                null
            }
        } catch (exception: Exception) {
            "配置文件校验失败：${exception.message ?: exception.javaClass.simpleName}"
        }
    }

    override fun saveConfig(root: LinkedHashMap<String, Any?>) {
        val validationError = validateConfig(root)
        if (validationError != null) {
            throw SettingsDataException(validationError)
        }
        val configFile = ensureUserConfigFile()
        configFile.writeText(renderConfig(root))
        BootstrapHostConfigStore(appContext).servicePort = readConfiguredPort(root)
    }

    override fun readConfiguredPort(root: LinkedHashMap<String, Any?>): Int {
        val rawValue = readValue(root, "port")
        val resolvedValue = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull() ?: defaultBootstrapServicePort
            else -> defaultBootstrapServicePort
        }
        return resolvedValue.coerceIn(1, 65535)
    }

    override fun syncStoredPortFromFile() {
        BootstrapHostConfigStore(appContext).servicePort = readConfiguredPort(loadConfig().root)
    }

    private fun loadDefaultConfigRoot(): LinkedHashMap<String, Any?> {
        val paths = TavernStoragePaths.from(appContext)
        val defaultConfigFile = resolveDefaultConfigFile(paths)
        if (!defaultConfigFile.isFile) {
            throw SettingsDataException("缺少默认 Tavern 配置模板：${defaultConfigFile.absolutePath}")
        }
        return parseConfigText(defaultConfigFile.readText())
    }

    private fun resolveDefaultConfigFile(paths: TavernStoragePaths): File {
        return File(paths.serverDir, "default/config.yaml")
    }

    private fun parseConfigText(rawText: String): LinkedHashMap<String, Any?> {
        return normalizeMap(yaml.load<Any?>(rawText))
    }

    private fun renderConfig(root: LinkedHashMap<String, Any?>): String {
        return yaml.dump(root)
    }

    private fun normalizeMap(node: Any?): LinkedHashMap<String, Any?> {
        val normalized = linkedMapOf<String, Any?>()
        val source = node as? Map<*, *> ?: return normalized
        for ((key, value) in source) {
            val normalizedKey = key?.toString() ?: continue
            normalized[normalizedKey] = normalizeNode(value)
        }
        return normalized
    }

    private fun normalizeNode(node: Any?): Any? {
        return when (node) {
            is Map<*, *> -> normalizeMap(node)
            is Iterable<*> -> node.map { normalizeNode(it) }.toMutableList()
            is Array<*> -> node.map { normalizeNode(it) }.toMutableList()
            else -> node
        }
    }
}
