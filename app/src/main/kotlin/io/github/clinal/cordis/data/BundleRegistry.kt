package io.github.clinal.cordis.data

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class RegistryBundle(
    val name: String,
    val description: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val id: String get() = "$name@$version"
}

class BundleRegistry(context: Context) {
    private val bundleDir = context.filesDir.resolve("bundles")
    private val registryCache = bundleDir.resolve("registry.json")

    fun load(): List<RegistryBundle> = if (registryCache.isFile) {
        parse(registryCache.readText())
    } else {
        fetch()
    }

    fun fetch(): List<RegistryBundle> {
        return try {
            val connection = URL(REGISTRY_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github.raw+json")
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val result = parse(json)
            bundleDir.mkdirs()
            registryCache.writeText(json)
            result
        } catch (error: Throwable) {
            if (!registryCache.isFile) throw error
            parse(registryCache.readText())
        }
    }

    fun downloaded(bundles: List<RegistryBundle>): Set<String> = bundles
        .filter { archive(it).isFile }
        .map(RegistryBundle::id)
        .toSet()

    fun archive(bundle: RegistryBundle): File = bundleDir.resolve("${bundle.safeId()}$ARCHIVE_SUFFIX")

    fun download(bundle: RegistryBundle, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        bundleDir.mkdirs()
        val target = archive(bundle)
        val temporary = bundleDir.resolve(".${bundle.safeId()}.download")
        temporary.delete()
        try {
            val connection = URL(bundle.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, total)
                    }
                }
            }
            check(temporary.sha256().equals(bundle.sha256.removePrefix("sha256:"), ignoreCase = true)) {
                "Downloaded bundle checksum does not match the registry."
            }
            target.delete()
            check(temporary.renameTo(target)) { "Cannot save downloaded bundle." }
        } finally {
            temporary.delete()
        }
    }

    fun delete(bundle: RegistryBundle) {
        val archive = archive(bundle)
        check(!archive.exists() || archive.delete()) { "Cannot delete ${bundle.id}." }
    }

    internal fun parse(json: String): List<RegistryBundle> {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "Unsupported registry schema." }
        val architecture = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "amd64"
            else -> return emptyList()
        }
        val result = mutableListOf<RegistryBundle>()
        val bundles = root.getJSONArray("bundles")
        for (bundleIndex in 0 until bundles.length()) {
            val bundle = bundles.getJSONObject(bundleIndex)
            val versions = bundle.getJSONArray("versions")
            for (versionIndex in 0 until versions.length()) {
                val version = versions.getJSONObject(versionIndex)
                val artifacts = version.getJSONArray("artifacts")
                for (artifactIndex in 0 until artifacts.length()) {
                    val artifact = artifacts.getJSONObject(artifactIndex)
                    if (artifact.getString("platform") == "linux" && artifact.getString("architecture") == architecture) {
                        result += RegistryBundle(
                            name = bundle.getString("name"),
                            description = bundle.optString("description"),
                            version = version.getString("version"),
                            url = artifact.getString("url"),
                            sha256 = artifact.getString("hash"),
                        )
                    }
                }
            }
        }
        return result
    }

    private fun RegistryBundle.safeId() = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val REGISTRY_URL = "https://raw.githubusercontent.com/clinal/registry/registry/registry.json"
        private const val ARCHIVE_SUFFIX = ".zip"
    }
}
