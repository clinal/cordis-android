package io.github.clinal.cordis.data

import android.content.Context
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.RuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class InstanceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val defaultInstance = CordisInstance(
        id = DEFAULT_INSTANCE_ID,
        name = "default",
        port = 5140,
        status = initialStatus(),
        lastLogLines = listOf("Waiting for runtime bootstrap assets."),
    )

    private val mutableInstances = MutableStateFlow(listOf(defaultInstance))
    val instances: StateFlow<List<CordisInstance>> = mutableInstances

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

    private fun appendLog(lines: List<String>, line: String?): List<String> {
        if (line.isNullOrBlank()) return lines
        return (lines + line).takeLast(MAX_LOG_LINES)
    }

    companion object {
        const val DEFAULT_INSTANCE_ID = "default"
        private const val MAX_LOG_LINES = 200
    }
}
