package com.jm.sillydroid.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class HostRuntimeDiagnosticTest {

    @Test
    fun `selinux diagnostic keeps valid enforcing state`() {
        assertEquals("Enforcing", normalizeSelinuxDiagnosticState("Enforcing\n"))
    }

    @Test
    fun `selinux diagnostic hides sandbox permission errors`() {
        assertEquals(
            "unavailable",
            normalizeSelinuxDiagnosticState("getenforce: Couldn't get enforcing status: Permission denied")
        )
    }

    @Test
    fun `selinux diagnostic hides blank output`() {
        assertEquals("unavailable", normalizeSelinuxDiagnosticState(""))
    }
}
