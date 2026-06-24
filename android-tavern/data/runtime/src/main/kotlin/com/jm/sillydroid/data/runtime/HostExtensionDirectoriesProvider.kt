package com.jm.sillydroid.data.runtime
import android.content.Context
import com.jm.sillydroid.domain.extensions.ExtensionDirectories
import com.jm.sillydroid.domain.extensions.ExtensionDirectoriesProvider
import java.io.File

class HostExtensionDirectoriesProvider(context: Context) : ExtensionDirectoriesProvider {
    private val appContext = context.applicationContext

    override fun directories(): ExtensionDirectories {
        val paths = HostPaths.from(appContext)
        paths.ensureWorkingDirectories()
        return ExtensionDirectories(
            globalExtensionsDir = File(paths.serverDataDir, "extensions"),
            userExtensionsDir = File(File(paths.serverDataDir, "data"), "default-user/extensions"),
            serverPluginsDir = File(paths.serverDataDir, "plugins"),
            bundledExtensionsDir = File(paths.bootstrapRoot, "bundled-extensions"),
            defaultExtensionsConfigFile = File(paths.bootstrapRoot, "default-extensions/sillydroid-build-config.json")
        )
    }
}
