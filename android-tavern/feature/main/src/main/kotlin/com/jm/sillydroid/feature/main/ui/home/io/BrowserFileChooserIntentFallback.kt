package com.jm.sillydroid.feature.main.ui.home.io

import android.content.Intent

/**
 * 构建 WebView/GeckoView 文件选择器的兼容兜底 Intent。
 *
 * 部分精简 ROM 或模拟器缺少 DocumentsUI，首选的 ACTION_OPEN_DOCUMENT/系统 chooser
 * 可能无法启动；WebView 文件上传只需要一次性读取所选 Uri，因此可退回到
 * ACTION_GET_CONTENT，并继续复用原有 MIME、多选和选后扩展名校验逻辑。
 */
internal object BrowserFileChooserIntentFallback {
    fun buildGetContentFallback(
        sourceIntent: Intent,
        allowMultiple: Boolean
    ): Intent {
        val sourceTarget = sourceIntent.unwrapChooserTarget()
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = sourceTarget.type?.takeIf { mimeType -> mimeType.isNotBlank() } ?: "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            sourceTarget.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.let { mimeTypes ->
                if (mimeTypes.isNotEmpty()) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.unwrapChooserTarget(): Intent {
        if (action != Intent.ACTION_CHOOSER) {
            return this
        }
        return getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: this
    }
}
