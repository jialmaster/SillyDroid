package com.jm.sillydroid

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal object TavernServerLogTailStore {
    private const val tavernServerLogFileName = "sillydroid-server.log"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLatestLine = MutableStateFlow("")

    val latestLine = mutableLatestLine.asStateFlow()

    private var observedLogFile: File? = null
    private var fileObserver: FileObserver? = null
    private var refreshJob: Job? = null
    private var refreshPending = false

    fun start(context: Context) {
        val applicationContext = context.applicationContext
        val logFile = File(HostPaths.from(applicationContext).logsDir, tavernServerLogFileName)

        synchronized(this) {
            if (observedLogFile?.absolutePath == logFile.absolutePath && fileObserver != null) {
                requestRefreshLocked()
                return
            }

            stopLocked(clearState = false)
            observedLogFile = logFile
            logFile.parentFile?.mkdirs()
            val logsDir = logFile.parentFile ?: return
            val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE
            fileObserver = object : FileObserver(logsDir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && !path.equals(tavernServerLogFileName, ignoreCase = true)) {
                        return
                    }

                    synchronized(this@TavernServerLogTailStore) {
                        requestRefreshLocked()
                    }
                }
            }.also { observer ->
                observer.startWatching()
            }
            requestRefreshLocked()
        }
    }

    fun stop() {
        synchronized(this) {
            stopLocked(clearState = true)
        }
    }

    private fun stopLocked(clearState: Boolean) {
        fileObserver?.stopWatching()
        fileObserver = null
        observedLogFile = null
        refreshPending = false
        refreshJob?.cancel()
        refreshJob = null
        if (clearState) {
            mutableLatestLine.value = ""
        }
    }

    private fun requestRefreshLocked() {
        if (refreshJob?.isActive == true) {
            refreshPending = true
            return
        }

        val logFile = observedLogFile ?: return
        refreshJob = scope.launch {
            do {
                synchronized(this@TavernServerLogTailStore) {
                    refreshPending = false
                }

                mutableLatestLine.value = HostLogReader.readLastNonBlankLine(logFile)

                val shouldContinue = synchronized(this@TavernServerLogTailStore) {
                    refreshPending && observedLogFile?.absolutePath == logFile.absolutePath
                }
                if (!shouldContinue) {
                    break
                }
            } while (true)
        }
    }
}
