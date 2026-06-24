package com.jm.sillydroid.domain.extensions

import java.io.File

data class ExtensionDirectories(
    val globalExtensionsDir: File,
    val userExtensionsDir: File,
    val serverPluginsDir: File,
    val bundledExtensionsDir: File,
    val defaultExtensionsConfigFile: File
)

interface ExtensionDirectoriesProvider {
    fun directories(): ExtensionDirectories
}
