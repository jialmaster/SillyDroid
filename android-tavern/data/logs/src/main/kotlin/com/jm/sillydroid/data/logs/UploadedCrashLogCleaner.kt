package com.jm.sillydroid.data.logs

import java.io.File

internal object UploadedCrashLogCleaner {
    fun clear(crashFile: File): Boolean {
        if (!crashFile.exists()) {
            return true
        }
        if (!crashFile.isFile) {
            return false
        }
        return crashFile.delete() || !crashFile.exists()
    }
}
