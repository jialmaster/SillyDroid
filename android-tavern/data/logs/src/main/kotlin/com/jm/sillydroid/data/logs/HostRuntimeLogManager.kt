package com.jm.sillydroid.data.logs

import android.content.Context
import com.jm.sillydroid.domain.runtime.RuntimeAsyncLogWriter
import com.jm.sillydroid.domain.runtime.RuntimeLogManager
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 适配器：把 [HostLogManager] 的 host 日志能力映射到 domain.runtime 的 [RuntimeLogManager] 接口。
 * data/runtime 在不引用 data/logs 的前提下消费这些方法。
 */
class HostRuntimeLogManager(context: Context) : RuntimeLogManager {
    private val appContext = context.applicationContext

    override val latestTavernServerLine: StateFlow<String>
        get() = HostLogManager.latestTavernServerLine

    override fun initializeForAppStart() {
        HostLogManager.initializeForAppStart(appContext)
    }

    override fun currentAppSessionId(): String = HostLogManager.currentAppSessionId(appContext)

    override fun retainedAppSessionLimit(): Int = HostLogManager.retainedAppSessionLimit()

    override fun runtimeLogFileName(baseName: String): String = HostLogManager.runtimeLogFileName(baseName)

    override fun currentStartupLogFile(): File = HostLogManager.currentStartupLogFile(appContext)

    override fun currentStartupLogFileName(): String = HostLogManager.currentStartupLogFileName(appContext)

    override fun currentServerLogFile(): File = HostLogManager.currentServerLogFile(appContext)

    override fun currentServerLogFileName(): String = HostLogManager.currentServerLogFileName(appContext)

    override fun currentRootfsRuntimeLogFile(): File = HostLogManager.currentRootfsRuntimeLogFile(appContext)

    override fun currentRootfsRuntimeLogFileName(): String =
        HostLogManager.currentRootfsRuntimeLogFileName(appContext)

    override fun startCurrentServerTail() {
        HostLogManager.startCurrentServerTail(appContext)
    }

    override fun stopCurrentServerTail() {
        HostLogManager.stopCurrentServerTail()
    }

    override fun openStartupAsyncWriter(): RuntimeAsyncLogWriter {
        val writer = HostLogManager.AsyncWriter { HostLogManager.currentStartupLogFile(appContext) }
        return object : RuntimeAsyncLogWriter {
            override fun reset(sessionId: Long) = writer.reset(sessionId)
            override fun append(sessionId: Long, line: String) = writer.append(sessionId, line)
            override fun close() = writer.close()
        }
    }
}
