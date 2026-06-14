package com.jm.sillydroid.data.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

internal object TavernRootConfigFiles {
    fun rootConfigFile(paths: TavernStoragePaths): File = File(paths.serverDir, "config.yaml")

    fun defaultConfigFile(paths: TavernStoragePaths): File = File(paths.serverDir, "default/config.yaml")

    fun legacyConfigFile(paths: TavernStoragePaths): File = File(File(paths.serverDataDir, "config"), "config.yaml")

    fun ensureRootConfigFile(paths: TavernStoragePaths): File {
        val rootConfig = rootConfigFile(paths)
        if (Files.isRegularFile(rootConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return rootConfig
        }

        if (Files.isDirectory(rootConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SettingsDataException("Tavern 根配置路径是目录，无法安全迁移：${rootConfig.absolutePath}")
        }

        if (Files.isSymbolicLink(rootConfig.toPath())) {
            if (!rootConfig.isFile || !rootConfig.canRead()) {
                throw SettingsDataException("Tavern 根配置链接目标不可读：${rootConfig.absolutePath}")
            }
            // 旧版本根配置可能是 symlink；设置页读写前必须先物化，避免继续写旧路径。
            replaceRootConfigFrom(paths, rootConfig)
            return rootConfig
        }

        if (Files.exists(rootConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SettingsDataException("Tavern 根配置路径不是普通文件，无法安全迁移：${rootConfig.absolutePath}")
        }

        val legacyConfig = legacyConfigFile(paths)
        val source = when {
            legacyConfig.isFile && legacyConfig.canRead() -> legacyConfig
            Files.exists(legacyConfig.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
                throw SettingsDataException("旧 Tavern 配置存在但不是可读文件，无法安全迁移：${legacyConfig.absolutePath}")
            }

            defaultConfigFile(paths).isFile -> defaultConfigFile(paths)
            else -> throw SettingsDataException("缺少默认 Tavern 配置模板：${defaultConfigFile(paths).absolutePath}")
        }

        replaceRootConfigFrom(paths, source)
        return rootConfig
    }

    fun replaceRootConfigFrom(paths: TavernStoragePaths, source: File) {
        if (!source.isFile || !source.canRead()) {
            throw SettingsDataException("Tavern 配置来源不可读：${source.absolutePath}")
        }
        writeRootConfig(paths, source.inputStream().use { input -> input.readBytes() })
    }

    fun writeRootConfigText(paths: TavernStoragePaths, content: String) {
        writeRootConfig(paths, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeRootConfig(paths: TavernStoragePaths, content: ByteArray) {
        val rootConfig = rootConfigFile(paths)
        rootConfig.parentFile?.mkdirs()
        val tempFile = File(rootConfig.parentFile, ".${rootConfig.name}.sillydroid-${System.currentTimeMillis()}.tmp")
        try {
            tempFile.writeBytes(content)
            Files.move(
                tempFile.toPath(),
                rootConfig.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (exception: IOException) {
            tempFile.delete()
            throw SettingsDataException("写入 Tavern 根配置失败：${exception.message ?: exception.javaClass.simpleName}")
        } catch (exception: RuntimeException) {
            tempFile.delete()
            throw exception
        }
    }
}
