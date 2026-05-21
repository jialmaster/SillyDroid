package com.jm.sillydroid.data.runtime

import java.io.File

internal sealed interface ServerPortOccupancy {
    val port: Int

    data class Free(override val port: Int) : ServerPortOccupancy

    data class OccupiedByThisApp(
        override val port: Int,
        val process: OwnedProcessInfo,
        val recognizedAsBootstrapServer: Boolean
    ) : ServerPortOccupancy

    data class OccupiedByOtherProcess(
        override val port: Int,
        val details: String
    ) : ServerPortOccupancy
}

/**
 * 端口判定不能只做一次 HTTP 探针：
 * 这里必须先确认“是否真的有监听 socket”，再把 socket inode 反查到进程，
 * 才能区分“这是宿主自己托管的 Tavern 进程”还是“别的进程已经抢占了配置端口”。
 */
internal class ServerPortOccupancyInspector(
    private val procRoot: File = File("/proc")
) {
    fun inspect(port: Int): ServerPortOccupancy {
        val listeningSocketInodes = resolveListeningSocketInodes(port)
        if (listeningSocketInodes.isEmpty()) {
            return ServerPortOccupancy.Free(port)
        }

        val ownedProcesses = listOwnedProcesses(procRoot = procRoot)
        val socketInodesByPid = ownedProcesses.associate { process ->
            process.pid to listSocketInodesForProcess(procRoot = procRoot, pid = process.pid)
        }
        return classifyPortOccupancy(
            port = port,
            listeningSocketInodes = listeningSocketInodes,
            ownedProcesses = ownedProcesses,
            socketInodesByPid = socketInodesByPid
        )
    }

    private fun resolveListeningSocketInodes(port: Int): Set<Long> {
        val tcpInodes = readListeningSocketInodes(File(procRoot, "net/tcp"), port)
        val tcp6Inodes = readListeningSocketInodes(File(procRoot, "net/tcp6"), port)
        return tcpInodes + tcp6Inodes
    }

    private fun readListeningSocketInodes(sourceFile: File, port: Int): Set<Long> {
        val content = runCatching {
            sourceFile.readText()
        }.getOrDefault("")
        return parseListeningSocketInodes(content, port)
    }

    companion object {
        private const val listenSocketState = "0A"
        private const val localAddressColumnIndex = 1
        private const val socketStateColumnIndex = 3
        private const val socketInodeColumnIndex = 9

        internal fun parseListeningSocketInodes(content: String, port: Int): Set<Long> {
            return content.lineSequence().mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size <= socketInodeColumnIndex) {
                    return@mapNotNull null
                }
                if (!columns[socketStateColumnIndex].equals(listenSocketState, ignoreCase = true)) {
                    return@mapNotNull null
                }

                val localPort = columns[localAddressColumnIndex]
                    .substringAfter(':', missingDelimiterValue = "")
                    .toIntOrNull(16)
                    ?: return@mapNotNull null
                if (localPort != port) {
                    return@mapNotNull null
                }

                columns[socketInodeColumnIndex].toLongOrNull()
            }.toSet()
        }

        internal fun classifyPortOccupancy(
            port: Int,
            listeningSocketInodes: Set<Long>,
            ownedProcesses: List<OwnedProcessInfo>,
            socketInodesByPid: Map<Int, Set<Long>>
        ): ServerPortOccupancy {
            val matchingOwnedProcesses = ownedProcesses.filter { process ->
                socketInodesByPid[process.pid].orEmpty().any(listeningSocketInodes::contains)
            }
            val ownedProcess = matchingOwnedProcesses
                .sortedByDescending { process -> process.isBootstrapServerProcess() }
                .firstOrNull()
            if (ownedProcess != null) {
                return ServerPortOccupancy.OccupiedByThisApp(
                    port = port,
                    process = ownedProcess,
                    recognizedAsBootstrapServer = ownedProcess.isBootstrapServerProcess()
                )
            }

            return ServerPortOccupancy.OccupiedByOtherProcess(
                port = port,
                details = "Tavern 配置端口 $port 已处于监听状态，但当前未识别到属于本应用的 Tavern 监听进程，通常表示被其他应用或系统进程占用。"
            )
        }
    }
}
