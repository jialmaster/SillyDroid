package com.jm.sillydroid.data.extensions

import com.jm.sillydroid.domain.extensions.ExtensionDirectories
import com.jm.sillydroid.domain.extensions.ExtensionDirectoriesProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionsLocalDataSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `installed server plugin reads manifest metadata without package json`() {
        val directories = createDirectories()
        val pluginDir = directories.serverPluginsDir.resolve("log").apply { mkdirs() }
        File(pluginDir, "manifest.json").writeText(
            """
            {
              "display_name": "HRS Log",
              "version": "0.4.0",
              "description": "Host runtime log plugin",
              "homePage": "https://github.com/example/hrs-log"
            }
            """.trimIndent()
        )

        val inventory = ExtensionsLocalDataSource(FakeExtensionDirectoriesProvider(directories)).loadInventory()

        val plugin = inventory.installedPlugins.single()
        assertEquals("log", plugin.folderName)
        assertEquals("HRS Log", plugin.displayName)
        assertEquals("0.4.0", plugin.version)
        assertEquals("Host runtime log plugin", plugin.description)
        assertEquals("https://github.com/example/hrs-log", plugin.repositoryUrl)
        assertTrue(plugin.packageHealthy)
        assertNull(plugin.packageMessage)
    }

    @Test
    fun `installed server plugin prefers package metadata but keeps manifest fallback`() {
        val directories = createDirectories()
        val pluginDir = directories.serverPluginsDir.resolve("hybrid-plugin").apply { mkdirs() }
        File(pluginDir, "manifest.json").writeText(
            """
            {
              "display_name": "Manifest Name",
              "version": "1.0.0",
              "description": "Manifest description"
            }
            """.trimIndent()
        )
        File(pluginDir, "package.json").writeText(
            """
            {
              "name": "package-name",
              "repository": { "url": "git+https://github.com/example/hybrid-plugin.git" }
            }
            """.trimIndent()
        )

        val inventory = ExtensionsLocalDataSource(FakeExtensionDirectoriesProvider(directories)).loadInventory()

        val plugin = inventory.installedPlugins.single()
        assertEquals("package-name", plugin.displayName)
        assertEquals("1.0.0", plugin.version)
        assertEquals("Manifest description", plugin.description)
        assertEquals("https://github.com/example/hybrid-plugin.git", plugin.repositoryUrl)
        assertTrue(plugin.packageHealthy)
        assertNull(plugin.packageMessage)
    }

    private fun createDirectories(): ExtensionDirectories {
        val root = temporaryFolder.newFolder("extension-dirs")
        return ExtensionDirectories(
            globalExtensionsDir = File(root, "extensions-global").apply { mkdirs() },
            userExtensionsDir = File(root, "extensions-user").apply { mkdirs() },
            serverPluginsDir = File(root, "server-plugins").apply { mkdirs() },
            bundledExtensionsDir = File(root, "bundled").apply { mkdirs() },
            defaultExtensionsConfigFile = File(root, "default-extensions.json")
        )
    }
}

private class FakeExtensionDirectoriesProvider(
    private val directories: ExtensionDirectories
) : ExtensionDirectoriesProvider {
    override fun directories(): ExtensionDirectories = directories
}
