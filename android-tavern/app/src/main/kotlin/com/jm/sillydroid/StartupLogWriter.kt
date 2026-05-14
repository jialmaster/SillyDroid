package com.jm.sillydroid

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal class StartupLogWriter(
    private val logFileProvider: () -> File
) {
    companion object {
        private const val logTag = "StartupLogWriter"
        private const val shutdownTimeoutMillis = 750L
    }

    private sealed class Command {
        data class Reset(val sessionId: Long) : Command()
        data class Append(val sessionId: Long, val line: String) : Command()
    }

    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private val writerJob = writerScope.launch {
        var activeSessionId = 0L
        for (command in commandChannel) {
            when (command) {
                is Command.Reset -> {
                    activeSessionId = command.sessionId
                    runCatching {
                        val logFile = ensureLogFile()
                        logFile.writeText("")
                    }.onFailure { error ->
                        Log.e(logTag, "Failed to reset startup log.", error)
                    }
                }

                is Command.Append -> {
                    if (command.sessionId != activeSessionId) {
                        continue
                    }

                    runCatching {
                        val logFile = ensureLogFile()
                        logFile.appendText(command.line)
                    }.onFailure { error ->
                        Log.e(logTag, "Failed to append startup log.", error)
                    }
                }
            }
        }
    }

    fun reset(sessionId: Long) {
        submit(Command.Reset(sessionId))
    }

    fun append(sessionId: Long, line: String) {
        submit(Command.Append(sessionId, line))
    }

    fun close() {
        commandChannel.close()
        runBlocking {
            val writerClosed = withTimeoutOrNull(shutdownTimeoutMillis) {
                writerJob.join()
                true
            } ?: false
            if (!writerClosed) {
                writerJob.cancel()
            }
        }
        writerScope.cancel()
    }

    private fun submit(command: Command) {
        val result = commandChannel.trySend(command)
        if (result.isFailure) {
            Log.w(logTag, "Ignoring startup log command because writer is already closed.")
        }
    }

    private fun ensureLogFile(): File {
        val logFile = logFileProvider()
        logFile.parentFile?.mkdirs()
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        return logFile
    }
}
