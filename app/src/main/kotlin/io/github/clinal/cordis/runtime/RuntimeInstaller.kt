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

    fun prepare(instanceId: String, port: Int, onProgress: (String) -> Unit = {}) {
        prepareBootstrap(onProgress)
        paths.home.resolve("instances").mkdirs()
        paths.instanceHome(instanceId).mkdirs()

        val instanceHome = paths.instanceHome(instanceId)
        seedInstanceTemplate(instanceHome, onProgress)
        ensureForegroundCordisConfig(instanceHome, onProgress)
        ensureAppPortConfig(instanceHome, port, onProgress)
    }

    fun prepareBootstrap(onProgress: (String) -> Unit = {}) {
        synchronized(bootstrapInstallLock) {
            installBootstrap(onProgress)
        }
    }

    fun isBootstrapInstalled(): Boolean = paths.proot.canExecute() && paths.envFile.exists()

    private fun installBootstrap(onProgress: (String) -> Unit) {
        paths.filesDir.mkdirs()

        try {
            val bundledEnv = readAssetText("bootstrap/env.txt")
            val installedEnv = paths.envFile.takeIf(File::exists)?.readText()?.trim()

            if (!paths.proot.canExecute() || installedEnv != bundledEnv.trim()) {
                val action = if (paths.proot.canExecute()) "Updating" else "Installing"
                onProgress("$action runtime bootstrap.")
                unpackBootstrap(onProgress)
                onProgress("Runtime bootstrap ${if (action == "Updating") "updated" else "installed"}.")
            }

            if (!paths.envFile.exists() || installedEnv != bundledEnv.trim()) {
                onProgress("Writing bootstrap environment.")
                writeText(paths.envFile, bundledEnv)
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

        onProgress("Cordis boilerplate asset is not packaged in this build.")
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

    private fun ensureAppPortConfig(instanceHome: File, port: Int, onProgress: (String) -> Unit) {
        val config = instanceHome.resolve("app.yml")
        if (!config.exists()) return

        val original = config.readLines()
        val updated = mutableListOf<String>()
        var inServerPlugin = false
        var replacedPort = false

        original.forEach { line ->
            val trimmed = line.trim()
            if (line.startsWith("- ")) {
                inServerPlugin = false
            }

            if (line.topLevelPluginName() != null) {
                val pluginName = line.topLevelPluginName()
                inServerPlugin = pluginName == "@cordisjs/plugin-server"
                updated += line
                return@forEach
            }

            if (inServerPlugin && trimmed.startsWith("port:")) {
                val indent = line.takeWhile(Char::isWhitespace)
                updated += "${indent}port: $port"
                replacedPort = true
            } else {
                updated += line
            }
        }

        val updatedText = updated.joinToString(separator = "\n", postfix = "\n")
        if (updatedText != config.readText()) {
            config.writeText(updatedText)
            onProgress("Configured Cordis app port $port.")
        } else if (!replacedPort) {
            onProgress("Cordis app port was not found in app.yml.")
        }
    }

    private fun String.topLevelPluginName(): String? {
        val indent = takeWhile(Char::isWhitespace).length
        val trimmed = trim()
        val nameValue = when {
            indent == 2 && trimmed.startsWith("name:") -> trimmed.removePrefix("name:")
            indent == 0 && trimmed.startsWith("- name:") -> trimmed.removePrefix("- name:")
            else -> return null
        }
        return nameValue.trim().trim('\'', '"')
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

    private fun readAssetText(assetPath: String): String {
        return try {
            appContext.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (error: FileNotFoundException) {
            throw BootstrapAssetMissingException(assetPath, error)
        }
    }

    private fun writeText(destination: File, content: String) {
        destination.parentFile?.mkdirs()
        destination.writeText(content)
    }

    companion object {
        private val bootstrapInstallLock = Any()
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
