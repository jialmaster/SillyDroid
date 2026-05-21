package com.jm.sillydroid.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPortOccupancyInspectorTest {

    @Test
    fun `parseListeningSocketInodes returns target listen socket only`() {
        val content = """
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:1F40 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234 0 12345 1 0000000000000000 100 0 0 10 0
           1: 0100007F:1F41 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234 0 54321 1 0000000000000000 100 0 0 10 0
           2: 0100007F:1F40 0100007F:ABCD 01 00000000:00000000 00:00000000 00000000 10234 0 99999 1 0000000000000000 100 0 0 10 0
        """.trimIndent()

        val result = ServerPortOccupancyInspector.parseListeningSocketInodes(content, 8000)

        assertEquals(setOf(12345L), result)
    }

    @Test
    fun `classifyPortOccupancy returns app owned process when matching process owns socket`() {
        val bootstrapProcess = OwnedProcessInfo(
            pid = 321,
            ppid = 1,
            name = "libproot.so",
            cmdline = "/data/app/libproot.so /tavern/server/server.js"
        )

        val result = ServerPortOccupancyInspector.classifyPortOccupancy(
            port = 8000,
            listeningSocketInodes = setOf(12345L),
            ownedProcesses = listOf(bootstrapProcess),
            socketInodesByPid = mapOf(321 to setOf(12345L))
        )

        assertTrue(result is ServerPortOccupancy.OccupiedByThisApp)
        assertEquals(321, (result as ServerPortOccupancy.OccupiedByThisApp).process.pid)
        assertTrue(result.recognizedAsBootstrapServer)
    }

    @Test
    fun `classifyPortOccupancy returns app owned process even when owner is not bootstrap server`() {
        val otherProcess = OwnedProcessInfo(
            pid = 654,
            ppid = 1,
            name = "python",
            cmdline = "python -m http.server 8000"
        )

        val result = ServerPortOccupancyInspector.classifyPortOccupancy(
            port = 8000,
            listeningSocketInodes = setOf(45678L),
            ownedProcesses = listOf(otherProcess),
            socketInodesByPid = mapOf(654 to setOf(45678L))
        )

        assertTrue(result is ServerPortOccupancy.OccupiedByThisApp)
        assertEquals(654, (result as ServerPortOccupancy.OccupiedByThisApp).process.pid)
        assertTrue(!result.recognizedAsBootstrapServer)
    }

    @Test
    fun `classifyPortOccupancy returns foreign listener when no owned process matches inode`() {
        val result = ServerPortOccupancyInspector.classifyPortOccupancy(
            port = 8000,
            listeningSocketInodes = setOf(45678L),
            ownedProcesses = emptyList(),
            socketInodesByPid = emptyMap()
        )

        assertTrue(result is ServerPortOccupancy.OccupiedByOtherProcess)
        assertTrue((result as ServerPortOccupancy.OccupiedByOtherProcess).details.contains("其他应用或系统进程"))
    }
}
