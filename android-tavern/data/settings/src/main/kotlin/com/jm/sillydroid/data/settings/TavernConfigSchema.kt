package com.jm.sillydroid.data.settings

import com.jm.sillydroid.core.model.bootstrap.defaultBootstrapServicePort
import com.jm.sillydroid.core.model.settings.TavernConfigFieldKind
import com.jm.sillydroid.core.model.settings.TavernConfigFieldSpec
import com.jm.sillydroid.core.model.settings.TavernConfigSectionSpec

object TavernConfigSchema {
    private fun booleanField(
        path: String,
        title: String,
        summary: String,
        defaultValue: Boolean,
        visibleWhenAllEnabled: List<String> = emptyList(),
        visibleWhenAnyEnabled: List<String> = emptyList()
    ) = TavernConfigFieldSpec(path, title, summary, TavernConfigFieldKind.BOOLEAN, defaultValue, visibleWhenAllEnabled, visibleWhenAnyEnabled)

    private fun textField(
        path: String,
        title: String,
        summary: String,
        defaultValue: String,
        visibleWhenAllEnabled: List<String> = emptyList(),
        visibleWhenAnyEnabled: List<String> = emptyList()
    ) = TavernConfigFieldSpec(path, title, summary, TavernConfigFieldKind.TEXT, defaultValue, visibleWhenAllEnabled, visibleWhenAnyEnabled)

    private fun multilineField(
        path: String,
        title: String,
        summary: String,
        defaultValue: String,
        visibleWhenAllEnabled: List<String> = emptyList(),
        visibleWhenAnyEnabled: List<String> = emptyList()
    ) = TavernConfigFieldSpec(path, title, summary, TavernConfigFieldKind.MULTILINE_TEXT, defaultValue, visibleWhenAllEnabled, visibleWhenAnyEnabled)

    private fun integerField(
        path: String,
        title: String,
        summary: String,
        defaultValue: Int,
        visibleWhenAllEnabled: List<String> = emptyList(),
        visibleWhenAnyEnabled: List<String> = emptyList()
    ) = TavernConfigFieldSpec(path, title, summary, TavernConfigFieldKind.INTEGER, defaultValue, visibleWhenAllEnabled, visibleWhenAnyEnabled)

    private fun stringListField(
        path: String,
        title: String,
        summary: String,
        defaultValue: List<String>,
        visibleWhenAllEnabled: List<String> = emptyList(),
        visibleWhenAnyEnabled: List<String> = emptyList()
    ) = TavernConfigFieldSpec(path, title, summary, TavernConfigFieldKind.STRING_LIST, defaultValue, visibleWhenAllEnabled, visibleWhenAnyEnabled)

    private fun passwordField(
        path: String,
        title: String,
        summary: String,
        defaultValue: String,
        visibleWhenAllEnabled: List<String> = emptyList(),
        visibleWhenAnyEnabled: List<String> = emptyList()
    ) = TavernConfigFieldSpec(path, title, summary, TavernConfigFieldKind.PASSWORD, defaultValue, visibleWhenAllEnabled, visibleWhenAnyEnabled)

    val sections: List<TavernConfigSectionSpec> = listOf(
        TavernConfigSectionSpec(
            title = "启动与网络",
            summary = "控制本地 Tavern 服务的监听端口、主机访问方式和基础网络栈。",
            fields = listOf(
                integerField("port", "启动端口", "宿主 WebView 和本地服务都会使用这个端口。", defaultBootstrapServicePort),
                booleanField("listen", "允许外部监听", "Android 宿主默认建议关闭，只在明确需要局域网访问时开启。", false),
                textField("listenAddress.ipv4", "监听地址 IPv4", "通常保留 0.0.0.0。", "0.0.0.0", visibleWhenAllEnabled = listOf("listen")),
                textField("listenAddress.ipv6", "监听地址 IPv6", "通常保留 [::]。", "[::]", visibleWhenAllEnabled = listOf("listen")),
                booleanField("protocol.ipv4", "启用 IPv4", "至少要保留一个网络协议可用。", true),
                booleanField("protocol.ipv6", "启用 IPv6", "如果网络环境稳定支持 IPv6，可以开启。", false),
                booleanField("dnsPreferIPv6", "优先使用 IPv6 DNS", "在 IPv6 网络质量足够好时再开启。", false),
                booleanField("browserLaunch.enabled", "启用浏览器自动打开", "Android 宿主一般会关闭，由宿主 WebView 统一接管。", false),
                textField("browserLaunch.hostname", "浏览器主机名", "上游配置项，默认 auto。Android 宿主通常无需修改。", "auto", visibleWhenAllEnabled = listOf("browserLaunch.enabled")),
                integerField("browserLaunch.port", "浏览器端口覆盖", "填 -1 表示跟随服务端口。", -1, visibleWhenAllEnabled = listOf("browserLaunch.enabled")),
                booleanField("browserLaunch.avoidLocalhost", "避免使用 localhost", "当设备 hosts 中没有 localhost 时可以开启。", false, visibleWhenAllEnabled = listOf("browserLaunch.enabled")),
                integerField("heartbeatInterval", "心跳写入间隔", "单位秒，填 0 关闭心跳文件。", 0),
                booleanField("enableKeepAlive", "启用 HTTP Keep-Alive", "网络波动大时可临时关闭。", false)
            )
        ),
        TavernConfigSectionSpec(
            title = "SSL 与访问控制",
            summary = "限制访问来源、鉴权方式和账户登录行为，并管理 SSL 证书。",
            fields = listOf(
                booleanField("ssl.enabled", "启用 SSL/TLS", "在你准备好证书后再开启。", false),
                textField("ssl.certPath", "证书路径", "相对于服务根目录，例如 ./certs/cert.pem。", "./certs/cert.pem", visibleWhenAllEnabled = listOf("ssl.enabled")),
                textField("ssl.keyPath", "私钥路径", "相对于服务根目录，例如 ./certs/privkey.pem。", "./certs/privkey.pem", visibleWhenAllEnabled = listOf("ssl.enabled")),
                passwordField("ssl.keyPassphrase", "私钥密码", "私钥有密码时再填写。", "", visibleWhenAllEnabled = listOf("ssl.enabled")),
                booleanField("whitelistMode", "启用 IP 白名单", "开启后仅允许白名单中的地址访问。", true),
                booleanField("enableForwardedWhitelist", "识别转发头白名单", "仅在你确定前置代理可信时使用。", true, visibleWhenAllEnabled = listOf("whitelistMode")),
                booleanField("whitelistDockerHosts", "自动加入 Docker 主机地址", "本地 Android 宿主通常可保持默认。", true, visibleWhenAllEnabled = listOf("whitelistMode")),
                stringListField("whitelist", "白名单地址", "每行一个 IP 或 CIDR。", listOf("::1", "127.0.0.1"), visibleWhenAllEnabled = listOf("whitelistMode")),
                booleanField("basicAuthMode", "启用基础认证", "为管理端接口增加用户名密码。", false),
                textField("basicAuthUser.username", "基础认证用户名", "启用基础认证时必填。", "user", visibleWhenAllEnabled = listOf("basicAuthMode")),
                passwordField("basicAuthUser.password", "基础认证密码", "启用基础认证时必填。", "password", visibleWhenAllEnabled = listOf("basicAuthMode")),
                booleanField("enableUserAccounts", "启用多用户账户", "如需多账号隔离再开启。", false),
                booleanField("enableDiscreetLogin", "启用隐身登录页", "登录页不展示用户列表。", false, visibleWhenAllEnabled = listOf("enableUserAccounts")),
                booleanField("perUserBasicAuth", "按用户复用基础认证", "仅在多用户且基础认证都启用时有意义。", false, visibleWhenAllEnabled = listOf("enableUserAccounts", "basicAuthMode")),
                integerField("sessionTimeout", "会话超时秒数", "-1 表示不超时，0 表示浏览器关闭即失效。", -1),
                booleanField("disableCsrfProtection", "禁用 CSRF 防护", "不建议开启。", false),
                booleanField("securityOverride", "跳过启动安全检查", "仅用于调试排障。", false)
            )
        ),
        TavernConfigSectionSpec(
            title = "代理与跨域",
            summary = "控制 Tavern 出站代理、CORS 和转发头行为。",
            fields = listOf(
                booleanField("enableCorsProxy", "启用 CORS 代理", "让服务端充当跨域代理。", false),
                booleanField("cors.enabled", "启用 CORS 中间件", "默认保留开启即可。", true),
                stringListField("cors.origin", "允许的 CORS Origin", "每行一个 origin，默认保留 null。", listOf("null"), visibleWhenAllEnabled = listOf("cors.enabled")),
                stringListField("cors.methods", "允许的 CORS 方法", "每行一个 HTTP 方法。", listOf("OPTIONS"), visibleWhenAllEnabled = listOf("cors.enabled")),
                stringListField("cors.allowedHeaders", "允许的请求头", "留空表示不额外限制。", emptyList(), visibleWhenAllEnabled = listOf("cors.enabled")),
                stringListField("cors.exposedHeaders", "暴露的响应头", "留空表示不额外暴露。", emptyList(), visibleWhenAllEnabled = listOf("cors.enabled")),
                booleanField("cors.credentials", "允许携带凭据", "开启后会允许 Cookie 和授权头。", false, visibleWhenAllEnabled = listOf("cors.enabled")),
                booleanField("requestProxy.enabled", "启用请求代理", "为 Tavern 的出站 HTTP/HTTPS 请求指定代理。", false),
                textField("requestProxy.url", "代理地址", "如 socks5://user:pass@example.com:1080。", "", visibleWhenAllEnabled = listOf("requestProxy.enabled")),
                stringListField("requestProxy.bypass", "代理绕过列表", "每行一个域名或主机。", listOf("localhost", "127.0.0.1"), visibleWhenAllEnabled = listOf("requestProxy.enabled")),
                booleanField("forwardedHeaders.xRealIp", "信任 X-Real-IP", "仅当前置代理可信时开启。", true),
                booleanField("forwardedHeaders.xForwardedFor", "信任 X-Forwarded-For", "仅当前置代理可信时开启。", true),
                booleanField("forwardedHeaders.cfConnectingIp", "信任 CF-Connecting-IP", "仅当使用 Cloudflare 时开启。", false)
            )
        ),
        TavernConfigSectionSpec(
            title = "单点登录与主机限制",
            summary = "管理 SSO、Host 白名单和私网访问限制。",
            fields = listOf(
                booleanField("sso.autheliaAuth", "启用 Authelia 自动登录", "仅在已有反向代理中间件时启用。", false),
                booleanField("sso.authentikAuth", "启用 Authentik 自动登录", "仅在已有 Authentik 时启用。", false),
                stringListField("sso.trustedProxies", "SSO 信任代理列表", "每行一个 IP、CIDR 或通配模式。", listOf("::1", "127.0.0.1"), visibleWhenAnyEnabled = listOf("sso.autheliaAuth", "sso.authentikAuth")),
                booleanField("hostWhitelist.enabled", "启用 Host 白名单", "对 Host 头做额外校验。", false),
                booleanField("hostWhitelist.scan", "扫描可疑 Host 头", "建议保持开启。", true, visibleWhenAllEnabled = listOf("hostWhitelist.enabled")),
                stringListField("hostWhitelist.hosts", "允许的 Host 列表", "每行一个域名或子域模式。", emptyList(), visibleWhenAllEnabled = listOf("hostWhitelist.enabled")),
                booleanField("privateAddressWhitelist.enabled", "启用私网地址白名单", "阻止服务端请求访问私网目标。", false),
                booleanField("privateAddressWhitelist.allowUnresolvedHosts", "允许未解析主机", "开启后无法解析的主机会被放行。", false, visibleWhenAllEnabled = listOf("privateAddressWhitelist.enabled")),
                booleanField("privateAddressWhitelist.log.blockedRequests", "记录被拦截请求", "建议保持开启。", true, visibleWhenAllEnabled = listOf("privateAddressWhitelist.enabled")),
                booleanField("privateAddressWhitelist.log.allowedRequests", "记录放行请求", "调试时再开启。", false, visibleWhenAllEnabled = listOf("privateAddressWhitelist.enabled")),
                stringListField("privateAddressWhitelist.allowedRanges", "允许的私网地址段", "每行一个 CIDR 或通配模式。", listOf("127.0.0.0/8", "::1/128"), visibleWhenAllEnabled = listOf("privateAddressWhitelist.enabled"))
            )
        ),
        TavernConfigSectionSpec(
            title = "日志、限流与备份",
            summary = "管理访问日志、登录限流和内建备份策略。",
            fields = listOf(
                booleanField("logging.enableAccessLog", "启用访问日志", "同时写入文件和控制台。", true),
                integerField("logging.minLogLevel", "最小日志级别", "0=DEBUG, 1=INFO, 2=WARN, 3=ERROR。", 0),
                booleanField("rateLimiting.preferRealIpHeader", "优先使用真实 IP 头", "仅当前置代理可信时开启。", false),
                integerField("rateLimiting.basicAuthMaxAttempts", "基础认证最大失败次数", "0 表示禁用该限流。", 5),
                integerField("rateLimiting.accountsLoginMaxAttempts", "账户登录最大失败次数", "0 表示禁用该限流。", 5),
                integerField("rateLimiting.accountsRecoverMaxAttempts", "账户恢复最大失败次数", "0 表示禁用该限流。", 5),
                booleanField("backups.allowFullDataBackup", "允许完整数据备份", "建议开启，方便导出整包。", true),
                integerField("backups.common.numberOfBackups", "单文件保留备份数", "每个聊天或配置文件保留的历史版本数。", 50),
                booleanField("backups.chat.enabled", "启用自动聊天备份", "建议保持开启。", true),
                booleanField("backups.chat.checkIntegrity", "备份前校验聊天完整性", "建议保持开启。", true, visibleWhenAllEnabled = listOf("backups.chat.enabled")),
                integerField("backups.chat.maxTotalBackups", "聊天备份总上限", "-1 表示不设上限。", -1, visibleWhenAllEnabled = listOf("backups.chat.enabled")),
                integerField("backups.chat.throttleInterval", "聊天备份节流毫秒", "避免过于频繁地写入备份。", 10000, visibleWhenAllEnabled = listOf("backups.chat.enabled")),
                booleanField("cacheBuster.enabled", "启用缓存破坏器", "首次加载或上传图片后清浏览器缓存。", false),
                textField("cacheBuster.userAgentPattern", "缓存破坏器 User-Agent 过滤", "留空表示对所有客户端生效。", "", visibleWhenAllEnabled = listOf("cacheBuster.enabled")),
                booleanField("allowKeysExposure", "允许通过 API 暴露密钥", "通常不建议开启。", false),
                booleanField("skipContentCheck", "跳过默认内容检查", "首次启动异常时可临时开启排障。", false),
                stringListField("whitelistImportDomains", "允许导入卡片的域名", "每行一个域名。", listOf("localhost", "cdn.discordapp.com", "files.catbox.moe", "raw.githubusercontent.com"))
            )
        ),
        TavernConfigSectionSpec(
            title = "性能与扩展",
            summary = "控制缩略图、缓存、请求压缩和插件扩展行为。",
            fields = listOf(
                booleanField("thumbnails.enabled", "启用缩略图", "关闭可以减少磁盘占用，但会影响预览体验。", true),
                textField("thumbnails.format", "缩略图格式", "常用值为 jpg 或 png。", "jpg", visibleWhenAllEnabled = listOf("thumbnails.enabled")),
                integerField("thumbnails.quality", "缩略图质量", "JPG 时建议 0-100。", 95, visibleWhenAllEnabled = listOf("thumbnails.enabled")),
                booleanField("performance.lazyLoadCharacters", "延迟加载角色卡", "角色库很大时再开启。", false),
                textField("performance.memoryCacheCapacity", "内存缓存上限", "如 100mb，填 0 表示禁用。", "100mb"),
                booleanField("performance.useDiskCache", "启用磁盘缓存", "建议保持开启。", true),
                booleanField("performance.requestCompression.enabled", "启用请求压缩", "大请求体场景下有帮助。", false),
                textField("performance.requestCompression.minPayloadSize", "请求压缩最小体积", "如 256kb。", "256kb", visibleWhenAllEnabled = listOf("performance.requestCompression.enabled")),
                textField("performance.requestCompression.maxPayloadSize", "请求压缩最大体积", "如 8mb，填 0 表示不限制。", "8mb", visibleWhenAllEnabled = listOf("performance.requestCompression.enabled")),
                integerField("performance.requestCompression.timeout", "请求压缩超时毫秒", "压缩任务最多等待多久。", 4000, visibleWhenAllEnabled = listOf("performance.requestCompression.enabled")),
                booleanField("extensions.enabled", "启用 UI 扩展", "控制第三方界面扩展。", true),
                booleanField("extensions.autoUpdate", "自动更新 UI 扩展", "在版本升级时自动尝试更新。", true, visibleWhenAllEnabled = listOf("extensions.enabled")),
                booleanField("extensions.models.autoDownload", "自动下载扩展模型", "自动从 HuggingFace 下载扩展依赖模型。", true, visibleWhenAllEnabled = listOf("extensions.enabled")),
                textField("extensions.models.classification", "情绪分类模型", "HuggingFace 模型 ID。", "Cohee/distilbert-base-uncased-go-emotions-onnx", visibleWhenAllEnabled = listOf("extensions.enabled", "extensions.models.autoDownload")),
                textField("extensions.models.captioning", "图像描述模型", "HuggingFace 模型 ID。", "Xenova/vit-gpt2-image-captioning", visibleWhenAllEnabled = listOf("extensions.enabled", "extensions.models.autoDownload")),
                textField("extensions.models.embedding", "向量嵌入模型", "HuggingFace 模型 ID。", "Cohee/jina-embeddings-v2-base-en", visibleWhenAllEnabled = listOf("extensions.enabled", "extensions.models.autoDownload")),
                textField("extensions.models.speechToText", "语音转文本模型", "HuggingFace 模型 ID。", "Xenova/whisper-small", visibleWhenAllEnabled = listOf("extensions.enabled", "extensions.models.autoDownload")),
                textField("extensions.models.textToSpeech", "文本转语音模型", "HuggingFace 模型 ID。", "Xenova/speecht5_tts", visibleWhenAllEnabled = listOf("extensions.enabled", "extensions.models.autoDownload")),
                textField("git.backend", "Git 后端", "可填 auto、system 或 builtin。Android 宿主默认建议 builtin，避免依赖系统 git。", "builtin"),
                booleanField("enableDownloadableTokenizers", "允许下载额外 tokenizer", "关闭后会回退到本地可用 tokenizer。", true),
                booleanField("enableServerPlugins", "启用服务端插件", "需要你确认插件来源可信。", false),
                booleanField("enableServerPluginsAutoUpdate", "自动更新服务端插件", "如果重视可重复性，建议关闭。", true, visibleWhenAllEnabled = listOf("enableServerPlugins"))
            )
        ),
        TavernConfigSectionSpec(
            title = "模型服务偏好",
            summary = "覆盖 OpenAI、Ollama、Claude、Gemini 等接口的常用宿主级选项。",
            fields = listOf(
                multilineField("promptPlaceholder", "默认占位提示词", "严格 prompt 后处理模式下的默认提示。", "[Start a new chat]"),
                booleanField("openai.randomizeUserId", "OpenAI 请求随机用户 ID", "对 OpenAI completion API 附带随机 user 字段。", false),
                multilineField("openai.captionSystemPrompt", "OpenAI 图像描述系统提示", "图像描述时追加到 system message。", ""),
                textField("deepl.formality", "DeepL 正式度", "可填 default、more、less、prefer_more、prefer_less。", "default"),
                booleanField("mistral.enablePrefix", "启用 Mistral 前缀补全", "会把上一条 assistant 回复作为前缀。", false),
                integerField("ollama.keepAlive", "Ollama 保活秒数", "-1 常驻，0 请求后立刻卸载。", -1),
                integerField("ollama.batchSize", "Ollama batch size", "-1 跟随模型默认值，否则需为 2 的幂。", -1),
                booleanField("claude.enableSystemPromptCache", "Claude 缓存 system prompt", "system prompt 稳定时才建议开启。", false),
                integerField("claude.cachingAtDepth", "Claude 缓存消息深度", "-1 表示关闭。", -1),
                booleanField("claude.extendedTTL", "Claude 使用 1h TTL", "会提高成本。", false),
                booleanField("claude.enableAdaptiveThinking", "Claude 自适应思考", "仅支持对应模型时再开启。", false),
                textField("gemini.apiVersion", "Gemini API 版本", "可填 v1beta 或 v1alpha。", "v1beta"),
                booleanField("gemini.thoughtSignatures", "Gemini 思考签名", "仅较新模型支持。", true),
                booleanField("gemini.enableSystemPromptCache", "Gemini 缓存 system prompt", "仅在 OpenRouter 等支持的环境下有意义。", false),
                textField("gemini.image.personGeneration", "Gemini 图像人物生成策略", "留空则使用接口默认值。", "allow_adult")
            )
        )
    )

    val allFields: List<TavernConfigFieldSpec> = sections.flatMap { it.fields }
    val fieldsByPath: Map<String, TavernConfigFieldSpec> = allFields.associateBy { it.path }
}
