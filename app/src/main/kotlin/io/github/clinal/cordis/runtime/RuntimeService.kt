package io.github.clinal.cordis.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.clinal.cordis.CordisApplication
import io.github.clinal.cordis.data.InstanceRepository

class RuntimeService : Service() {
    private val supervisor: RuntimeSupervisor
        get() = (application as CordisApplication).runtimeSupervisor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID) ?: InstanceRepository.DEFAULT_INSTANCE_ID
        when (intent?.action) {
            ACTION_START -> supervisor.start(instanceId)
            ACTION_STOP -> supervisor.stop(instanceId)
        }
        return START_STICKY
    }

    companion object {
        const val ACTION_START = "io.github.clinal.cordis.runtime.START"
        const val ACTION_STOP = "io.github.clinal.cordis.runtime.STOP"
        const val EXTRA_INSTANCE_ID = "instance_id"
    }
}
