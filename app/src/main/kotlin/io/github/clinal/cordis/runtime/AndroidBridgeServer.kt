package io.github.clinal.cordis.runtime

import android.net.LocalSocket
import android.util.Log
import android.content.Context
import io.github.clinal.cordis.data.ButtonPatch
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.domain.AndroidBridgeStatus
import io.github.clinal.cordis.domain.CordisButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AndroidBridgeServer(
    private val instanceId: String,
    private val controlEnabled: Boolean,
    context: Context,
    private val instanceRepository: InstanceRepository,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val token = UUID.randomUUID().toString()
    private val active = AtomicBoolean(true)
    private val writeMutex = Mutex()
    private val pendingResponses = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private var clientSocket: LocalSocket? = null
    private var writer: BufferedWriter? = null
    private var pollJob: Job? = null
    private var nextRequestId = 0L
    private val controlShell = AndroidControlShell(context.applicationContext)

    val environment: Map<String, String>
        get() = mapOf(
            "CORDIS_ANDROID_SOCKET" to AndroidBridgeListener.SOCKET_NAME,
            "CORDIS_ANDROID_SOCKET_NAMESPACE" to "abstract",
            "CORDIS_ANDROID_INSTANCE_ID" to instanceId,
            "CORDIS_ANDROID_PROTOCOL" to PROTOCOL,
            "CORDIS_ANDROID_TOKEN" to token,
            "CORDIS_ANDROID_CONTROL_ENABLED" to controlEnabled.toString(),
        )

    fun awaitClient() {
        instanceRepository.updateBridgeStatus(instanceId, AndroidBridgeStatus.WaitingForPlugin)
    }

    @Synchronized
    fun stop() {
        active.set(false)
        pollJob?.cancel()
        pollJob = null
        runCatching { clientSocket?.close() }
        clientSocket = null
        writer = null
        pendingResponses.clear()
        instanceRepository.updateBridgeStatus(instanceId, AndroidBridgeStatus.Stopped)
    }

    fun click(buttonId: String) {
        scope.launch {
            val currentWriter = writer
            if (currentWriter == null) {
                instanceRepository.appendLog(
                    instanceId,
                    "Android bridge is not connected; cannot trigger button $buttonId.",
                )
                return@launch
            }
            val sent = write(
                currentWriter,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "button.click")
                    .put("params", JSONObject().put("id", buttonId)),
            )
            if (!sent) {
                instanceRepository.appendLog(instanceId, "Failed to send Android button $buttonId to the Cordis plugin.")
            }
        }
    }

    @Synchronized
    fun attachClient(socket: LocalSocket, reader: BufferedReader, hello: JSONObject): Boolean {
        if (!accepts(hello)) return false
        runCatching { clientSocket?.close() }
        clientSocket = socket
        writer = socket.outputStream.bufferedWriter()
        pollJob?.cancel()
        handleRequest(hello)
        scope.launch {
            try {
                reader.use(::readMessages)
            } catch (error: IOException) {
                Log.d(TAG, "Android bridge client socket closed for instance: $instanceId")
            }
        }.invokeOnCompletion {
            if (clientSocket == socket) {
                clientSocket = null
                writer = null
                pendingResponses.clear()
                pollJob?.cancel()
                pollJob = null
                instanceRepository.updateBridgeStatus(instanceId, AndroidBridgeStatus.WaitingForPlugin)
            }
        }
        return true
    }

    private fun accepts(hello: JSONObject): Boolean {
        val params = hello.optJSONObject("params") ?: return false
        return active.get() &&
            hello.optString("method") == "hello" &&
            params.optString("protocol") == PROTOCOL &&
            params.optString("token") == token &&
            params.optString("instanceId") == instanceId
    }

    private fun readMessages(reader: BufferedReader) {
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            val id = message.rpcId()
            when {
                id != null && (message.has("result") || message.has("error")) -> {
                    pendingResponses.remove(id.toString())?.invoke(message)
                }
                message.optString("method").isNotBlank() -> {
                    handleRequest(message)
                }
            }
        }
    }

    private fun handleRequest(message: JSONObject) {
        val id = message.rpcId()
        val params = message.optJSONObject("params") ?: JSONObject()
        when (message.optString("method")) {
            "hello" -> {
                val accepted = accepts(message)
                if (accepted) {
                    instanceRepository.updateBridgeStatus(instanceId, AndroidBridgeStatus.Connected)
                    startButtonPolling()
                    reply(id, JSONObject().put("protocol", PROTOCOL).put("host", "cordis-android"))
                } else {
                    replyError(id, 1001, "unauthorized")
                    runCatching { clientSocket?.close() }
                }
            }
            "button.register" -> {
                (params.optJSONObject("button") ?: params).toCordisButtonOrNull()?.let { button ->
                    instanceRepository.registerBridgeButton(instanceId, button)
                }
                reply(id, JSONObject())
            }
            "button.patch" -> {
                val buttonId = params.optString("id")
                if (buttonId.isNotBlank()) {
                    val patchParams = params.optJSONObject("patch") ?: params
                    instanceRepository.patchBridgeButton(instanceId, buttonId, patchParams.toButtonPatch())
                }
                reply(id, JSONObject())
            }
            "button.unregister" -> {
                params.optString("id").takeIf(String::isNotBlank)?.let { buttonId ->
                    instanceRepository.unregisterBridgeButton(instanceId, buttonId)
                }
                reply(id, JSONObject())
            }
            "control.execute" -> {
                if (!controlEnabled) {
                    replyError(id, 1002, "Android control is not enabled for this Cordis instance")
                } else {
                    val command = params.optString("command")
                    if (command.isBlank()) {
                        replyError(id, -32602, "missing command")
                    } else {
                        scope.launch {
                            runCatching { executeControlCommand(command) }
                                .onSuccess { reply(id, it) }
                                .onFailure { error ->
                                    val message = error.message ?: "Android control failed with an unknown error."
                                    Log.e(TAG, "Android control request failed for instance $instanceId: $message", error)
                                    instanceRepository.appendLog(instanceId, "Android control failed: $message")
                                    replyError(id, 1003, message)
                                }
                        }
                    }
                }
            }
            else -> replyError(id, -32601, "method not found")
        }
    }

    private fun executeControlCommand(command: String): JSONObject {
        return controlShell.execute(command)
    }

    private fun startButtonPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                refreshButtons()
                delay(BUTTON_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshButtons() {
        val response = request("buttons", JSONObject()).getOrNull() ?: return
        val result = response.opt("result")
        val array = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("buttons")
            else -> null
        } ?: return
        val buttons = List(array.length()) { index -> array.getJSONObject(index).toCordisButton() }
        instanceRepository.replaceBridgeButtons(instanceId, buttons)
    }

    private suspend fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): Result<JSONObject> {
        val currentWriter = writer ?: return Result.failure(
            IOException("Cordis plugin socket is not connected (bridge status: ${instanceRepository.instance(instanceId)?.bridgeStatus})."),
        )
        val id = (++nextRequestId).toString()
        var response: JSONObject? = null
        val waitLock = Object()
        pendingResponses[id] = { message ->
            synchronized(waitLock) {
                response = message
                waitLock.notifyAll()
            }
        }
        val sent = write(
            currentWriter,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params),
        )
        if (!sent) {
            pendingResponses.remove(id)
            return Result.failure(IOException("Failed to write $method to the Cordis plugin socket."))
        }
        synchronized(waitLock) {
            if (response == null) waitLock.wait(timeoutMs)
        }
        pendingResponses.remove(id)
        return response?.let { Result.success(it) } ?: Result.failure(
            SocketTimeoutException("Cordis plugin did not respond to $method within ${timeoutMs / 1_000} seconds."),
        )
    }

    private fun reply(id: Any?, result: JSONObject) {
        if (id == null) return
        scope.launch {
            writer?.let { currentWriter ->
                write(currentWriter, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
            }
        }
    }

    private fun replyError(id: Any?, code: Int, message: String) {
        if (id == null) return
        scope.launch {
            writer?.let { currentWriter ->
                write(
                    currentWriter,
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("error", JSONObject().put("code", code).put("message", message)),
                )
            }
        }
    }

    private suspend fun write(currentWriter: BufferedWriter, message: JSONObject): Boolean {
        return try {
            writeMutex.withLock {
                currentWriter.write(message.toString())
                currentWriter.newLine()
                currentWriter.flush()
            }
            true
        } catch (error: IOException) {
            Log.d(TAG, "Android bridge write failed for instance: $instanceId", error)
            if (writer == currentWriter) {
                runCatching { clientSocket?.close() }
            }
            false
        }
    }

    private fun JSONObject.toCordisButton(): CordisButton {
        return CordisButton(
            id = getString("id"),
            label = optString("label").ifBlank { getString("id") },
            icon = optString("icon").takeIf(String::isNotBlank),
            description = optString("description").takeIf(String::isNotBlank),
            enabled = if (has("enabled")) optBoolean("enabled") else true,
            disabledReason = optString("disabledReason").takeIf(String::isNotBlank),
        )
    }

    private fun JSONObject.rpcId(): Any? = opt("id")?.takeUnless { it == JSONObject.NULL }

    private fun JSONObject.toCordisButtonOrNull(): CordisButton? {
        return runCatching { toCordisButton() }.getOrNull()
    }

    private fun JSONObject.toButtonPatch(): ButtonPatch {
        return ButtonPatch(
            label = optString("label").takeIf { has("label") },
            icon = optString("icon").takeIf { has("icon") },
            description = optString("description").takeIf { has("description") },
            enabled = optBoolean("enabled").takeIf { has("enabled") },
            disabledReason = optString("disabledReason").takeIf { has("disabledReason") },
        )
    }

    companion object {
        private const val TAG = "AndroidBridgeServer"
        private const val PROTOCOL = "cordis.android.bridge.v1"
        private const val BUTTON_POLL_INTERVAL_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 2_000L
    }
}
