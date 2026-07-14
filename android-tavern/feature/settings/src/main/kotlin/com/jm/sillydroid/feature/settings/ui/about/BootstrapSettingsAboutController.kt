package com.jm.sillydroid.feature.settings.ui.about

import android.content.Intent
import android.net.Uri
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.jm.sillydroid.domain.settings.HostPreferencesRepository

/**
 * 管理设置页关于区的外部入口与崩溃日志开关。
 *
 * 允许：构造外部浏览器 Intent 并委托 Activity 发起；不允许绕过宿主统一的外部流程抑制策略。
 */
class BootstrapSettingsAboutController(
    private val activity: AppCompatActivity,
    private val githubButton: ImageButton,
    private val crashUploadSwitch: MaterialSwitch,
    private val hostPreferencesRepository: HostPreferencesRepository,
    private val githubRepository: String,
    private val externalBrowserFailureMessage: () -> String,
    private val launchExternalIntent: (Intent) -> Boolean
) {
    /** 初始化关于区按钮与开关，保持宿主设置真值单向写入。 */
    fun initialize() {
        githubButton.setOnClickListener {
            openProjectHomePage()
        }
        crashUploadSwitch.isChecked = hostPreferencesRepository.crashLogUploadEnabled
        crashUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            hostPreferencesRepository.crashLogUploadEnabled = isChecked
        }
    }

    /** 打开项目主页；实际 startActivity 由设置页统一包裹悬浮浏览器抑制令牌。 */
    private fun openProjectHomePage() {
        val repository = githubRepository.trim()
        val projectUri = Uri.parse("https://github.com/$repository")
        val launched = launchExternalIntent(
            Intent(Intent.ACTION_VIEW, projectUri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        )
        if (!launched) {
            Toast.makeText(activity, externalBrowserFailureMessage(), Toast.LENGTH_SHORT).show()
        }
    }
}
