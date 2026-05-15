package com.jm.sillydroid.data.logs

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object CrashLogStore {
    private const val logTag = "CrashLogStore"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) {
            return
        }

        synchronized(this) {
            if (installed) {
                return
            }

            val appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    writeCrashLog(appContext, thread, throwable)
                }.onFailure { error ->
                    Log.e(logTag, "Failed to persist crash log.", error)
                }

                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }

            installed = true
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        HostLogManager.writeCrashLog(
            context = context,
            content = buildCrashLogContent(context, thread, throwable)
        )
    }

    private fun buildCrashLogContent(context: Context, thread: Thread, throwable: Throwable): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            context.packageName
        }
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { printWriter ->
                throwable.printStackTrace(printWriter)
            }
        }.toString().trimEnd()

        return buildString {
            appendLine("timestamp=$timestamp")
            appendLine("packageName=${context.packageName}")
            appendLine("processName=$processName")
            appendLine("pid=${Process.myPid()}")
            appendLine("threadName=${thread.name}")
            appendLine("threadId=${thread.id}")
            appendLine("hostVersion=${BuildConfig.SILLYDROID_HOST_VERSION}")
            appendLine("androidSdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine(stackTrace)
        }
    }
}
