package io.github.clinal.cordis.data

import android.content.Context
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.RuntimeStatus
import io.github.clinal.cordis.runtime.RuntimePaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class InstanceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val paths = RuntimePaths(appContext)

    private val mutableInstances = MutableStateFlow(loadInstances())
    val instances: StateFlow<List<CordisInstance>> = mutableInstances

    fun addInstance() {
        val nextIndex = nextInstanceIndex()
        val id = instanceId(nextIndex)
        val currentInstances = mutableInstances.value
        val instance = CordisInstance(
            id = id,
            name = "instance $nextIndex",
            port = nextAvailablePort(currentInstances),
            dns = "",
            status = initialStatus(),
            lastLogLines = listOf("Press Start to prepare the Cordis runtime."),
        )

        preferences.edit().putStringSet(KEY_INSTANCE_IDS, currentIds() + id).apply()
        paths.instanceHome(id).mkdirs()
        saveInstanceConfig(instance)
        mutableInstances.update { instances ->
            instances + instance
        }
    }

    fun removeInstance(id: String) {
        preferences.edit().putStringSet(KEY_INSTANCE_IDS, currentIds() - id).apply()
        clearInstanceConfig(id)
        paths.instanceHome(id).deleteRecursively()
        mutableInstances.update { instances ->
            instances.filterNot { it.id == id }
        }
    }

    fun updateInstanceConfig(id: String, name: String, port: Int, dns: String) {
        val sanitizedName = name.trim().ifBlank { defaultName(id) }
        val sanitizedPort = port.coerceIn(MIN_PORT, MAX_PORT)
        val sanitizedDns = dns.trim()
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id == id) {
                    instance.copy(name = sanitizedName, port = sanitizedPort, dns = sanitizedDns)
                        .also(::saveInstanceConfig)
                } else {
                    instance
                }
            }
        }
    }

    fun instance(id: String): CordisInstance? = mutableInstances.value.firstOrNull { it.id == id }

    fun updateStatus(id: String, status: RuntimeStatus, logLine: String? = null) {
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id != id) {
                    instance
                } else {
                    instance.copy(
                        status = status,
                        lastLogLines = appendLog(instance.lastLogLines, logLine),
                    )
                }
            }
        }
    }

    fun appendLog(id: String, line: String) {
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id == id) {
                    instance.copy(lastLogLines = appendLog(instance.lastLogLines, line))
                } else {
                    instance
                }
            }
        }
    }

    private fun initialStatus(): RuntimeStatus {
        val bootstrap = appContext.filesDir.resolve("data/proot-static")
        return if (bootstrap.canExecute()) RuntimeStatus.Stopped else RuntimeStatus.MissingBootstrap
    }

    private fun loadInstances(): List<CordisInstance> {
        val ids = currentIds().sortedWith(compareBy(::instanceSortIndex, { it }))
        return ids.mapIndexed { index, id ->
            paths.instanceHome(id).mkdirs()
            CordisInstance(
                id = id,
                name = preferences.getString(instanceKey(id, KEY_NAME), null) ?: defaultName(id),
                port = preferences.getInt(instanceKey(id, KEY_PORT), DEFAULT_BASE_PORT + index)
                    .coerceIn(MIN_PORT, MAX_PORT),
                dns = preferences.getString(instanceKey(id, KEY_DNS), null).orEmpty(),
                status = initialStatus(),
                lastLogLines = listOf("Press Start to prepare the Cordis runtime."),
            )
        }
    }

    private fun defaultName(id: String): String {
        return "instance ${instanceSortIndex(id)}"
    }

    private fun currentIds(): Set<String> {
        return preferences.getStringSet(KEY_INSTANCE_IDS, emptySet()).orEmpty() - DEFAULT_INSTANCE_ID
    }

    private fun nextInstanceIndex(): Int {
        val used = currentIds().map(::instanceSortIndex).toSet()
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    private fun instanceSortIndex(id: String): Int {
        return id.removePrefix(INSTANCE_ID_PREFIX).toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun instanceId(index: Int): String = "$INSTANCE_ID_PREFIX$index"

    private fun nextAvailablePort(instances: List<CordisInstance>): Int {
        val used = instances.map(CordisInstance::port).toSet()
        return generateSequence(DEFAULT_BASE_PORT) { it + 1 }
            .first { it in MIN_PORT..MAX_PORT && it !in used }
    }

    private fun saveInstanceConfig(instance: CordisInstance) {
        preferences.edit()
            .putString(instanceKey(instance.id, KEY_NAME), instance.name)
            .putInt(instanceKey(instance.id, KEY_PORT), instance.port)
            .putString(instanceKey(instance.id, KEY_DNS), instance.dns)
            .apply()
    }

    private fun clearInstanceConfig(id: String) {
        preferences.edit()
            .remove(instanceKey(id, KEY_NAME))
            .remove(instanceKey(id, KEY_PORT))
            .remove(instanceKey(id, KEY_DNS))
            .apply()
    }

    private fun instanceKey(id: String, key: String): String = "$id.$key"

    private fun appendLog(lines: List<String>, line: String?): List<String> {
        if (line.isNullOrBlank()) return lines
        return (lines + line).takeLast(MAX_LOG_LINES)
    }

    companion object {
        const val DEFAULT_INSTANCE_ID = "default"
        const val DEFAULT_BASE_PORT = 3140
        private const val PREFERENCES_NAME = "cordis_instances"
        private const val KEY_INSTANCE_IDS = "instance_ids"
        private const val KEY_NAME = "name"
        private const val KEY_PORT = "port"
        private const val KEY_DNS = "dns"
        private const val INSTANCE_ID_PREFIX = "instance-"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val MAX_LOG_LINES = 200
    }
}
