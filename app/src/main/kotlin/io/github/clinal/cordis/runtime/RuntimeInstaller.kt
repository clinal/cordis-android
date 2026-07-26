package io.github.clinal.cordis.runtime

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

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

        seedInstanceTemplate(paths.instanceHome(instanceId))
    }

    fun isBootstrapInstalled(): Boolean = paths.proot.canExecute()

    private fun seedInstanceTemplate(instanceHome: File) {
        val marker = instanceHome.resolve(".cordis-android-template")
        if (marker.exists()) return

        if (extractBundledBoilerplate(instanceHome)) {
            marker.writeText("${BoilerplateRelease.Version}\n")
            return
        }

        seedDefaultConfig(instanceHome)
        marker.writeText("minimal\n")
    }

    private fun extractBundledBoilerplate(instanceHome: File): Boolean {
        return try {
            appContext.assets.open(BoilerplateRelease.AssetPath).use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        val destination = instanceHome.resolve(entry.name).canonicalFile
                        if (!destination.path.startsWith(instanceHome.canonicalPath + File.separator)) {
                            error("Boilerplate zip contains an unsafe path: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            destination.mkdirs()
                        } else {
                            destination.parentFile?.mkdirs()
                            destination.outputStream().use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            true
        } catch (_: FileNotFoundException) {
            false
        }
    }

    private fun seedDefaultConfig(instanceHome: File) {
        val config = instanceHome.resolve("cordis.yml")
        if (!config.exists()) {
            appContext.assets.open("bootstrap/default-cordis.yml").use { input ->
                config.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
