package com.jm.sillydroid.domain.runtime

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 运行时启动/服务进程日志的领域抽象。data/runtime 通过此接口访问 host 日志能力，
 * 避免直接依赖 data/logs 模块。
 */
interface RuntimeLogManager {
    val latestTavernServerLine: StateFlow<String>

    fun initializeForAppStart()
    fun currentAppSessionId(): String
    fun retainedAppSessionLimit(): Int
    fun runtimeLogFileName(baseName: String): String
    fun currentStartupLogFile(): File
    fun currentStartupLogFileName(): String
    fun currentServerLogFile(): File
    fun currentServerLogFileName(): String
    fun currentRootfsRuntimeLogFile(): File
    fun currentRootfsRuntimeLogFileName(): String
    fun startCurrentServerTail()
    fun stopCurrentServerTail()
    fun openStartupAsyncWriter(): RuntimeAsyncLogWriter
}

interface RuntimeAsyncLogWriter : AutoCloseable {
    fun reset(sessionId: Long)
    fun append(sessionId: Long, line: String)
    override fun close()
}
