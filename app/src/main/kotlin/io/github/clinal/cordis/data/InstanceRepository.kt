package io.github.clinal.cordis.data

import android.content.Context
import io.github.clinal.cordis.domain.AppSettings
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.RuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class InstanceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val mutableSettings = MutableStateFlow(AppSettings(basePort = loadBasePort()))
    val settings: StateFlow<AppSettings> = mutableSettings

    private val mutableInstances = MutableStateFlow(loadInstances())
    val instances: StateFlow<List<CordisInstance>> = mutableInstances

    fun addInstance() {
        val nextIndex = nextInstanceIndex()
        preferences.edit().putStringSet(KEY_INSTANCE_IDS, currentIds() + instanceId(nextIndex)).apply()
        mutableInstances.update { instances ->
            instances + CordisInstance(
                id = instanceId(nextIndex),
                name = "instance $nextIndex",
                port = mutableSettings.value.basePort + instances.size,
                status = initialStatus(),
                lastLogLines = listOf("Press Start to prepare the Cordis runtime."),
            )
        }
    }

    fun removeInstance(id: String) {
        if (id == DEFAULT_INSTANCE_ID) return

        preferences.edit().putStringSet(KEY_INSTANCE_IDS, currentIds() - id).apply()
        mutableInstances.update { instances ->
            instances
                .filterNot { it.id == id }
                .mapIndexed { index, instance -> instance.copy(port = mutableSettings.value.basePort + index) }
        }
    }

    fun updateBasePort(port: Int) {
        val sanitizedPort = port.coerceIn(MIN_PORT, maxBasePort(mutableInstances.value.size))
        preferences.edit().putInt(KEY_BASE_PORT, sanitizedPort).apply()
        mutableSettings.value = AppSettings(basePort = sanitizedPort)
        mutableInstances.update { instances ->
            instances.mapIndexed { index, instance -> instance.copy(port = sanitizedPort + index) }
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
        val basePort = mutableSettings.value.basePort
        return ids.mapIndexed { index, id ->
            CordisInstance(
                id = id,
                name = if (id == DEFAULT_INSTANCE_ID) "default" else "instance ${instanceSortIndex(id)}",
                port = basePort + index,
                status = initialStatus(),
                lastLogLines = listOf("Press Start to prepare the Cordis runtime."),
            )
        }
    }

    private fun loadBasePort(): Int {
        return preferences.getInt(KEY_BASE_PORT, DEFAULT_BASE_PORT).coerceIn(MIN_PORT, MAX_PORT)
    }

    private fun maxBasePort(instanceCount: Int): Int = MAX_PORT - (instanceCount - 1).coerceAtLeast(0)

    private fun currentIds(): Set<String> {
        return preferences.getStringSet(KEY_INSTANCE_IDS, null)
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(DEFAULT_INSTANCE_ID)
    }

    private fun nextInstanceIndex(): Int {
        val used = currentIds().map(::instanceSortIndex).toSet()
        return generateSequence(2) { it + 1 }.first { it !in used }
    }

    private fun instanceSortIndex(id: String): Int {
        return when (id) {
            DEFAULT_INSTANCE_ID -> 1
            else -> id.removePrefix(INSTANCE_ID_PREFIX).toIntOrNull() ?: Int.MAX_VALUE
        }
    }

    private fun instanceId(index: Int): String = "$INSTANCE_ID_PREFIX$index"

    private fun appendLog(lines: List<String>, line: String?): List<String> {
        if (line.isNullOrBlank()) return lines
        return (lines + line).takeLast(MAX_LOG_LINES)
    }

    companion object {
        const val DEFAULT_INSTANCE_ID = "default"
        const val DEFAULT_BASE_PORT = 3140
        private const val PREFERENCES_NAME = "cordis_instances"
        private const val KEY_BASE_PORT = "base_port"
        private const val KEY_INSTANCE_IDS = "instance_ids"
        private const val INSTANCE_ID_PREFIX = "instance-"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val MAX_LOG_LINES = 200
    }
}
