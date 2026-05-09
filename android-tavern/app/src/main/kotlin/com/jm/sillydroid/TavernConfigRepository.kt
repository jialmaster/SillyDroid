package com.jm.sillydroid

import android.content.Context
import java.io.File
import java.util.LinkedHashMap
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

internal data class LoadedTavernConfig(
    val root: LinkedHashMap<String, Any?>,
    val filePath: String,
    val warningMessage: String?
)

internal class TavernConfigRepository(context: Context) {
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

    fun loadConfig(): LoadedTavernConfig {
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

    fun loadDefaultConfig(): LoadedTavernConfig {
        val configFile = ensureUserConfigFile()
        return LoadedTavernConfig(
            root = loadDefaultConfigRoot(),
            filePath = configFile.absolutePath,
            warningMessage = "当前表单已切换为默认配置草稿，尚未写回磁盘。"
        )
    }

    fun ensureUserConfigFile(): File {
        val paths = HostPaths.from(appContext)
        AssetExtractor(appContext).extractBootstrap(paths)
        paths.ensureWorkingDirectories()
        val configDirectory = File(paths.serverDataDir, "config")
        val configFile = File(configDirectory, "config.yaml")
        if (!configFile.isFile) {
            configDirectory.mkdirs()
            val defaultConfigFile = resolveDefaultConfigFile(paths)
            if (!defaultConfigFile.isFile) {
                throw BootstrapException("缺少默认 Tavern 配置模板：${defaultConfigFile.absolutePath}")
            }
            defaultConfigFile.copyTo(configFile, overwrite = true)
        }
        return configFile
    }

    fun readValue(root: LinkedHashMap<String, Any?>, path: String): Any? {
        var current: Any? = root
        for (segment in path.split('.')) {
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }

    fun writeValue(root: LinkedHashMap<String, Any?>, path: String, value: Any?) {
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

    fun copyRoot(root: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
        return normalizeMap(root)
    }

    fun validateConfig(root: LinkedHashMap<String, Any?>): String? {
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

    fun saveConfig(root: LinkedHashMap<String, Any?>) {
        val validationError = validateConfig(root)
        if (validationError != null) {
            throw BootstrapException(validationError)
        }
        val configFile = ensureUserConfigFile()
        configFile.writeText(renderConfig(root))
        BootstrapHostConfigStore(appContext).servicePort = readConfiguredPort(root)
    }

    fun readConfiguredPort(root: LinkedHashMap<String, Any?>): Int {
        val rawValue = readValue(root, "port")
        val resolvedValue = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull() ?: BootConfig.defaultServicePort
            else -> BootConfig.defaultServicePort
        }
        return resolvedValue.coerceIn(1, 65535)
    }

    fun syncStoredPortFromFile() {
        BootstrapHostConfigStore(appContext).servicePort = readConfiguredPort(loadConfig().root)
    }

    private fun loadDefaultConfigRoot(): LinkedHashMap<String, Any?> {
        val paths = HostPaths.from(appContext)
        AssetExtractor(appContext).extractBootstrap(paths)
        val defaultConfigFile = resolveDefaultConfigFile(paths)
        if (!defaultConfigFile.isFile) {
            throw BootstrapException("缺少默认 Tavern 配置模板：${defaultConfigFile.absolutePath}")
        }
        return parseConfigText(defaultConfigFile.readText())
    }

    private fun resolveDefaultConfigFile(paths: HostPaths): File {
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
