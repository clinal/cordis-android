package io.github.clinal.cordis.runtime

import android.content.Context
import java.io.File

class RuntimeInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val paths = RuntimePaths(appContext)

    fun prepare(instanceId: String) {
        listOf(
            paths.root,
            paths.nixStore,
            paths.home,
            paths.home.resolve("instances"),
            paths.tmp,
            paths.shm,
            paths.instanceHome(instanceId),
        ).forEach(File::mkdirs)

        seedDefaultConfig(paths.instanceHome(instanceId))
    }

    fun isBootstrapInstalled(): Boolean = paths.proot.canExecute()

    private fun seedDefaultConfig(instanceHome: File) {
        val config = instanceHome.resolve("cordis.yml")
        if (!config.exists()) {
            appContext.assets.open("bootstrap/default-cordis.yml").use { input ->
                config.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
