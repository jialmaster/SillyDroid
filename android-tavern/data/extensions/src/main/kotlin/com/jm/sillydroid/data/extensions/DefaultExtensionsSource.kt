package com.jm.sillydroid.data.extensions

import android.content.Context
import java.io.File
import org.json.JSONObject

class DefaultExtensionsSource(context: Context) {
    private val appContext = context.applicationContext

    fun repositoryCount(): Int {
        val packagedConfigFile = File(
            appContext.filesDir,
            "android-tavern/bootstrap/default-extensions/sillydroid-build-config.json"
        )
        if (!packagedConfigFile.isFile) {
            return 0
        }

        return runCatching {
            val root = JSONObject(packagedConfigFile.readText())
            val repositories = root.optJSONArray("defaultExtensionRepositories")
                ?: root.optJSONArray("repositories")
                ?: return@runCatching 0
            (0 until repositories.length()).count { index ->
                val repository = repositories.optJSONObject(index) ?: return@count false
                repository.optString("displayName").trim().isNotBlank() &&
                    repository.optString("repositoryUrl").trim().isNotBlank()
            }
        }.getOrDefault(0)
    }
}
