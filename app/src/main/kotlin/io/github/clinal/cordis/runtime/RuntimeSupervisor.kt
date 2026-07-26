package io.github.clinal.cordis.runtime

import android.content.Context
import android.os.Process.SIGNAL_KILL
import android.os.Process.sendSignal
import android.util.Log
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.domain.RuntimeStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RuntimeSupervisor(
    context: Context,
    private val instanceRepository: InstanceRepository,
) {
    private val appContext = context.applicationContext
    private val installer = RuntimeInstaller(appContext)
    private val commandBuilder = ProotCommandBuilder(RuntimePaths(appContext))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = ConcurrentHashMap<String, Process>()
    private val prootPids = ConcurrentHashMap<String, Int>()
    private val activeStarts = ConcurrentHashMap.newKeySet<String>()
    private val stoppingInstances = ConcurrentHashMap.newKeySet<String>()
    private val deletingInstances = ConcurrentHashMap.newKeySet<String>()
    private val startJobs = ConcurrentHashMap<String, Job>()

    fun prepare(instanceId: String) {
        if (deletingInstances.contains(instanceId)) return
        scope.launch {
            val instance = instanceRepository.instance(instanceId)
            if (instance == null) {
                instanceRepository.updateStatus(instanceId, RuntimeStatus.Failed, "Instance configuration was not found.")
                return@launch
            }

            try {
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
                instanceRepository.updateStatus(instanceId, RuntimeStatus.Stopped, "Runtime prepared.")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to prepare runtime for instance: $instanceId", error)
                instanceRepository.updateStatus(
                    instanceId,
                    RuntimeStatus.Failed,
                    "Runtime prepare failed: ${error.message ?: error.javaClass.simpleName}.",
                )
            }
        }
    }

    fun start(instanceId: String) {
        if (deletingInstances.contains(instanceId)) return
        if (!activeStarts.add(instanceId)) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val instance = instanceRepository.instance(instanceId)
                if (instance == null) {
                    instanceRepository.updateStatus(instanceId, RuntimeStatus.Failed, "Instance configuration was not found.")
                    return@launch
                }

                instanceRepository.updateStatus(instanceId, RuntimeStatus.Starting, "Preparing Cordis runtime.")
                installer.prepare(instanceId, instance.port) { line -> instanceRepository.appendLog(instanceId, line) }
                if (deletingInstances.contains(instanceId)) {
                    return@launch
                }
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
                    .also { builder ->
                        builder.environment()["PROOT_TMP_DIR"] = RuntimePaths(appContext).tmp.absolutePath
                        if (instance.dns.isNotBlank()) {
                            builder.environment()["CORDIS_DNS"] = instance.dns
                        }
                    }
                    .start()
                processes[instanceId] = process

                instanceRepository.updateStatus(instanceId, RuntimeStatus.Running, "Runtime process started.")
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val prootPid = line.toProotPid()
                        when {
                            prootPid != null -> prootPids[instanceId] = prootPid
                            line.toProcessStatus() != null -> Unit
                            else -> instanceRepository.appendLog(instanceId, line)
                        }
                    }
                }

                val exitCode = process.waitFor()
                val stoppedByRequest = stoppingInstances.remove(instanceId)
                val status = if (stoppedByRequest || exitCode == 0) RuntimeStatus.Stopped else RuntimeStatus.Failed
                val logLine = if (stoppedByRequest) {
                    "Runtime stopped."
                } else {
                    "Runtime exited with code $exitCode."
                }
                instanceRepository.updateStatus(instanceId, status, logLine)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start runtime for instance: $instanceId", error)
                instanceRepository.updateStatus(
                    instanceId,
                    RuntimeStatus.Failed,
                    "Runtime start failed: ${error.message ?: error.javaClass.simpleName}.",
                )
            } finally {
                processes.remove(instanceId)
                prootPids.remove(instanceId)
                stoppingInstances.remove(instanceId)
                activeStarts.remove(instanceId)
                startJobs.remove(instanceId)
            }
        }
        startJobs[instanceId] = job
        job.invokeOnCompletion { startJobs.remove(instanceId, job) }
        job.start()
    }

    fun stop(instanceId: String) {
        scope.launch {
            stopProcess(instanceId)
        }
    }

    fun remove(instanceId: String) {
        scope.launch {
            try {
                deletingInstances.add(instanceId)
                stopProcess(instanceId)
                startJobs[instanceId]?.join()
                instanceRepository.removeInstance(instanceId)
            } finally {
                deletingInstances.remove(instanceId)
            }
        }
    }

    private fun stopProcess(instanceId: String) {
        val process = processes[instanceId]
        if (process == null) {
            instanceRepository.updateStatus(instanceId, RuntimeStatus.Stopped, "Stop requested.")
            return
        }

        stoppingInstances.add(instanceId)
        instanceRepository.updateStatus(instanceId, RuntimeStatus.Stopping, "Stop requested.")

        val pid = prootPids[instanceId]
        val signaled = pid != null && sendProcessGroupInterrupt(instanceId, pid)
        if (!signaled) {
            val wrapperPid = process.pidCompat()
            if (wrapperPid != null) {
                sendSignal(wrapperPid, SIGNAL_KILL)
            } else {
                process.destroyForcibly()
            }
        }

        if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            instanceRepository.appendLog(instanceId, "Forced runtime process to exit.")
        }
    }

    private fun sendProcessGroupInterrupt(instanceId: String, pid: Int): Boolean {
        return try {
            val command = """
                ps -o pid,pgid | awk '{ if (${'$'}1 == "$pid") print "-" ${'$'}2 }' | xargs kill -SIGINT
            """.trimIndent()
            val process = ProcessBuilder(commandBuilder.shellCommand(instanceId, command))
                .redirectErrorStream(true)
                .also { builder -> builder.environment()["PROOT_TMP_DIR"] = RuntimePaths(appContext).tmp.absolutePath }
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode != 0 && output.isNotBlank()) {
                instanceRepository.appendLog(instanceId, "Stop signal failed: $output")
            }
            exitCode == 0
        } catch (error: Exception) {
            Log.e(TAG, "Failed to stop runtime process group for instance: $instanceId", error)
            false
        }
    }

    private fun String.toProotPid(): Int? {
        return PROOT_PID_REGEX.matchEntire(this)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String.toProcessStatus(): Int? {
        return PROCESS_STATUS_REGEX.matchEntire(this)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun Process.pidCompat(): Int? {
        return try {
            val field = this::class.java.getDeclaredField("pid")
            field.isAccessible = true
            field.get(this) as Int
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "RuntimeSupervisor"
        private const val STOP_TIMEOUT_SECONDS = 10L
        private val PROOT_PID_REGEX = Regex("^__PID__: (\\d+)$")
        private val PROCESS_STATUS_REGEX = Regex("^__STATUS__: (-?\\d+)$")
    }
}
