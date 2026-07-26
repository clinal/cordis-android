package io.github.clinal.cordis.runtime

import android.content.Context
import android.util.Log
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.domain.RuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class RuntimeSupervisor(
    context: Context,
    private val instanceRepository: InstanceRepository,
) {
    private val appContext = context.applicationContext
    private val installer = RuntimeInstaller(appContext)
    private val commandBuilder = ProotCommandBuilder(RuntimePaths(appContext))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = ConcurrentHashMap<String, Process>()
    private val activeStarts = ConcurrentHashMap.newKeySet<String>()

    fun start(instanceId: String) {
        if (!activeStarts.add(instanceId)) return

        scope.launch {
            try {
                val instance = instanceRepository.instance(instanceId)
                if (instance == null) {
                    instanceRepository.updateStatus(instanceId, RuntimeStatus.Failed, "Instance configuration was not found.")
                    return@launch
                }

                instanceRepository.updateStatus(instanceId, RuntimeStatus.Starting, "Preparing Cordis runtime.")
                installer.prepare(instanceId, instance.port) { line -> instanceRepository.appendLog(instanceId, line) }
                if (!installer.isBootstrapInstalled()) {
                    instanceRepository.updateStatus(
                        instanceId,
                        RuntimeStatus.MissingBootstrap,
                        "Runtime bootstrap is not installed yet.",
                    )
                    return@launch
                }

                instanceRepository.appendLog(instanceId, "Starting Cordis with yarn start.")
                val command = commandBuilder.cordisCommand(instanceId)
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .also { builder -> builder.environment()["PROOT_TMP_DIR"] = RuntimePaths(appContext).tmp.absolutePath }
                    .start()
                processes[instanceId] = process

                instanceRepository.updateStatus(instanceId, RuntimeStatus.Running, "Runtime process started.")
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> instanceRepository.appendLog(instanceId, line) }
                }

                val exitCode = process.waitFor()
                val status = if (exitCode == 0) RuntimeStatus.Stopped else RuntimeStatus.Failed
                instanceRepository.updateStatus(instanceId, status, "Runtime exited with code $exitCode.")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start runtime for instance: $instanceId", error)
                instanceRepository.updateStatus(
                    instanceId,
                    RuntimeStatus.Failed,
                    "Runtime start failed: ${error.message ?: error.javaClass.simpleName}.",
                )
            } finally {
                processes.remove(instanceId)
                activeStarts.remove(instanceId)
            }
        }
    }

    fun stop(instanceId: String) {
        processes.remove(instanceId)?.destroy()
        instanceRepository.updateStatus(instanceId, RuntimeStatus.Stopped, "Stop requested.")
    }

    companion object {
        private const val TAG = "RuntimeSupervisor"
    }
}
