package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.core.model.bootstrap.BootstrapStepId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapFailureDiagnoserTest {

    @Test
    fun `port occupancy evidence produces port solution`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.START_SERVER_PROCESS,
            stageTitle = "启动 Tavern 进程",
            details = "Tavern 配置端口 8000 已处于监听状态。",
            errorKind = "ConfigIntervention",
            logFileName = "startup.log",
            logExcerpt = ""
        )

        assertEquals("启动 Tavern 进程", diagnosis.stageTitle)
        assertTrue(diagnosis.suspectedReason.contains("端口"))
        assertTrue(diagnosis.solutions.any { solution -> solution.contains("端口") })
    }

    @Test
    fun `sillytavern whitelist evidence produces whitelist solution`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.WAIT_HTTP_READY,
            stageTitle = "等待 HTTP 服务就绪",
            details = "本地 Tavern 服务在等待窗口内未就绪。",
            errorKind = BootstrapError.ServerNotReady::class.simpleName,
            logFileName = "sillydroid-server.log",
            logExcerpt = "Blocked connection from 192.168.1.23; To allow this connection, add its IP address to the whitelist."
        )

        assertTrue(diagnosis.suspectedReason.contains("白名单"))
        assertTrue(diagnosis.solutions.any { solution -> solution.contains("127.0.0.1") })
    }

    @Test
    fun `server not ready keeps nearest log excerpt in diagnosis`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.WAIT_HTTP_READY,
            stageTitle = "等待 HTTP 服务就绪",
            details = "本地 Tavern 服务在等待窗口内未就绪。",
            errorKind = BootstrapError.ServerNotReady::class.simpleName,
            logFileName = "sillydroid-server-20260526.log",
            logExcerpt = "Warning: failed to start server on IPv4"
        )

        assertEquals("sillydroid-server-20260526.log", diagnosis.logFileName)
        assertEquals("Warning: failed to start server on IPv4", diagnosis.logExcerpt)
        assertTrue(diagnosis.suspectedReason.contains("HTTP 服务"))
    }

    @Test
    fun `layout validation missing bootstrap files points to asset resync`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.VALIDATE_RUNTIME_LAYOUT,
            stageTitle = "校验运行时布局",
            details = "bootstrap 资产缺少关键文件：scripts/start-server.sh, server/bootstrap-manifest.json。",
            errorKind = BootstrapError.Generic::class.simpleName,
            logFileName = "startup.log",
            logExcerpt = ""
        )

        assertTrue(diagnosis.suspectedReason.contains("关键文件"))
        assertTrue(diagnosis.solutions.any { solution -> solution.contains("重新同步") })
    }

    @Test
    fun `rootfs proot seccomp crash points to runtime compatibility`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.ENSURE_ROOTFS_RUNTIME,
            stageTitle = "初始化离线 Linux 运行时",
            details = "Linux 离线运行时校验失败。",
            errorKind = null,
            logFileName = "rootfs-runtime.log",
            logExcerpt = "proot info: ptrace operation not permitted\nsignal 11"
        )

        assertTrue(diagnosis.suspectedReason.contains("proot"))
        assertTrue(diagnosis.solutions.any { solution -> solution.contains("PROOT_NO_SECCOMP") })
    }

    @Test
    fun `sillytavern ssl startup error points to ssl settings`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.START_SERVER_PROCESS,
            stageTitle = "启动 Tavern 进程",
            details = "Tavern 进程退出码：1",
            errorKind = null,
            logFileName = "sillydroid-server.log",
            logExcerpt = "Error: SSL certificate path is required when using HTTPS. Check your config"
        )

        assertTrue(diagnosis.suspectedReason.contains("HTTPS"))
        assertTrue(diagnosis.solutions.any { solution -> solution.contains("SSL") })
    }

    @Test
    fun `sillytavern ip stack disabled error points to listen protocol settings`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.START_SERVER_PROCESS,
            stageTitle = "启动 Tavern 进程",
            details = "Tavern 进程退出码：1",
            errorKind = null,
            logFileName = "sillydroid-server.log",
            logExcerpt = "Both IPv6 and IPv4 are disabled or not detected"
        )

        assertTrue(diagnosis.suspectedReason.contains("IPv4"))
        assertTrue(diagnosis.solutions.any { solution -> solution.contains("enableIPv4") })
    }

    @Test
    fun `private address whitelist block points to SillyTavern privateAddressWhitelist`() {
        val diagnosis = BootstrapFailureDiagnoser.diagnose(
            stepId = BootstrapStepId.WAIT_HTTP_READY,
            stageTitle = "等待 HTTP 服务就绪",
            details = "本地 Tavern 服务在等待窗口内未就绪。",
            errorKind = BootstrapError.ServerNotReady::class.simpleName,
            logFileName = "sillydroid-server.log",
            logExcerpt = "[Private Request Filter] Blocked request to private IP address: 192.168.1.2"
        )

        assertTrue(diagnosis.suspectedReason.contains("私有地址"))
        assertTrue(diagnosis.solutions.any { solution -> solution.contains("privateAddressWhitelist") })
    }
}
