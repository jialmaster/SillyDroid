package com.jm.sillydroid.ui.update

/**
 * 把更新检查失败时的异常消息压成单行短文本，给 UI 直接渲染。
 *
 * - 取异常消息中第一段非空白行，截断到 240 字符。
 * - 异常消息为空白时返回 `unknownFallback`，由调用方提供本地化字符串。
 *
 * 抽出为 internal 顶层函数，使其可在 JVM 单测中无 Android 依赖地验证。
 */
internal fun formatAppUpdateCheckError(exception: Throwable, unknownFallback: String): String {
    val rawMessage = exception.message.orEmpty().trim()
    if (rawMessage.isBlank()) {
        return unknownFallback
    }

    return rawMessage
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(240)
        ?: unknownFallback
}
