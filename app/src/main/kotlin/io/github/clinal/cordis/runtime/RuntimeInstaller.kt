package io.github.clinal.cordis.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipInputStream

class RuntimeInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val paths = RuntimePaths(appContext)

    fun prepare(instanceId: String) {
        installBootstrap()
        paths.home.resolve("instances").mkdirs()
        paths.instanceHome(instanceId).mkdirs()

        seedInstanceTemplate(paths.instanceHome(instanceId))
    }

    fun isBootstrapInstalled(): Boolean = paths.proot.canExecute() && paths.envFile.exists()

    private fun installBootstrap() {
        paths.filesDir.mkdirs()
        paths.tmp.mkdirs()
        paths.shm.mkdirs()

        try {
            if (!paths.root.exists()) {
                unpackBootstrap()
            }

            if (!paths.envFile.exists()) {
                copyAsset("bootstrap/env.txt", paths.envFile)
            }
        } catch (error: BootstrapAssetMissingException) {
            Log.i(TAG, "Bootstrap assets are not packaged in this build.", error)
        }
    }

    private fun unpackBootstrap() {
        val staging = paths.filesDir.resolve("data-staging")
        if (staging.exists()) {
            staging.deleteRecursively()
        }
        if (!staging.mkdirs()) {
            error("Cannot create bootstrap staging directory: ${staging.absolutePath}")
        }

        try {
            unpackZip(
                assetPath = "bootstrap/bootstrap.zip",
                target = staging,
                restoreMetadata = true,
            )
            if (!staging.renameTo(paths.root)) {
                error("Cannot move bootstrap staging directory into place.")
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

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
                        writeZipEntry(instanceHome, entry.name, entry.isDirectory, zip)
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
            copyAsset("bootstrap/default-cordis.yml", config)
        }
    }

    private fun unpackZip(assetPath: String, target: File, restoreMetadata: Boolean) {
        val executables = mutableListOf<String>()
        val symlinks = mutableListOf<Pair<String, String>>()

        appContext.assets.open(assetPath).use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    when (entry.name) {
                        "EXECUTABLES.txt" -> executables += zip.reader().readText()
                            .lineSequence()
                            .filter(String::isNotBlank)
                        "SYMLINKS.txt" -> symlinks += zip.reader().readText()
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .map { line ->
                                val index = line.indexOf(SYMLINK_SEPARATOR)
                                require(index > 0) { "Invalid symlink entry: $line" }
                                line.substring(0, index) to line.substring(index + 1)
                            }
                        else -> writeZipEntry(target, entry.name, entry.isDirectory, zip)
                    }
                    zip.closeEntry()
                }
            }
        }

        if (restoreMetadata) {
            restoreExecutables(target, executables)
            restoreSymlinks(target, symlinks)
        }
    }

    private fun writeZipEntry(target: File, name: String, directory: Boolean, zip: ZipInputStream) {
        val destination = target.resolve(name).canonicalFile
        if (!destination.path.startsWith(target.canonicalPath + File.separator)) {
            error("Zip contains an unsafe path: $name")
        }

        if (directory) {
            destination.mkdirs()
        } else {
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output -> zip.copyTo(output) }
        }
    }

    private fun restoreExecutables(target: File, executables: List<String>) {
        executables.forEach { executable ->
            try {
                Os.chmod(target.resolve(executable).absolutePath, EXECUTABLE_MODE)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to chmod bootstrap executable: $executable", error)
            }
        }
    }

    private fun restoreSymlinks(target: File, symlinks: List<Pair<String, String>>) {
        symlinks.forEach { (linkTarget, relativePath) ->
            val link = target.resolve(relativePath)
            try {
                link.parentFile?.mkdirs()
                Os.symlink(linkTarget, link.absolutePath)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to restore bootstrap symlink: $linkTarget -> $relativePath", error)
            }
        }
    }

    private fun copyAsset(assetPath: String, destination: File) {
        try {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: FileNotFoundException) {
            throw BootstrapAssetMissingException(assetPath, error)
        }
    }

    companion object {
        private const val TAG = "RuntimeInstaller"
        private const val EXECUTABLE_MODE = 448
        private const val SYMLINK_SEPARATOR = '←'
    }
}

class BootstrapAssetMissingException(
    assetPath: String,
    cause: IOException,
) : IOException("Missing bootstrap asset: $assetPath", cause)
