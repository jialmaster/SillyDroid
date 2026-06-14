package com.jm.sillydroid.domain.bootstrap

/**
 * 为设置页终端提供独立于 bootstrap 会话状态的 shell 启动参数。
 * 终端不能解包、刷新或校验 rootfs/server 资产；这些环境管理职责只能留在 app bootstrap 链路。
 */
interface ConsoleRuntimeRepository {
    fun createShellLaunchSpec(): ConsoleShellLaunchSpec
}

/**
 * 终端库只关心“起哪个进程、带哪些参数和环境变量”，因此把 console 启动规格收敛成纯数据，
 * 避免设置页直接拼接 Termux host runtime 细节并在多个位置散落。
 */
data class ConsoleShellLaunchSpec(
    val shellPath: String,
    val workingDirectory: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val transcriptRows: Int
)
