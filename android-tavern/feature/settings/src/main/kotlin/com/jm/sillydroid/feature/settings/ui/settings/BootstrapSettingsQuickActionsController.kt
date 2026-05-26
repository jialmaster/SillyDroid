package com.jm.sillydroid.feature.settings.ui.settings

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.TextViewCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.validation.BootstrapSettingsFormValidator
import kotlinx.coroutines.launch
import java.net.Inet4Address
import com.google.android.material.R as MaterialR

class BootstrapSettingsQuickActionsController(
    private val activity: AppCompatActivity,
    private val settingsCoordinator: BootstrapSettingsSettingsCoordinator,
    private val showMessage: (String) -> Unit
) {
    private data class QuickActionsContent(
        val rootView: LinearLayout,
        val lanAccessCard: MaterialCardView,
        val lanAccessSwitch: MaterialSwitch
    )

    fun showQuickActionsMenu() {
        val typedValues = settingsCoordinator.currentTypedValues() ?: return
        val listenEnabled = typedValues["listen"] as? Boolean == true
        val content = createQuickActionsContent(listenEnabled)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_quick_actions_title)
            .setView(content.rootView)
            .setNegativeButton(R.string.bootstrap_settings_import_confirm_cancel, null)
            .create()

        dialog.setOnShowListener {
            bindLanAccessAction(
                dialog = dialog,
                listenEnabled = listenEnabled,
                content = content
            )
        }
        dialog.show()
    }

    private fun bindLanAccessAction(
        dialog: androidx.appcompat.app.AlertDialog,
        listenEnabled: Boolean,
        content: QuickActionsContent
    ) {
        var actionHandled = false

        fun submit(targetEnabled: Boolean) {
            if (actionHandled) {
                return
            }
            actionHandled = true
            dialog.dismiss()
            setLanAccessEnabled(targetEnabled)
        }

        // 快捷功能弹窗这里要求“整行可点 + 右侧开关可点”，避免只剩一句文字时用户看不出这是可操作项。
        content.lanAccessCard.setOnClickListener {
            content.lanAccessSwitch.toggle()
        }
        content.lanAccessSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == listenEnabled) {
                return@setOnCheckedChangeListener
            }
            submit(isChecked)
        }
    }

    private fun createQuickActionsContent(listenEnabled: Boolean): QuickActionsContent {
        val horizontalPadding = dimen(R.dimen.sillydroid_control_padding_horizontal)
        val verticalPadding = dimen(R.dimen.sillydroid_control_padding_vertical)
        lateinit var lanAccessCard: MaterialCardView
        lateinit var lanAccessSwitch: MaterialSwitch

        val rootView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
            addView(
                MaterialCardView(activity).apply {
                    lanAccessCard = this
                    radius = activity.resources.getDimension(R.dimen.sillydroid_nested_card_radius)
                    cardElevation = 0f
                    strokeWidth = dp(1)
                    strokeColor = resolveThemeColor(MaterialR.attr.colorOutlineVariant)
                    setCardBackgroundColor(resolveThemeColor(MaterialR.attr.colorSurfaceContainerLow))
                    isClickable = true
                    isFocusable = true
                    foreground = activity.getDrawable(resolveThemeResource(android.R.attr.selectableItemBackground))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(
                                dimen(R.dimen.sillydroid_panel_padding),
                                dimen(R.dimen.sillydroid_panel_padding),
                                dimen(R.dimen.sillydroid_panel_padding),
                                dimen(R.dimen.sillydroid_panel_padding)
                            )
                            addView(
                                LinearLayout(activity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    addView(
                                        TextView(activity).apply {
                                            text = activity.getString(R.string.bootstrap_settings_quick_actions_lan_access_title)
                                            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsCardTitle)
                                            setTextColor(resolveThemeColor(MaterialR.attr.colorOnSurface))
                                        }
                                    )
                                    addView(
                                        TextView(activity).apply {
                                            text = activity.getString(
                                                if (listenEnabled) {
                                                    R.string.bootstrap_settings_quick_actions_lan_access_summary_enabled
                                                } else {
                                                    R.string.bootstrap_settings_quick_actions_lan_access_summary_disabled
                                                }
                                            )
                                            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
                                            setTextColor(resolveThemeColor(MaterialR.attr.colorOnSurfaceVariant))
                                            setPadding(0, dimen(R.dimen.sillydroid_space_xs_half), dimen(R.dimen.sillydroid_space_lg), 0)
                                        }
                                    )
                                }
                            )
                            addView(
                                MaterialSwitch(activity).apply {
                                    lanAccessSwitch = this
                                    isChecked = listenEnabled
                                    showText = false
                                    minWidth = 0
                                    minHeight = 0
                                    scaleX = 0.68f
                                    scaleY = 0.68f
                                }
                            )
                        }
                    )
                }
            )
        }
        return QuickActionsContent(
            rootView = rootView,
            lanAccessCard = lanAccessCard,
            lanAccessSwitch = lanAccessSwitch
        )
    }

    private fun setLanAccessEnabled(targetEnabled: Boolean) {
        activity.lifecycleScope.launch {
            val typedValues = settingsCoordinator.currentTypedValues() ?: return@launch
            if (!targetEnabled) {
                // 这里沿 Tavern 的 listen 语义收口为“关闭外部监听，保留宿主本地 127.0.0.1 访问”，
                // 不能顺手清白名单或白名单模式，避免误改用户原始配置。
                settingsCoordinator.applyProgrammaticValues(
                    mapOf("listen" to false)
                )
                showMessage(activity.getString(R.string.bootstrap_settings_quick_actions_lan_disabled))
                return@launch
            }

            // 开启快捷功能时才补齐 Wi-Fi 网段白名单和本机 loopback，确保局域网与宿主本地访问同时可用。
            val wifiIpv4 = resolveCurrentWifiIpv4()
            if (wifiIpv4 == null) {
                showMessage(activity.getString(R.string.bootstrap_settings_quick_actions_lan_wifi_ip_missing))
                return@launch
            }

            val whitelistEntries = (typedValues["whitelist"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            val normalizedWhitelist = mergeLanWhitelistEntries(whitelistEntries, wifiIpv4)
            if (normalizedWhitelist.isEmpty()) {
                showMessage(activity.getString(R.string.bootstrap_settings_quick_actions_lan_whitelist_missing))
                return@launch
            }

            val updatedValues = linkedMapOf<String, Any?>(
                "listen" to true,
                "whitelistMode" to true,
                "whitelist" to normalizedWhitelist
            )

            val previewValues = LinkedHashMap(typedValues).apply {
                putAll(updatedValues)
            }
            val validationIssue = BootstrapSettingsFormValidator.validate(
                values = previewValues,
                defaultServicePort = previewValues["port"] as? Int ?: 8000
            )
            if (validationIssue != null) {
                showMessage(validationIssue.message)
                return@launch
            }

            settingsCoordinator.applyProgrammaticValues(updatedValues)
            showMessage(activity.getString(R.string.bootstrap_settings_quick_actions_lan_enabled))
        }
    }

    private fun resolveCurrentWifiIpv4(): String? {
        val connectivityManager = activity.getSystemService(ConnectivityManager::class.java) ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        return linkProperties.linkAddresses
            .mapNotNull { linkAddress -> linkAddress.address as? Inet4Address }
            .firstOrNull { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
            ?.hostAddress
    }

    private fun mergeLanWhitelistEntries(
        existingEntries: List<String>,
        wifiIpv4: String
    ): List<String> {
        return mergeLanWhitelistEntriesForWifi(existingEntries, wifiIpv4)
    }

    companion object {
        internal fun mergeLanWhitelistEntriesForWifi(
            existingEntries: List<String>,
            wifiIpv4: String
        ): List<String> {
        val octets = wifiIpv4.split('.')
        if (octets.size != 4 || octets.any { part -> part.toIntOrNull() !in 0..255 }) {
            return emptyList()
        }

        val requiredEntries = linkedSetOf("127.0.0.1", "::1")
        val targetWildcard = "${octets[0]}.${octets[1]}.${octets[2]}.*"
        val targetCidr = "${octets[0]}.${octets[1]}.${octets[2]}.0/24"

        val normalizedExisting = existingEntries
            .map { entry -> entry.trim() }
            .filter { entry -> entry.isNotBlank() }
            .toMutableList()

        requiredEntries.forEach { loopback ->
            if (normalizedExisting.none { it.equals(loopback, ignoreCase = true) }) {
                normalizedExisting += loopback
            }
        }

        val alreadyCovered = normalizedExisting.any { entry ->
            whitelistEntryCoversWifi(entry, octets)
        }
        if (!alreadyCovered) {
            // 局域网访问快捷功能这里优先沿用户描述写入同网段通配白名单；
            // 同时在写入前做包含关系判断，避免已有更大的 192.168.*.* 之类规则时重复塞更小网段。
            normalizedExisting += targetWildcard
        }

        return normalizedExisting
            .distinctBy { entry -> entry.lowercase() }
            .sortedWith(compareBy<String> { entry ->
                when {
                    entry == "127.0.0.1" -> 0
                    entry == "::1" -> 1
                    entry == targetWildcard || entry == targetCidr -> 2
                    else -> 3
                }
            }.thenBy { it })
    }

    private fun whitelistEntryCoversWifi(entry: String, wifiOctets: List<String>): Boolean {
        val normalizedEntry = entry.trim()
        if (normalizedEntry.isBlank()) {
            return false
        }

        if (normalizedEntry.contains('/')) {
            return cidrCoversWifi(normalizedEntry, wifiOctets)
        }

        val parts = normalizedEntry.split('.')
        if (parts.size != 4) {
            return false
        }

        return parts.indices.all { index ->
            val part = parts[index]
            part == "*" || part == wifiOctets[index]
        }
    }

    private fun cidrCoversWifi(entry: String, wifiOctets: List<String>): Boolean {
        val segments = entry.split('/')
        if (segments.size != 2) {
            return false
        }
        val baseOctets = segments[0].split('.')
        val prefixLength = segments[1].toIntOrNull() ?: return false
        if (baseOctets.size != 4 || prefixLength !in 0..32) {
            return false
        }

        val baseValue = ipv4ToLong(baseOctets) ?: return false
        val wifiValue = ipv4ToLong(wifiOctets) ?: return false
        val mask = if (prefixLength == 0) 0L else (-1L shl (32 - prefixLength)) and 0xffffffffL
        return (baseValue and mask) == (wifiValue and mask)
    }

    private fun ipv4ToLong(octets: List<String>): Long? {
        if (octets.size != 4) {
            return null
        }

        var result = 0L
        octets.forEach { part ->
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) {
                return null
            }
            result = (result shl 8) or octet.toLong()
        }
        return result
    }
    }

    private fun resolveThemeColor(attrRes: Int): Int {
        return MaterialColors.getColor(activity, attrRes, 0)
    }

    private fun resolveThemeResource(attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        activity.theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.resourceId
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
