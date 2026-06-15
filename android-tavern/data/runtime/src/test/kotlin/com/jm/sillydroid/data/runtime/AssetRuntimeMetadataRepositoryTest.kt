package com.jm.sillydroid.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetRuntimeMetadataRepositoryTest {

    @Test
    fun `runtime label includes host mode base version and usr archive fingerprint`() {
        val label = resolveRootfsRuntimeVersionLabel(
            RootfsRuntimeVersionMetadata(
                runtimeVersion = "1.0.0",
                baseFlavor = "termux",
                runtimeMode = "termux-host",
                baseVersion = "stable-dash.0.5.12-2",
                usrArchiveSha256 = "ce58012b27afe5ce4c798a2b47911fdc2a306332536d2b2d9f80d02f8d696794"
            )
        )

        assertEquals("termux-host · stable-dash.0.5.12-2 · usr ce58012b", label)
    }

    @Test
    fun `runtime label avoids fixed layout version when content metadata is available without hash`() {
        val label = resolveRootfsRuntimeVersionLabel(
            RootfsRuntimeVersionMetadata(
                runtimeVersion = "1.0.0",
                baseFlavor = "termux",
                runtimeMode = "termux-host",
                baseVersion = "stable-dash.0.5.12-2",
                usrArchiveSha256 = ""
            )
        )

        assertEquals("termux-host · stable-dash.0.5.12-2", label)
    }

    @Test
    fun `runtime label falls back to legacy runtime version only when content metadata is absent`() {
        val label = resolveRootfsRuntimeVersionLabel(
            RootfsRuntimeVersionMetadata(
                runtimeVersion = "1.0.0",
                baseFlavor = "",
                runtimeMode = "",
                baseVersion = "",
                usrArchiveSha256 = ""
            )
        )

        assertEquals("1.0.0", label)
    }
}
