package io.github.clinal.cordis.runtime

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AndroidBridgeServer(
    private val instanceId: String,
    private val instanceRepository: InstanceRepository,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val socketName = "cordis-android-$instanceId"
    private val token = UUID.randomUUID().toString()
    private val writeMutex = Mutex()
    private val pendingResponses = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var writer: BufferedWriter? = null
    private var acceptJob: Job? = null
    private var pollJob: Job? = null
    private var nextRequestId = 0L

    val environment: Map<String, String>
        get() = mapOf(
            "CORDIS_ANDROID_SOCKET" to socketName,
            "CORDIS_ANDROID_SOCKET_NAMESPACE" to "abstract",
            "CORDIS_ANDROID_INSTANCE_ID" to instanceId,
            "CORDIS_ANDROID_PROTOCOL" to PROTOCOL,
            "CORDIS_ANDROID_TOKEN" to token,
        )

    fun start() {
        stop()
        instanceRepository.updateBridgeStatus(instanceId, AndroidBridgeStatus.WaitingForPlugin)
        acceptJob = scope.launch {
            try {
                serverSocket = LocalServerSocket(socketName)
                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    attachClient(socket)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Bridge server failed for instance: $instanceId", error)
                instanceRepository.updateBridgeStatus(instanceId, AndroidBridgeStatus.Stopped)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        acceptJob?.cancel()
        acceptJob = null
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
        writer = null
        pendingResponses.clear()
        instanceRepository.updateBridgeStatus(instanceId, AndroidBridgeStatus.Stopped)
    }

    fun click(buttonId: String) {
        scope.launch {
            val sent = request("button.click", JSONObject().put("id", buttonId)) != null
            if (!sent) {
                instanceRepository.appendLog(instanceId, "Android bridge is not connected; cannot trigger button $buttonId.")
            }
        }
    }

    private fun attachClient(socket: LocalSocket) {
        runCatching { clientSocket?.close() }
        clientSocket = socket
        writer = socket.outputStream.bufferedWriter()
        pollJob?.cancel()
        scope.launch {
            try {
                socket.inputStream.bufferedReader().use { reader ->
                    readMessages(reader)
                }
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
    }

    private fun readMessages(reader: BufferedReader) {
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            val id = message.optString("id").takeIf(String::isNotBlank)
            when {
                id != null && (message.has("result") || message.has("error")) -> {
                    pendingResponses.remove(id)?.invoke(message)
                }
                message.optString("method").isNotBlank() -> {
                    handleRequest(message)
                }
            }
        }
    }

    private fun handleRequest(message: JSONObject) {
        val id = message.optString("id").takeIf(String::isNotBlank)
        val params = message.optJSONObject("params") ?: JSONObject()
        when (message.optString("method")) {
            "hello" -> {
                val accepted = params.optString("protocol") == PROTOCOL && params.optString("token") == token
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
            else -> replyError(id, -32601, "method not found")
        }
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
        val response = request("buttons", JSONObject()) ?: return
        val result = response.opt("result")
        val array = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("buttons")
            else -> null
        } ?: return
        val buttons = List(array.length()) { index -> array.getJSONObject(index).toCordisButton() }
        instanceRepository.replaceBridgeButtons(instanceId, buttons)
    }

    private suspend fun request(method: String, params: JSONObject): JSONObject? {
        val currentWriter = writer ?: return null
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
            return null
        }
        synchronized(waitLock) {
            if (response == null) waitLock.wait(REQUEST_TIMEOUT_MS)
        }
        pendingResponses.remove(id)
        return response
    }

    private fun reply(id: String?, result: JSONObject) {
        if (id == null) return
        scope.launch {
            writer?.let { currentWriter ->
                write(currentWriter, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
            }
        }
    }

    private fun replyError(id: String?, code: Int, message: String) {
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
