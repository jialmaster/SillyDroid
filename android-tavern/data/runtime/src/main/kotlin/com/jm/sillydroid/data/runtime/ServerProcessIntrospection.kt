package com.jm.sillydroid.data.runtime

import android.system.Os
import java.io.File

internal data class OwnedProcessInfo(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val cmdline: String
)

internal fun OwnedProcessInfo.isBootstrapServerProcess(): Boolean {
    return name == "libtermux-node.so" ||
        cmdline.contains("libtermux-node.so") ||
        cmdline.contains("server.js") ||
        cmdline.contains("start-server.sh") ||
        cmdline.contains("tavern-entrypoint.sh") ||
        cmdline.contains("/tavern/server")
}

internal fun listOwnedProcesses(
    procRoot: File = File("/proc"),
    currentPid: Int = android.os.Process.myPid(),
    currentUid: String = android.os.Process.myUid().toString()
): List<OwnedProcessInfo> {
    return procRoot.listFiles().orEmpty().mapNotNull { procDir ->
        val pid = procDir.name.toIntOrNull() ?: return@mapNotNull null
        if (pid == currentPid) {
            return@mapNotNull null
        }

        val statusLines = runCatching {
            File(procDir, "status").readLines()
        }.getOrNull() ?: return@mapNotNull null

        val uidLine = statusLines.firstOrNull { line -> line.startsWith("Uid:") } ?: return@mapNotNull null
        val uid = uidLine.substringAfter(':').trim().substringBefore('\t').substringBefore(' ')
        if (uid != currentUid) {
            return@mapNotNull null
        }

        val name = statusLines.firstOrNull { line -> line.startsWith("Name:") }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
        val ppid = statusLines.firstOrNull { line -> line.startsWith("PPid:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        val cmdline = readProcCmdline(File(procDir, "cmdline"))

        OwnedProcessInfo(pid = pid, ppid = ppid, name = name, cmdline = cmdline)
    }
}

internal fun listSocketInodesForProcess(
    procRoot: File = File("/proc"),
    pid: Int
): Set<Long> {
    val fdDirectory = File(procRoot, "$pid/fd")
    return fdDirectory.listFiles().orEmpty().mapNotNull { fdEntry ->
        val linkTarget = runCatching {
            Os.readlink(fdEntry.absolutePath)
        }.getOrNull() ?: return@mapNotNull null
        parseSocketInode(linkTarget)
    }.toSet()
}

internal fun parseSocketInode(linkTarget: String): Long? {
    val startIndex = linkTarget.indexOf("socket:[")
    if (startIndex < 0) {
        return null
    }
    val inodeText = linkTarget.substring(startIndex + "socket:[".length).substringBefore(']')
    return inodeText.toLongOrNull()
}

internal fun readProcCmdline(cmdlineFile: File): String {
    return runCatching {
        cmdlineFile.readBytes()
            .toString(Charsets.UTF_8)
            .replace('\u0000', ' ')
            .trim()
    }.getOrDefault("")
}
