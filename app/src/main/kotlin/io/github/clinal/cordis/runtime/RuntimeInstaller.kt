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

    fun prepare(instanceId: String, onProgress: (String) -> Unit = {}) {
        installBootstrap(onProgress)
        paths.home.resolve("instances").mkdirs()
        paths.instanceHome(instanceId).mkdirs()

        val instanceHome = paths.instanceHome(instanceId)
        seedInstanceTemplate(instanceHome, onProgress)
        ensureForegroundCordisConfig(instanceHome, onProgress)
        seedDefaultAppConfig(instanceHome, onProgress)
    }

    fun isBootstrapInstalled(): Boolean = paths.proot.canExecute() && paths.envFile.exists()

    private fun installBootstrap(onProgress: (String) -> Unit) {
        paths.filesDir.mkdirs()

        try {
            if (!paths.proot.canExecute()) {
                onProgress("Installing runtime bootstrap.")
                unpackBootstrap(onProgress)
                onProgress("Runtime bootstrap installed.")
            }

            if (!paths.envFile.exists()) {
                onProgress("Writing bootstrap environment.")
                copyAsset("bootstrap/env.txt", paths.envFile)
            }

            paths.tmp.mkdirs()
            paths.shm.mkdirs()
        } catch (error: BootstrapAssetMissingException) {
            Log.i(TAG, "Bootstrap assets are not packaged in this build.", error)
        }
    }

    private fun unpackBootstrap(onProgress: (String) -> Unit) {
        if (paths.root.exists()) {
            onProgress("Repairing existing bootstrap directory.")
            unpackZip(
                assetPath = "bootstrap/bootstrap.zip",
                target = paths.root,
                restoreMetadata = true,
                onProgress = onProgress,
            )
            return
        }

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
                onProgress = onProgress,
            )
            if (!paths.root.exists() && !staging.renameTo(paths.root)) {
                error("Cannot move bootstrap staging directory into place.")
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun seedInstanceTemplate(instanceHome: File, onProgress: (String) -> Unit) {
        val marker = instanceHome.resolve(".cordis-android-template")
        if (marker.exists()) return

        onProgress("Seeding Cordis project template.")
        if (extractBundledBoilerplate(instanceHome)) {
            marker.writeText("${BoilerplateRelease.Version}\n")
            onProgress("Cordis project template installed.")
            return
        }

        seedDefaultConfig(instanceHome)
        marker.writeText("minimal\n")
        onProgress("Minimal Cordis config installed.")
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

    private fun ensureForegroundCordisConfig(instanceHome: File, onProgress: (String) -> Unit) {
        val config = instanceHome.resolve("cordis.yml")
        if (!config.exists()) return

        val content = config.readText()
        val foreground = content.replace(
            oldValue = "daemon:\n      enabled: true",
            newValue = "daemon:\n      enabled: false",
        )
        if (foreground != content) {
            config.writeText(foreground)
            onProgress("Configured Cordis to run in the foreground.")
        }
    }

    private fun seedDefaultAppConfig(instanceHome: File, onProgress: (String) -> Unit) {
        val config = instanceHome.resolve("app.yml")
        if (!config.exists()) {
            onProgress("Writing default Cordis app config.")
            copyAsset("bootstrap/default-app.yml", config)
        }
    }

    private fun unpackZip(
        assetPath: String,
        target: File,
        restoreMetadata: Boolean,
        onProgress: (String) -> Unit = {},
    ) {
        val executables = mutableListOf<String>()
        val symlinks = mutableListOf<Pair<String, String>>()
        var extractedEntries = 0

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
                    extractedEntries += 1
                    if (extractedEntries == 1 || extractedEntries % PROGRESS_INTERVAL == 0) {
                        onProgress("Extracted $extractedEntries bootstrap files.")
                    }
                    zip.closeEntry()
                }
            }
        }

        if (restoreMetadata) {
            onProgress("Restoring bootstrap executable metadata.")
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
        private const val PROGRESS_INTERVAL = 500
        private const val SYMLINK_SEPARATOR = '←'
    }
}

class BootstrapAssetMissingException(
    assetPath: String,
    cause: IOException,
) : IOException("Missing bootstrap asset: $assetPath", cause)
