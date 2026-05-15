package com.jm.sillydroid.feature.main.ui.extensions

import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.domain.extensions.ExtensionsRepository
import com.jm.sillydroid.feature.settings.ui.extensions.BootstrapSettingsExtensionsCoordinator
import com.jm.sillydroid.feature.settings.ui.extensions.ExtensionInstallProgressHost

/**
 * 在 MainActivity 首次进入「启动成功」状态时，独立小窗触发「安装默认扩展」的完整流程：
 *  1. GitHub 可达性预检
 *  2. 按仓库批量预检（解析 manifest、判断目标目录是否已存在）
 *  3. 弹出选择对话框（默认全选 + 跳过/覆盖按钮 + 取消）
 *  4. 实时百分比进度小窗
 *  5. 结果汇总弹窗
 *
 * 这里直接复用 [BootstrapSettingsExtensionsCoordinator] —— 设置页里那一套已经经过验证的实现，
 * 只是把它需要的几个 view（list / empty / 三个 image button）替换成一组离屏占位 view，
 * 把进度展示替换成 [DialogExtensionInstallProgressHost]，从而让用户全程留在主界面，不必跳到设置页。
 */
class DefaultExtensionsInstallerLauncher(
    private val activity: AppCompatActivity,
    private val dispatchers: DispatcherProvider,
    private val extensionsRepository: ExtensionsRepository,
    private val onTavernUiReloadRequired: () -> Unit
) {

    fun launch() {
        val placeholderListContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val placeholderEmptyView = TextView(activity)
        val placeholderInstallButton = ImageButton(activity)
        val placeholderBatchDeleteButton = ImageButton(activity)
        val placeholderReloadButton = ImageButton(activity)

        val progressHost = DialogExtensionInstallProgressHost(activity)

        val coordinator = BootstrapSettingsExtensionsCoordinator(
            activity = activity,
            dispatchers = dispatchers,
            listContainer = placeholderListContainer,
            emptyView = placeholderEmptyView,
            installButton = placeholderInstallButton,
            batchDeleteButton = placeholderBatchDeleteButton,
            reloadButton = placeholderReloadButton,
            progressHost = progressHost,
            extensionsRepository = extensionsRepository,
            setBusy = { /* 离屏占位按钮无需根据 busy 切换可用状态 */ },
            showError = { message -> showErrorDialog(message) },
            showBanner = { message -> showShortToast(message) },
            showMessage = { message -> showLongToast(message) },
            onTavernUiReloadRequired = onTavernUiReloadRequired
        )
        coordinator.initialize()
        coordinator.promptDefaultRepositoriesSelection()
    }

    private fun showShortToast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun showLongToast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun showErrorDialog(message: String) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

/**
 * 用一个不可取消的 MaterialAlertDialog 承载安装过程中的进度条与文案。
 * `BootstrapSettingsExtensionsCoordinator` 在 IO 协程跑预检 / 安装时，会通过 progressHost.show
 * 不停回报阶段说明 + 百分比；这里把它映射到一个独立小窗，让用户清楚知道当前在干什么、卡在哪。
 */
private class DialogExtensionInstallProgressHost(
    private val activity: AppCompatActivity
) : ExtensionInstallProgressHost {

    private var dialog: AlertDialog? = null
    private var progressBar: LinearProgressIndicator? = null
    private var label: TextView? = null

    override fun show(message: String, percent: Int?, indeterminate: Boolean) {
        if (dialog == null) {
            buildAndShowDialog()
        }
        label?.text = message
        progressBar?.let { bar ->
            bar.isIndeterminate = indeterminate
            if (!indeterminate) {
                bar.setProgressCompat(percent ?: 0, true)
            }
        }
    }

    override fun hide() {
        dialog?.dismiss()
        dialog = null
        progressBar = null
        label = null
    }

    private fun buildAndShowDialog() {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val labelView = TextView(activity).apply {
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }
        val progressView = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(labelView)
        container.addView(progressView)

        val titleView = TextView(activity).apply {
            text = "正在安装默认扩展"
            textSize = 16f
            gravity = Gravity.START
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }

        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(container)
        }

        label = labelView
        progressBar = progressView
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(wrapper)
            .setCancelable(false)
            .create()
            .also {
                it.show()
                // 即便在某些主题下 wrapper 自带的标题已经够用，这里仍保留可见性管理，
                // 避免进度条意外消失带来的“假死”错觉。
                progressView.isVisible = true
                labelView.isVisible = true
            }
    }
}
