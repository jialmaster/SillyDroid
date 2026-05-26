package com.jm.sillydroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 下载完成广播可能发生在应用 UI 不可见甚至未运行时；
 * 这里把宿主自管通知键做成轻量持久化，方便广播接收器按 key 更新/清理通知。
 */
class HostNotificationStateStore(context: Context) {
    companion object {
        private const val preferencesName = "host-notification-state"
        private const val activeKeysKey = "active-keys"
        private const val notificationTargetsKey = "notification-targets"
    }

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun markActive(notificationKey: String) {
        markActive(notificationKey, tag = notificationKey, id = resolveLegacyNotificationId(notificationKey))
    }

    fun markActive(notificationKey: String, tag: String?, id: Int) {
        if (notificationKey.isBlank()) {
            return
        }
        val keys = readKeys().toMutableSet()
        keys += notificationKey
        writeKeys(keys)
        writeTarget(notificationKey, HostNotificationTarget(tag = tag, id = id))
    }

    fun markRemoved(notificationKey: String) {
        val keys = readKeys().toMutableSet()
        keys.remove(notificationKey)
        writeKeys(keys)
        removeTarget(notificationKey)
    }

    fun findKeysByPrefix(prefix: String): List<String> {
        return readKeys().filter { key -> key.startsWith(prefix) }
    }

    fun findTarget(notificationKey: String): HostNotificationTarget? {
        val rawValue = preferences.getString(notificationTargetsKey, "{}").orEmpty()
        return runCatching {
            val targets = JSONObject(rawValue)
            val payload = targets.optJSONObject(notificationKey) ?: return null
            HostNotificationTarget(
                tag = payload.optString("tag").takeIf { value -> value.isNotBlank() },
                id = payload.getInt("id")
            )
        }.getOrNull()
    }

    private fun readKeys(): List<String> {
        val rawValue = preferences.getString(activeKeysKey, "[]").orEmpty()
        return runCatching {
            val json = JSONArray(rawValue)
            buildList {
                for (index in 0 until json.length()) {
                    val key = json.optString(index).trim()
                    if (key.isNotBlank()) {
                        add(key)
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeKeys(keys: Set<String>) {
        val json = JSONArray()
        keys.sorted().forEach(json::put)
        preferences.edit().putString(activeKeysKey, json.toString()).apply()
    }

    private fun writeTarget(notificationKey: String, target: HostNotificationTarget) {
        val targets = readTargets()
        targets.put(
            notificationKey,
            JSONObject().apply {
                put("id", target.id)
                if (target.tag != null) {
                    put("tag", target.tag)
                }
            }
        )
        preferences.edit().putString(notificationTargetsKey, targets.toString()).apply()
    }

    private fun removeTarget(notificationKey: String) {
        val targets = readTargets()
        targets.remove(notificationKey)
        preferences.edit().putString(notificationTargetsKey, targets.toString()).apply()
    }

    private fun readTargets(): JSONObject {
        val rawValue = preferences.getString(notificationTargetsKey, "{}").orEmpty()
        return runCatching { JSONObject(rawValue) }.getOrElse { JSONObject() }
    }

    private fun resolveLegacyNotificationId(notificationKey: String): Int {
        val seed = notificationKey.hashCode()
        return if (seed == Int.MIN_VALUE) 0 else kotlin.math.abs(seed)
    }
}

data class HostNotificationTarget(
    val tag: String?,
    val id: Int
)
