package io.github.clinal.cordis.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidControlShell(context: Context) {
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context, AndroidControlUserService::class.java),
    )
        .daemon(false)
        .processNameSuffix("android_control")
        .debuggable(false)
        .version(1)
    @Volatile
    private var connected = CountDownLatch(0)
    @Volatile
    private var service: IAndroidControlService? = null
    @Volatile
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IAndroidControlService.Stub.asInterface(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    @Synchronized
    fun execute(command: String): JSONObject {
        check(Shizuku.pingBinder()) {
            "Shizuku is not running. Start Shizuku before using Android control."
        }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Shizuku permission is missing. Grant cordis-android access in the Shizuku app."
        }
        if (service == null) {
            connected = CountDownLatch(1)
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
                bound = true
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Failed to request the Shizuku Android control service: " +
                        (error.message ?: error.javaClass.simpleName),
                    error,
                )
            }
        }
        check(service != null || connected.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out connecting to the Shizuku Android control service. Check that Shizuku is running and authorized."
        }
        return try {
            JSONObject(checkNotNull(service).execute(command))
        } catch (error: RemoteException) {
            service = null
            throw IllegalStateException(
                "The Shizuku Android control service disconnected while executing the command.",
                error,
            )
        }
    }

    @Synchronized
    fun close() {
        if (!bound) return
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        bound = false
        service = null
    }

    companion object {
        private const val CONNECTION_TIMEOUT_SECONDS = 5L
    }
}
