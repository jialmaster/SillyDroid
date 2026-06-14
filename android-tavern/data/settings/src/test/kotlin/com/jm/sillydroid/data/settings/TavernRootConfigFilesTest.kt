package com.jm.sillydroid.data.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRootConfigFilesTest {

    @Test
    fun `ensureRootConfigFile migrates legacy config without modifying legacy path`() {
        val rootDirectory = createTempDirectory(prefix = "settings-root-config-legacy").toFile()
        try {
            val paths = createStoragePaths(rootDirectory)
            val legacyConfig = File(paths.serverDataDir, "config/config.yaml").apply {
                parentFile?.mkdirs()
                writeText("port: 18000\n")
            }

            val rootConfig = TavernRootConfigFiles.ensureRootConfigFile(paths)

            assertEquals(File(paths.serverDir, "config.yaml"), rootConfig)
            assertEquals("port: 18000\n", rootConfig.readText())
            assertEquals("port: 18000\n", legacyConfig.readText())
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `writeManagedDataArchive materializes root config into exported config directory`() {
        val rootDirectory = createTempDirectory(prefix = "settings-export-root-config").toFile()
        try {
            val paths = createStoragePaths(rootDirectory)
            File(paths.serverDir, "config.yaml").writeText("root: true\n")
            File(paths.serverDataDir, "config/config.yaml").apply {
                parentFile?.mkdirs()
                writeText("legacy: true\n")
            }
            File(paths.serverDataDir, "data/default-user/settings.json").apply {
                parentFile?.mkdirs()
                writeText("""{"theme":"default"}""")
            }

            val output = ByteArrayOutputStream()
            writeManagedDataArchive(paths, output)
            val entries = unzipEntries(output.toByteArray())

            assertEquals("root: true\n", entries.getValue("config/config.yaml"))
            assertEquals("""{"theme":"default"}""", entries.getValue("data/default-user/settings.json"))
            assertTrue(entries.containsKey("plugins/"))
            assertTrue(entries.containsKey("extensions/"))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `findImportedRootConfigFile prefers top level config over managed config directory`() {
        val rootDirectory = createTempDirectory(prefix = "settings-import-root-config").toFile()
        try {
            val sourceRoot = File(rootDirectory, "archive").apply { mkdirs() }
            val managedConfig = File(sourceRoot, "config/config.yaml").apply {
                parentFile?.mkdirs()
                writeText("managed: true\n")
            }
            val topLevelConfig = File(sourceRoot, "config.yaml").apply {
                writeText("top: true\n")
            }

            assertEquals(topLevelConfig, findImportedRootConfigFile(sourceRoot, requireNotNull(managedConfig.parentFile)))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `replaceContentWithImportedRootConfig restores top level config to root config`() {
        val rootDirectory = createTempDirectory(prefix = "settings-import-root-write").toFile()
        try {
            val paths = createStoragePaths(rootDirectory)
            File(paths.serverDir, "config.yaml").writeText("old: true\n")
            val sourceRoot = File(rootDirectory, "archive").apply { mkdirs() }
            val topLevelConfig = File(sourceRoot, "config.yaml").apply {
                writeText("top: true\n")
            }

            replaceContentWithImportedRootConfig(
                paths = paths,
                importedRootConfig = findImportedRootConfigFile(sourceRoot),
                replaceContent = {}
            )

            assertEquals("top: true\n", File(paths.serverDir, "config.yaml").readText())
            assertEquals(topLevelConfig, findImportedRootConfigFile(sourceRoot))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `replaceContentWithImportedRootConfig rolls back root config when import fails`() {
        val rootDirectory = createTempDirectory(prefix = "settings-import-root-rollback").toFile()
        try {
            val paths = createStoragePaths(rootDirectory)
            val rootConfig = File(paths.serverDir, "config.yaml").apply {
                writeText("old: true\n")
            }
            val importedConfig = File(rootDirectory, "config.yaml").apply {
                writeText("new: true\n")
            }

            assertThrows(IllegalStateException::class.java) {
                replaceContentWithImportedRootConfig(
                    paths = paths,
                    importedRootConfig = importedConfig,
                    replaceContent = { error("restore managed data failed") }
                )
            }

            assertEquals("old: true\n", rootConfig.readText())
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                entries[entry.name] = if (entry.isDirectory) {
                    ""
                } else {
                    zipInput.readBytes().toString(Charsets.UTF_8)
                }
                zipInput.closeEntry()
            }
        }
        return entries
    }

    private fun createStoragePaths(rootDirectory: File): TavernStoragePaths {
        val dataRoot = File(rootDirectory, "data").apply { mkdirs() }
        return TavernStoragePaths(
            dataRoot = dataRoot,
            serverDir = File(rootDirectory, "bootstrap/server").apply { mkdirs() },
            serverDataDir = File(dataRoot, "server").apply { mkdirs() }
        )
    }
}
