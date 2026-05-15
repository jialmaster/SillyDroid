package com.jm.sillydroid.data.logs

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ApplicationExitInfoLogStore {
    private const val maxTraceChars = 240_000
    private const val historyLimit = 16

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var refreshJob: Job? = null

    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (refreshJob?.isActive == true) {
                return
            }

            refreshJob = scope.launch {
                try {
                    refresh(appContext)
                } finally {
                    synchronized(this@ApplicationExitInfoLogStore) {
                        refreshJob = null
                    }
                }
            }
        }
    }

    private fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            HostLogManager.writeExitInfoLog(context, null)
            return
        }

        val content = runCatching {
            buildExitInfoLogContent(context)
        }.getOrNull()

        HostLogManager.writeExitInfoLog(context, content)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun buildExitInfoLogContent(context: Context): String? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exitInfos = activityManager.getHistoricalProcessExitReasons(null, 0, historyLimit)
        if (exitInfos.isEmpty()) {
            return null
        }

        val latestHistoricalExit = exitInfos.maxByOrNull { info -> info.timestamp } ?: return null
        val latestAbnormalExit = exitInfos
            .filterNot { info -> isExpectedExitReason(info.reason) }
            .maxByOrNull { info -> info.timestamp }

        return buildString {
            appendLine("generatedAt=${formatTimestamp(System.currentTimeMillis())}")
            appendLine("packageName=${context.packageName}")
            appendLine("historyCount=${exitInfos.size}")
            appendLine("latestAbnormalExitPresent=${latestAbnormalExit != null}")

            if (latestAbnormalExit != null) {
                appendExitInfoSection("latestAbnormalExit", latestAbnormalExit)
            }

            if (latestHistoricalExit !== latestAbnormalExit) {
                appendExitInfoSection("latestHistoricalExit", latestHistoricalExit)
            }
        }.trimEnd()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun StringBuilder.appendExitInfoSection(
        sectionName: String,
        exitInfo: ApplicationExitInfo
    ) {
        appendLine()
        appendLine("[$sectionName]")
        appendLine("timestamp=${formatTimestamp(exitInfo.timestamp)}")
        appendLine("reason=${reasonToString(exitInfo.reason)}(${exitInfo.reason})")
        appendLine("status=${exitInfo.status}")
        appendLine("importance=${exitInfo.importance}")
        appendLine("pid=${exitInfo.pid}")
        appendLine("realUid=${exitInfo.realUid}")
        appendLine("packageUid=${exitInfo.packageUid}")
        appendLine("processName=${exitInfo.processName}")
        appendLine("description=${exitInfo.description.orEmpty().ifBlank { "-" }}")
        appendLine("pssKb=${exitInfo.pss}")
        appendLine("rssKb=${exitInfo.rss}")

        val trace = readTrace(exitInfo)
        if (trace.isNotBlank()) {
            appendLine()
            appendLine("[${sectionName}.trace]")
            appendLine(trace)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(exitInfo: ApplicationExitInfo): String {
        val rawTrace = runCatching {
            exitInfo.traceInputStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                reader.readText()
            }.orEmpty()
        }.getOrDefault("")

        val trimmedTrace = rawTrace.trim()
        if (trimmedTrace.length <= maxTraceChars) {
            return trimmedTrace
        }

        return buildString {
            appendLine("[truncated]")
            append(trimmedTrace.takeLast(maxTraceChars))
        }
    }

    private fun formatTimestamp(epochMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(epochMillis))
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun isExpectedExitReason(reason: Int): Boolean {
        return when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> true
            else -> false
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun reasonToString(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            else -> "UNKNOWN"
        }
    }
}
