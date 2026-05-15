package com.jm.sillydroid.data.settings

import android.content.Context
import java.io.File

class SettingsDataException(message: String) : IllegalStateException(message)

internal data class TavernStoragePaths(
    val dataRoot: File,
    val serverDir: File,
    val serverDataDir: File
) {
    companion object {
        fun from(context: Context): TavernStoragePaths {
            val filesDir = context.applicationContext.filesDir
            val dataRoot = File(filesDir, "android-tavern/data")
            return TavernStoragePaths(
                dataRoot = dataRoot,
                serverDir = File(filesDir, "android-tavern/bootstrap/server"),
                serverDataDir = File(dataRoot, "server")
            )
        }
    }

    fun ensureWorkingDirectories() {
        listOf(dataRoot, serverDir, serverDataDir).forEach { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }
}
