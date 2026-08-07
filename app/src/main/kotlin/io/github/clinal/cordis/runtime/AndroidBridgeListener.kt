package io.github.clinal.cordis.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import io.github.clinal.cordis.data.InstanceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.ConcurrentHashMap

class AndroidBridgeListener(
    context: Context,
    private val instanceRepository: InstanceRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, AndroidBridgeServer>()

    init {
        scope.launch {
            while (true) {
                try {
                    acceptConnections()
                } catch (error: Exception) {
                    Log.e(TAG, "Android bridge listener failed; retrying.", error)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    fun register(instanceId: String, controlEnabled: Boolean): AndroidBridgeServer {
        val session = AndroidBridgeServer(
            instanceId = instanceId,
            controlEnabled = controlEnabled,
            context = appContext,
            instanceRepository = instanceRepository,
            parentScope = scope,
        )
        sessions.put(instanceId, session)?.stop()
        session.awaitClient()
        return session
    }

    fun unregister(instanceId: String, session: AndroidBridgeServer? = null) {
        val removed = if (session == null) {
            sessions.remove(instanceId)
        } else if (sessions.remove(instanceId, session)) {
            session
        } else {
            null
        }
        removed?.stop()
    }

    fun click(instanceId: String, buttonId: String) {
        sessions[instanceId]?.click(buttonId)
            ?: instanceRepository.appendLog(instanceId, "Android bridge is not connected; cannot trigger button $buttonId.")
    }

    private fun acceptConnections() {
        val server = LocalServerSocket(SOCKET_NAME)
        Log.i(TAG, "Android bridge listener started: $SOCKET_NAME")
        try {
            while (true) {
                val socket = server.accept()
                scope.launch { route(socket) }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private fun route(socket: LocalSocket) {
        val reader = runCatching { socket.inputStream.bufferedReader() }.getOrElse {
            runCatching { socket.close() }
            return
        }
        val hello = reader.readHelloOrNull()
        val instanceId = hello?.optJSONObject("params")?.optString("instanceId").orEmpty()
        val session = sessions[instanceId]
        if (hello == null || session == null || !session.attachClient(socket, reader, hello)) {
            reject(socket, hello?.opt("id"))
        }
    }

    private fun BufferedReader.readHelloOrNull(): JSONObject? {
        val line = runCatching { readLine() }.getOrNull() ?: return null
        return runCatching { JSONObject(line) }.getOrNull()
            ?.takeIf { it.optString("method") == "hello" }
    }

    private fun reject(socket: LocalSocket, id: Any?) {
        if (id != null && id != JSONObject.NULL) {
            runCatching {
                socket.outputStream.bufferedWriter().use { writer ->
                    writer.write(
                        JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", id)
                            .put("error", JSONObject().put("code", 1001).put("message", "unauthorized"))
                            .toString(),
                    )
                    writer.newLine()
                }
            }
        }
        runCatching { socket.close() }
    }

    companion object {
        const val SOCKET_NAME = "cordis-android"
        private const val TAG = "AndroidBridgeListener"
        private const val RETRY_DELAY_MS = 1_000L
    }
}
