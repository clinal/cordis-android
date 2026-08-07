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
    private val startGate = RuntimeStartGate()
    private val stoppingInstances = ConcurrentHashMap.newKeySet<String>()
    private val deletingInstances = ConcurrentHashMap.newKeySet<String>()
    private val startJobs = ConcurrentHashMap<String, Job>()
    private val bridgeServers = ConcurrentHashMap<String, AndroidBridgeServer>()

    fun restoreAutoStartedInstances() {
        instanceRepository.autoStartInstanceIds().forEach(::start)
    }

    fun start(instanceId: String) {
        if (deletingInstances.contains(instanceId)) return
        if (!startGate.request(instanceId)) return
        launchStart(instanceId)
    }

    private fun launchStart(instanceId: String) {
        if (deletingInstances.contains(instanceId)) {
            startGate.release(instanceId)
            return
        }
        instanceRepository.setAutoStart(instanceId, true)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val instance = instanceRepository.instance(instanceId)
                if (instance == null) {
                    instanceRepository.setAutoStart(instanceId, false)
                    instanceRepository.updateStatus(instanceId, RuntimeStatus.Failed, "Instance configuration was not found.")
                    return@launch
                }

                instanceRepository.updateStatus(instanceId, RuntimeStatus.Starting, "Preparing Cordis runtime.")
                installer.prepare(instanceId, instance.port, instance.patchPort) { line ->
                    instanceRepository.appendLog(instanceId, line)
                }
                if (deletingInstances.contains(instanceId)) {
                    return@launch
                }
                if (!installer.isBootstrapInstalled()) {
                    instanceRepository.setAutoStart(instanceId, false)
                    instanceRepository.updateStatus(
                        instanceId,
                        RuntimeStatus.Failed,
                        "Runtime bootstrap is not installed yet.",
                    )
                    return@launch
                }
                val dns = instance.dns.ifBlank { InstanceRepository.DEFAULT_DNS }

                if (instance.androidControlEnabled) {
                    instanceRepository.appendLog(instanceId, "Checking Shizuku Android control service.")
                    val shell = AndroidControlShell(appContext)
                    try {
                        shell.execute("true")
                    } finally {
                        shell.close()
                    }
                    instanceRepository.appendLog(instanceId, "Shizuku Android control service is ready.")
                }

                instanceRepository.appendLog(instanceId, "Starting Cordis with ${instance.startCommand}.")
                val bridgeServer = AndroidBridgeServer(
                    instanceId = instanceId,
                    controlEnabled = instance.androidControlEnabled,
                    context = appContext,
                    instanceRepository = instanceRepository,
                    parentScope = scope,
                ).also { bridge ->
                    bridgeServers[instanceId] = bridge
                    bridge.start()
                }
                val command = commandBuilder.cordisCommand(
                    instanceId = instanceId,
                    startCommand = instance.startCommand,
                    environment = bridgeServer.environment,
                )
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .also { builder ->
                        builder.environment()["PROOT_TMP_DIR"] = RuntimePaths(appContext).tmp.absolutePath
                        builder.environment()["CORDIS_DNS"] = dns
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
                instanceRepository.setAutoStart(instanceId, false)
                val logLine = if (stoppedByRequest) {
                    "Runtime stopped."
                } else {
                    "Runtime exited with code $exitCode."
                }
                instanceRepository.updateStatus(instanceId, status, logLine)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start runtime for instance: $instanceId", error)
                instanceRepository.setAutoStart(instanceId, false)
                instanceRepository.updateStatus(
                    instanceId,
                    RuntimeStatus.Failed,
                    "Runtime start failed: ${error.message ?: error.javaClass.simpleName}.",
                )
            } finally {
                processes.remove(instanceId)
                prootPids.remove(instanceId)
                bridgeServers.remove(instanceId)?.stop()
                stoppingInstances.remove(instanceId)
                startJobs.remove(instanceId)
                if (startGate.finish(instanceId)) {
                    launchStart(instanceId)
                }
            }
        }
        startJobs[instanceId] = job
        job.invokeOnCompletion { startJobs.remove(instanceId, job) }
        job.start()
    }

    fun stop(instanceId: String) {
        startGate.cancelPending(instanceId)
        instanceRepository.setAutoStart(instanceId, false)
        scope.launch {
            stopProcess(instanceId)
        }
    }

    fun clickBridgeButton(instanceId: String, buttonId: String) {
        bridgeServers[instanceId]?.click(buttonId)
            ?: instanceRepository.appendLog(instanceId, "Android bridge is not connected; cannot trigger button $buttonId.")
    }

    fun remove(instanceId: String) {
        if (!deletingInstances.add(instanceId)) return
        startGate.cancelPending(instanceId)
        instanceRepository.setAutoStart(instanceId, false)
        scope.launch {
            try {
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
