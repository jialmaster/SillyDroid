package com.jm.sillydroid.feature.settings.validation

import com.jm.sillydroid.core.model.settings.TavernConfigFieldKind
import com.jm.sillydroid.core.model.settings.TavernConfigFieldSpec
import com.jm.sillydroid.feature.settings.model.BootstrapSettingsValidationIssue

// 统一收敛宿主设置表单的类型收敛与跨字段校验，保证保存与重启前的约束一致。
object BootstrapSettingsFormValidator {
    fun coerceFieldValue(field: TavernConfigFieldSpec, rawValue: Any?): Any {
        return when (field.kind) {
            TavernConfigFieldKind.BOOLEAN -> rawValue as? Boolean ?: false
            TavernConfigFieldKind.INTEGER -> {
                val rawText = rawValue?.toString().orEmpty().trim()
                if (rawText.isEmpty()) {
                    field.defaultValue as? Int ?: 0
                } else {
                    rawText.toIntOrNull() ?: throw IllegalArgumentException("${field.title} 必须是整数。")
                }
            }

            TavernConfigFieldKind.STRING_LIST -> rawValue?.toString().orEmpty()
                .lineSequence()
                .map { line -> line.trim() }
                .filter { line -> line.isNotBlank() }
                .toList()

            TavernConfigFieldKind.MULTILINE_TEXT -> rawValue?.toString().orEmpty().trim()
            else -> rawValue?.toString().orEmpty().trim()
        }
    }

    fun validate(values: Map<String, Any?>, defaultServicePort: Int): BootstrapSettingsValidationIssue? {
        val servicePort = intValue(values, "port", defaultServicePort)
        if (servicePort !in 1..65535) {
            return BootstrapSettingsValidationIssue("port", "启动端口必须在 1 到 65535 之间。")
        }

        val browserLaunchEnabled = booleanValue(values, "browserLaunch.enabled")
        val browserLaunchPort = intValue(values, "browserLaunch.port", -1)
        if (browserLaunchPort != -1 && browserLaunchPort !in 1..65535) {
            return BootstrapSettingsValidationIssue("browserLaunch.port", "浏览器端口覆盖必须是 -1 或 1 到 65535。")
        }

        if (browserLaunchEnabled && stringValue(values, "browserLaunch.hostname").isBlank()) {
            return BootstrapSettingsValidationIssue("browserLaunch.hostname", "启用浏览器自动打开时必须填写浏览器主机名。")
        }

        val heartbeatInterval = intValue(values, "heartbeatInterval", 0)
        if (heartbeatInterval < 0) {
            return BootstrapSettingsValidationIssue("heartbeatInterval", "心跳写入间隔不能小于 0。")
        }

        val ipv4Enabled = booleanValue(values, "protocol.ipv4", true)
        val ipv6Enabled = booleanValue(values, "protocol.ipv6")
        if (!ipv4Enabled && !ipv6Enabled) {
            return BootstrapSettingsValidationIssue("protocol.ipv4", "IPv4 和 IPv6 至少要启用一个。")
        }

        if (booleanValue(values, "ssl.enabled")) {
            if (stringValue(values, "ssl.certPath").isBlank()) {
                return BootstrapSettingsValidationIssue("ssl.certPath", "启用 SSL 时必须填写证书路径。")
            }
            if (stringValue(values, "ssl.keyPath").isBlank()) {
                return BootstrapSettingsValidationIssue("ssl.keyPath", "启用 SSL 时必须填写私钥路径。")
            }
        }

        val whitelistModeEnabled = booleanValue(values, "whitelistMode")
        val whitelistEntries = listValue(values, "whitelist")
        if (whitelistModeEnabled && whitelistEntries.isEmpty()) {
            return BootstrapSettingsValidationIssue("whitelist", "启用白名单后至少要保留一个地址。")
        }

        val basicAuthEnabled = booleanValue(values, "basicAuthMode")
        val basicAuthUser = stringValue(values, "basicAuthUser.username")
        val basicAuthPassword = stringValue(values, "basicAuthUser.password")
        if (basicAuthEnabled && (basicAuthUser.isBlank() || basicAuthPassword.isBlank())) {
            return BootstrapSettingsValidationIssue("basicAuthUser.username", "启用基础认证时必须填写用户名和密码。")
        }

        if (booleanValue(values, "perUserBasicAuth") && (!basicAuthEnabled || !booleanValue(values, "enableUserAccounts"))) {
            return BootstrapSettingsValidationIssue("perUserBasicAuth", "按用户复用基础认证需要同时启用基础认证和多用户账户。")
        }

        if (booleanValue(values, "cors.enabled") && listValue(values, "cors.origin").isEmpty()) {
            return BootstrapSettingsValidationIssue("cors.origin", "启用 CORS 中间件时至少保留一个允许的 Origin。")
        }

        val proxyEnabled = booleanValue(values, "requestProxy.enabled")
        val proxyUrl = stringValue(values, "requestProxy.url")
        if (proxyEnabled && proxyUrl.isBlank()) {
            return BootstrapSettingsValidationIssue("requestProxy.url", "启用请求代理后必须填写代理地址。")
        }

        if ((booleanValue(values, "sso.autheliaAuth") || booleanValue(values, "sso.authentikAuth")) && listValue(values, "sso.trustedProxies").isEmpty()) {
            return BootstrapSettingsValidationIssue("sso.trustedProxies", "启用 SSO 自动登录后至少要保留一个可信代理。")
        }

        if (booleanValue(values, "hostWhitelist.enabled") && listValue(values, "hostWhitelist.hosts").isEmpty()) {
            return BootstrapSettingsValidationIssue("hostWhitelist.hosts", "启用 Host 白名单后至少要保留一个 Host。")
        }

        if (booleanValue(values, "privateAddressWhitelist.enabled") && listValue(values, "privateAddressWhitelist.allowedRanges").isEmpty()) {
            return BootstrapSettingsValidationIssue("privateAddressWhitelist.allowedRanges", "启用私网地址白名单后至少要保留一个允许地址段。")
        }

        val logLevel = intValue(values, "logging.minLogLevel", 0)
        if (logLevel !in 0..3) {
            return BootstrapSettingsValidationIssue("logging.minLogLevel", "最小日志级别只能是 0 到 3。")
        }

        for (fieldPath in listOf(
            "rateLimiting.basicAuthMaxAttempts",
            "rateLimiting.accountsLoginMaxAttempts",
            "rateLimiting.accountsRecoverMaxAttempts"
        )) {
            if (intValue(values, fieldPath, 0) < 0) {
                return BootstrapSettingsValidationIssue(fieldPath, "登录与恢复相关限流次数不能小于 0。")
            }
        }

        if (intValue(values, "backups.common.numberOfBackups", 1) < 1) {
            return BootstrapSettingsValidationIssue("backups.common.numberOfBackups", "单文件保留备份数至少为 1。")
        }

        if (intValue(values, "backups.chat.maxTotalBackups", -1) < -1) {
            return BootstrapSettingsValidationIssue("backups.chat.maxTotalBackups", "聊天备份总上限只能为 -1 或更大的整数。")
        }

        if (intValue(values, "backups.chat.throttleInterval", 0) < 0) {
            return BootstrapSettingsValidationIssue("backups.chat.throttleInterval", "聊天备份节流毫秒不能小于 0。")
        }

        if (booleanValue(values, "thumbnails.enabled")) {
            val thumbnailQuality = intValue(values, "thumbnails.quality", 95)
            if (thumbnailQuality !in 0..100) {
                return BootstrapSettingsValidationIssue("thumbnails.quality", "缩略图质量必须在 0 到 100 之间。")
            }
        }

        if (booleanValue(values, "performance.requestCompression.enabled") && intValue(values, "performance.requestCompression.timeout", 0) <= 0) {
            return BootstrapSettingsValidationIssue("performance.requestCompression.timeout", "启用请求压缩后，压缩超时毫秒必须大于 0。")
        }

        if (booleanValue(values, "extensions.enabled") && booleanValue(values, "extensions.models.autoDownload")) {
            for (fieldPath in listOf(
                "extensions.models.classification",
                "extensions.models.captioning",
                "extensions.models.embedding",
                "extensions.models.speechToText",
                "extensions.models.textToSpeech"
            )) {
                if (stringValue(values, fieldPath).isBlank()) {
                    return BootstrapSettingsValidationIssue(fieldPath, "启用扩展模型自动下载后，每个模型 ID 都必须填写。")
                }
            }
        }

        val gitBackend = stringValue(values, "git.backend")
        if (gitBackend !in setOf("auto", "system", "builtin")) {
            return BootstrapSettingsValidationIssue("git.backend", "Git 后端只能是 auto、system 或 builtin。")
        }

        val deeplFormality = stringValue(values, "deepl.formality")
        if (deeplFormality !in setOf("default", "more", "less", "prefer_more", "prefer_less")) {
            return BootstrapSettingsValidationIssue("deepl.formality", "DeepL 正式度只能是 default、more、less、prefer_more 或 prefer_less。")
        }

        val ollamaKeepAlive = intValue(values, "ollama.keepAlive", -1)
        if (ollamaKeepAlive < -1) {
            return BootstrapSettingsValidationIssue("ollama.keepAlive", "Ollama 保活秒数只能是 -1 或更大的整数。")
        }

        val ollamaBatchSize = intValue(values, "ollama.batchSize", -1)
        if (ollamaBatchSize != -1 && (ollamaBatchSize <= 0 || (ollamaBatchSize and (ollamaBatchSize - 1)) != 0)) {
            return BootstrapSettingsValidationIssue("ollama.batchSize", "Ollama batch size 只能是 -1 或 2 的幂。")
        }

        if (intValue(values, "claude.cachingAtDepth", -1) < -1) {
            return BootstrapSettingsValidationIssue("claude.cachingAtDepth", "Claude 缓存深度只能是 -1 或更大的整数。")
        }

        val geminiApiVersion = stringValue(values, "gemini.apiVersion")
        if (geminiApiVersion !in setOf("v1beta", "v1alpha")) {
            return BootstrapSettingsValidationIssue("gemini.apiVersion", "Gemini API 版本只能是 v1beta 或 v1alpha。")
        }

        if (stringValue(values, "gemini.image.personGeneration").isBlank()) {
            return BootstrapSettingsValidationIssue("gemini.image.personGeneration", "Gemini 图像人物生成策略不能为空。")
        }

        return null
    }

    private fun intValue(values: Map<String, Any?>, path: String, defaultValue: Int): Int {
        return when (val rawValue = values[path]) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun booleanValue(values: Map<String, Any?>, path: String, defaultValue: Boolean = false): Boolean {
        return when (val rawValue = values[path]) {
            is Boolean -> rawValue
            is String -> rawValue.equals("true", ignoreCase = true)
            else -> defaultValue
        }
    }

    private fun stringValue(values: Map<String, Any?>, path: String, defaultValue: String = ""): String {
        return values[path]?.toString()?.trim().orEmpty().ifBlank { defaultValue }
    }

    private fun listValue(values: Map<String, Any?>, path: String): List<String> {
        return (values[path] as? Iterable<*>)
            ?.mapNotNull { item -> item?.toString()?.trim() }
            ?.filter { item -> item.isNotBlank() }
            .orEmpty()
    }
}
