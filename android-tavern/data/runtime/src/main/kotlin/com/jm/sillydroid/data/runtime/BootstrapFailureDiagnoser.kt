package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.core.model.bootstrap.BootstrapFailureDiagnosis
import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import java.util.Locale

/**
 * 启动失败诊断器：只根据已发生的阶段、结构化错误和最近日志做提示，
 * 避免 UI 层散落字符串匹配，也避免在没有日志依据时臆测具体故障。
 */
internal object BootstrapFailureDiagnoser {

    fun diagnose(
        stepId: BootstrapStepId?,
        stageTitle: String,
        details: String,
        errorKind: String?,
        logFileName: String?,
        logExcerpt: String
    ): BootstrapFailureDiagnosis {
        val normalizedEvidence = listOf(details, logExcerpt, errorKind.orEmpty())
            .joinToString(separator = "\n")
            .lowercase(Locale.ROOT)
        val reason = resolveReason(stepId, normalizedEvidence, errorKind)
        return BootstrapFailureDiagnosis(
            stageTitle = stageTitle,
            logFileName = logFileName,
            logExcerpt = logExcerpt,
            suspectedReason = reason.title,
            solutions = reason.solutions
        )
    }

    private fun resolveReason(
        stepId: BootstrapStepId?,
        evidence: String,
        errorKind: String?
    ): DiagnosisReason {
        if (containsAny(evidence, "eaddrinuse", "already in use", "端口", "占用", "监听状态")) {
            return DiagnosisReason(
                title = "Tavern 配置端口已被其他进程占用，Node 无法绑定到当前端口。",
                solutions = listOf(
                    "打开宿主设置，把 Tavern 端口改成未被占用的端口后继续启动。",
                    "如果确认是旧 Tavern 进程残留，先停止占用端口的进程，再点重试。"
                )
            )
        }

        if (containsAny(evidence, "blocked connection from", "forbidden-by-whitelist", "whitelist mode")) {
            return DiagnosisReason(
                title = "SillyTavern IP 白名单拦截了当前访问来源。",
                solutions = listOf(
                    "在 config.yaml 的 whitelist 中保留 127.0.0.1 和 ::1；如果从局域网访问，也加入对应设备 IP。",
                    "只在可信本地环境下，可以关闭 whitelistMode 后重启 Tavern。"
                )
            )
        }

        if (containsAny(evidence, "host-not-allowed", "access from this host is not allowed", "request from untrusted host", "hostwhitelist.hosts")) {
            return DiagnosisReason(
                title = "SillyTavern Host 白名单拦截了当前 Host 头。",
                solutions = listOf(
                    "如果使用域名、反代或隧道访问，把对应域名加入 config.yaml 的 hostWhitelist.hosts。",
                    "如果只是本机 127.0.0.1 访问，检查 WebView 实际打开的地址是否被改成了其他 Host。"
                )
            )
        }

        // SillyTavern 的私有地址过滤属于运行后请求限制，优先于通用 HTTP 未就绪提示展示。
        if (containsAny(evidence, "[private request filter]", "blocked request to private ip address", "privateaddresswhitelist")) {
            return DiagnosisReason(
                title = "SillyTavern 私有地址请求过滤器拦截了内网地址访问。",
                solutions = listOf(
                    "如果这是可信内网服务，把对应 IP 或网段加入 config.yaml 的 privateAddressWhitelist.allowedRanges。",
                    "如果不是必须访问内网地址，保持拦截并检查触发请求的扩展或导入链接。"
                )
            )
        }

        // 这些字符串来自 SillyTavern server-startup.js 的 HTTPS 启动前置校验。
        if (containsAny(evidence, "ssl certificate path is required", "ssl key path is required", "ssl certificate path does not exist", "ssl key path does not exist")) {
            return DiagnosisReason(
                title = "SillyTavern HTTPS/SSL 配置不完整或证书路径不可用。",
                solutions = listOf(
                    "如果不需要 HTTPS，在 config.yaml 里关闭 SSL 后重启 Tavern。",
                    "如果需要 HTTPS，确认 certPath、keyPath 和 SSL 证书文件都存在且当前运行时可读取。"
                )
            )
        }

        // 监听协议不可用时 Node 进程会直接退出，必须提示用户回到 config.yaml 修正 enableIPv4/enableIPv6。
        if (containsAny(evidence, "both ipv6 and ipv4 are disabled or not detected", "failed to start server on ipv6 and ipv4 disabled", "failed to start server on ipv4 and ipv6 disabled")) {
            return DiagnosisReason(
                title = "SillyTavern 没有可用的 IPv4/IPv6 监听协议，服务无法开始监听。",
                solutions = listOf(
                    "在 config.yaml 中启用 enableIPv4，安卓本机访问建议至少保留 IPv4 可用。",
                    "如果修改过 listenAddress 或 enableIPv6，先恢复为本机默认监听配置后再重试。"
                )
            )
        }

        // 布局校验和 launcher 的缺文件错误都说明本地 runtime/server 资产不完整。
        if (
            containsAny(
                evidence,
                "bootstrap 资产缺少关键文件",
                "启动脚本不存在",
                "缺少 Termux Node 入口",
                "缺少 Termux Git 入口",
                "缺少 Termux Git HTTPS helper 入口",
                "缺少 Termux shell 入口",
                "缺少 host runtime 依赖目录"
            )
        ) {
            return DiagnosisReason(
                title = "当前启动阶段缺少必要的 runtime/server 关键文件。",
                solutions = listOf(
                    "重新同步 android-tavern 的离线运行时与 server payload 资产后再启动。",
                    "如果设备上曾手动删除 android-tavern 目录，重新安装应用或清空后让宿主完整解压。"
                )
            )
        }

        // no-proot 链路直接执行 APK nativeLibraryDir 中的 Termux ELF；
        // linker/权限类错误应指向 native runtime 资产，而不是 Tavern 配置。
        if (
            stepId == BootstrapStepId.ENSURE_ROOTFS_RUNTIME &&
            containsAny(
                evidence,
                "cannot link executable",
                "library",
                "permission denied",
                "eacces",
                "operation not permitted",
                "exec format error"
            )
        ) {
            return DiagnosisReason(
                title = "离线 Termux host runtime 在当前设备上无法执行或缺少 native 依赖。",
                solutions = listOf(
                    "重新同步 Android runtime image，确认 APK nativeLibraryDir 内包含 node、git、git-remote-http 和 shell 入口。",
                    "导出 rootfs-runtime 日志，优先检查 CANNOT LINK EXECUTABLE、Permission denied 或缺失 so 名称。"
                )
            )
        }

        if (containsAny(evidence, "cannot find module", "err_module_not_found", "module_not_found")) {
            return DiagnosisReason(
                title = "Tavern 服务依赖或 server payload 不完整，Node 启动时找不到模块。",
                solutions = listOf(
                    "重新同步或重新安装当前 APK 内置 Tavern 资产。",
                    "导出日志后检查 server payload 是否被手动删除或被清理工具移除。"
                )
            )
        }

        if (containsAny(evidence, "yaml", "config.yaml", "bad indentation", "can not read", "cannot read")) {
            return DiagnosisReason(
                title = "Tavern 配置文件可能存在格式错误或不可读取项。",
                solutions = listOf(
                    "打开宿主设置检查端口、监听地址等配置，保存后继续启动。",
                    "如果手动编辑过 config.yaml，恢复缩进和字段名后再重试。"
                )
            )
        }

        if (containsAny(evidence, "enospc", "no space left", "空间不足")) {
            return DiagnosisReason(
                title = "设备存储空间不足，运行时或 Tavern 资产无法完整写入。",
                solutions = listOf(
                    "清理设备存储后重试启动。",
                    "如果反复失败，导出日志确认是哪一个资产解压阶段写入失败。"
                )
            )
        }

        if (containsAny(evidence, "eacces", "permission denied", "权限")) {
            return DiagnosisReason(
                title = "运行时文件或脚本权限异常，导致启动脚本无法继续。",
                solutions = listOf(
                    "重试启动让宿主重新校验运行时权限。",
                    "如果仍失败，重新安装应用或重新同步 runtime 资产。"
                )
            )
        }

        return when {
            errorKind == BootstrapError.ServerNotReady::class.simpleName ||
                stepId == BootstrapStepId.WAIT_HTTP_READY -> DiagnosisReason(
                title = "Tavern 进程已拉起，但 HTTP 服务在等待窗口内没有返回可用响应。",
                solutions = listOf(
                    "先查看最近服务日志中的最后一段异常，再点重试。",
                    "如果总停在这里，优先检查端口、白名单、配置文件和最近安装的扩展。"
                )
            )

            errorKind == BootstrapError.ArchiveCorrupted::class.simpleName -> DiagnosisReason(
                title = "bootstrap 资产包损坏或不完整。",
                solutions = listOf(
                    "重新安装应用或重新同步对应资产包。",
                    "导出日志确认损坏的是 runtime、dependency pack 还是 server payload。"
                )
            )

            errorKind == BootstrapError.PostExtractHookFailed::class.simpleName -> DiagnosisReason(
                title = "资产解压后的初始化脚本执行失败。",
                solutions = listOf(
                    "查看最近 rootfs/runtime 日志中的脚本输出。",
                    "重新同步资产后重试，避免半解压状态继续启动。"
                )
            )

            else -> DiagnosisReason(
                title = "启动流程在当前阶段异常中断，最近日志是主要诊断依据。",
                solutions = listOf(
                    "按最近日志里的第一条明确错误处理后重试。",
                    "如果日志没有明显原因，导出完整日志包继续排查。"
                )
            )
        }
    }

    private fun containsAny(evidence: String, vararg needles: String): Boolean {
        return needles.any { needle -> evidence.contains(needle) }
    }

    private data class DiagnosisReason(
        val title: String,
        val solutions: List<String>
    )
}
