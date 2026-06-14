package com.jm.sillydroid.data.runtime

import android.content.Context
import com.jm.sillydroid.domain.bootstrap.ConsoleRuntimeRepository
import com.jm.sillydroid.domain.bootstrap.ConsoleShellLaunchSpec
import java.io.File

/**
 * 设置页终端只负责给出 shell 启动规格，不能解包、刷新或校验 rootfs/server 资产。
 * 资产准备只能由 app bootstrap 链路完成，避免用户只是打开终端时触发环境管理副作用。
 */
class DefaultConsoleRuntimeRepository(context: Context) : ConsoleRuntimeRepository {
    private val appContext = context.applicationContext

    override fun createShellLaunchSpec(): ConsoleShellLaunchSpec {
        val paths = HostPaths.from(appContext)
        val guestShellPath = resolveConsoleGuestShellPath(paths, readInstalledRootfsGuestShellPath(paths))
        return buildConsoleShellLaunchSpec(paths, guestShellPath)
    }
}

/**
 * 设置页终端需要真实的交互式行编辑和历史能力；
 * 当前 rootfs manifest 的 /bin/sh 实际是 dash，方向键只会回显 ^[[A，无法像 Termux 一样浏览历史。
 * 因此这里仅对“终端页 shell”单独优先切到 bash；宿主服务和其他 runtime 入口仍继续沿用 manifest 里的默认 shell。
 */
internal fun resolveConsoleGuestShellPath(
    paths: HostPaths,
    installedGuestShellPath: String
): String {
    if (File(paths.rootfsDir, "fs/bin/bash").isFile && installedGuestShellPath == "/bin/sh") {
        return "/bin/bash"
    }

    return installedGuestShellPath
}

/**
 * 终端 shell 启动规格必须复用宿主统一的 Termux host 环境变量，
 * 否则设置页终端看到的目录、prefix 和 DNS 会和正式运行时分叉。
 */
internal fun buildConsoleShellLaunchSpec(
    paths: HostPaths,
    guestShellPath: String
): ConsoleShellLaunchSpec {
    val scriptFile = File(paths.scriptsDir, "start-console-shell.sh")
    val environment = buildHostRuntimeEnvironment(paths).toMutableMap().apply {
        put("APP_DATA_ROOT", paths.serverDataDir.absolutePath)
        put("SILLYDROID_GUEST_SHELL_PATH", guestShellPath)
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
    }

    // transcript 只保存在单次 app 进程内存里，避免和现有日志体系混写；
    // 但滚动缓冲必须足够长，才能满足设置页终端切进切出后继续查看历史输出。
    return ConsoleShellLaunchSpec(
        shellPath = "/system/bin/sh",
        workingDirectory = paths.bootstrapRoot.absolutePath,
        // Termux TerminalSession 底层直接把 arguments 作为 execvp(argv) 传入，不会自动补 argv[0]。
        // 这里必须显式带上 shell 自身和脚本路径，否则 /system/bin/sh 会退回成“等输入的交互 shell”，
        // 首屏既不会执行 start-console-shell.sh，也不会进入真实 Tavern 工作目录。
        arguments = listOf("/system/bin/sh", scriptFile.absolutePath),
        environment = environment,
        transcriptRows = 10_000
    )
}
