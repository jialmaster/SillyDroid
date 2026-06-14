package com.jm.sillydroid.data.runtime

import com.jm.sillydroid.core.model.bootstrap.BootstrapStepDetection
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

internal data class RootConfigLayoutInspection(
    val detection: BootstrapStepDetection,
    val details: String,
    val needsMigration: Boolean
)

internal fun inspectRootConfigLayout(paths: HostPaths): RootConfigLayoutInspection {
    val rootConfig = rootConfigFile(paths)
    val legacyConfig = legacyConfigFile(paths)
    val defaultConfig = defaultConfigFile(paths)
    return when {
        isRegularFileWithoutFollowingSymlinks(rootConfig) -> RootConfigLayoutInspection(
            detection = BootstrapStepDetection.UP_TO_DATE,
            details = "Tavern 根 config.yaml 已是普通文件。",
            needsMigration = false
        )

        Files.isDirectory(rootConfig.toPath(), LinkOption.NOFOLLOW_LINKS) -> RootConfigLayoutInspection(
            detection = BootstrapStepDetection.INCOMPLETE,
            details = "Tavern 根 config.yaml 当前是目录，需要停止并由用户清理。",
            needsMigration = true
        )

        Files.isSymbolicLink(rootConfig.toPath()) -> RootConfigLayoutInspection(
            detection = BootstrapStepDetection.INCOMPLETE,
            details = "Tavern 根 config.yaml 当前是符号链接，需要物化为普通文件。",
            needsMigration = true
        )

        existsWithoutFollowingSymlinks(rootConfig) -> RootConfigLayoutInspection(
            detection = BootstrapStepDetection.INCOMPLETE,
            details = "Tavern 根 config.yaml 不是可用普通文件，需要停止并由用户清理。",
            needsMigration = true
        )

        legacyConfig.isFile -> RootConfigLayoutInspection(
            detection = BootstrapStepDetection.INCOMPLETE,
            details = "Tavern 根 config.yaml 缺失，将从旧数据配置迁移。",
            needsMigration = true
        )

        defaultConfig.isFile -> RootConfigLayoutInspection(
            detection = BootstrapStepDetection.INCOMPLETE,
            details = "Tavern 根 config.yaml 缺失，将从默认配置初始化。",
            needsMigration = true
        )

        else -> RootConfigLayoutInspection(
            detection = BootstrapStepDetection.INCOMPLETE,
            details = "Tavern 根 config.yaml 缺失，等待 server 资产解包后初始化。",
            needsMigration = true
        )
    }
}

internal fun ensureRootConfigLayout(paths: HostPaths): Boolean {
    paths.serverDir.mkdirs()
    val rootConfig = rootConfigFile(paths)
    if (isRegularFileWithoutFollowingSymlinks(rootConfig)) {
        return false
    }

    if (Files.isDirectory(rootConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw BootstrapException("Tavern 根配置路径是目录，无法安全迁移：${rootConfig.absolutePath}")
    }

    if (Files.isSymbolicLink(rootConfig.toPath())) {
        if (!rootConfig.isFile || !rootConfig.canRead()) {
            throw BootstrapException("Tavern 根配置链接目标不可读：${rootConfig.absolutePath}")
        }
        // 旧版本把根配置做成符号链接；这里先完整复制目标内容，再替换链接本身，避免丢失用户配置。
        materializeRootConfig(source = rootConfig, target = rootConfig)
        return true
    }

    if (existsWithoutFollowingSymlinks(rootConfig)) {
        throw BootstrapException("Tavern 根配置路径不是普通文件，无法安全迁移：${rootConfig.absolutePath}")
    }

    val legacyConfig = legacyConfigFile(paths)
    val source = when {
        legacyConfig.isFile && legacyConfig.canRead() -> legacyConfig
        existsWithoutFollowingSymlinks(legacyConfig) -> {
            throw BootstrapException("旧 Tavern 配置存在但不是可读文件，无法安全迁移：${legacyConfig.absolutePath}")
        }

        defaultConfigFile(paths).isFile -> defaultConfigFile(paths)
        else -> throw BootstrapException("缺少默认 Tavern 配置模板：${defaultConfigFile(paths).absolutePath}")
    }

    materializeRootConfig(source = source, target = rootConfig)
    return true
}

internal fun shouldSkipServerArchiveEntry(relativePath: String): Boolean {
    return relativePath == "config.yaml"
}

private fun rootConfigFile(paths: HostPaths): File = File(paths.serverDir, "config.yaml")

private fun legacyConfigFile(paths: HostPaths): File = File(File(paths.serverDataDir, "config"), "config.yaml")

private fun defaultConfigFile(paths: HostPaths): File = File(paths.serverDir, "default/config.yaml")

private fun materializeRootConfig(source: File, target: File) {
    target.parentFile?.mkdirs()
    val tempFile = File(target.parentFile, ".${target.name}.sillydroid-${System.currentTimeMillis()}.tmp")
    try {
        source.inputStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Files.move(
            tempFile.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (exception: IOException) {
        tempFile.delete()
        throw BootstrapException("写入 Tavern 根配置失败：${exception.message ?: exception.javaClass.simpleName}")
    } catch (exception: RuntimeException) {
        tempFile.delete()
        throw exception
    }
}

private fun existsWithoutFollowingSymlinks(file: File): Boolean {
    return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}

private fun isRegularFileWithoutFollowingSymlinks(file: File): Boolean {
    return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
