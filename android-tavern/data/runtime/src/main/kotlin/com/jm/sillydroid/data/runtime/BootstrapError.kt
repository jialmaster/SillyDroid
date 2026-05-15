package com.jm.sillydroid.data.runtime

/**
 * 给 [BootstrapException] 增加结构化分类，便于上层在不依赖 message 字符串的情况下：
 *   - 区分可重试 / 需用户干预的错误；
 *   - 输出更准确的 telemetry / 日志标签；
 *   - 后续若要做差异化提示文案，可直接 `when (exception.error)` 分支。
 *
 * 历史 [BootstrapException] 仅携带字符串消息；新代码应优先抛出携带具体子类的版本，
 * 旧调用点保持 `BootstrapException(message)` 写法仍可工作（被解释为 [Generic]）。
 */
sealed class BootstrapError(open val message: String) {
    /** 未明确分类的 bootstrap 失败。 */
    data class Generic(override val message: String) : BootstrapError(message)

    /** bootstrap 归档（assets / dependency pack）内容损坏或格式非法。 */
    data class ArchiveCorrupted(override val message: String) : BootstrapError(message)

    /** Tavern server 进程在等待窗口内未就绪。 */
    data class ServerNotReady(override val message: String) : BootstrapError(message)

    /** runtime 停止流程在超时窗口内未完成。 */
    data class RuntimeStopTimeout(override val message: String) : BootstrapError(message)

    /** dependency pack 解压后 hook 执行失败或超时。 */
    data class PostExtractHookFailed(override val message: String) : BootstrapError(message)
}
