package com.jm.sillydroid.domain.bootstrap

/**
 * 为设置页终端提供独立于 bootstrap 会话状态的运行时准备与 shell 启动参数。
 * 这里不能复用“启动酒馆服务”的生命周期语义，否则只想开终端也会误触发服务启动状态机。
 */
interface ConsoleRuntimeRepository {
    fun prepareConsoleAssets(
        onProgress: (message: String, details: String, progressPercent: Int) -> Unit = { _, _, _ -> }
    )

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
