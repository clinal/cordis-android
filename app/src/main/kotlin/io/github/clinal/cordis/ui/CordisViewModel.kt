package io.github.clinal.cordis.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.github.clinal.cordis.CordisApplication
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.runtime.RuntimeService

class CordisViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CordisApplication
    val instances = app.instanceRepository.instances
    val bootstrapInstallState = app.bootstrapInstallState

    fun addInstance() {
        app.instanceRepository.addInstance()
    }

    fun removeInstance(instanceId: String) {
        val intent = Intent(app, RuntimeService::class.java)
            .setAction(RuntimeService.ACTION_REMOVE)
            .putExtra(RuntimeService.EXTRA_INSTANCE_ID, instanceId)
        app.startService(intent)
    }

    fun updateInstanceConfig(instanceId: String, name: String, port: Int, dns: String) {
        app.instanceRepository.updateInstanceConfig(instanceId, name, port, dns)
    }

    fun start(instanceId: String = InstanceRepository.DEFAULT_INSTANCE_ID) {
        val intent = Intent(app, RuntimeService::class.java)
            .setAction(RuntimeService.ACTION_START)
            .putExtra(RuntimeService.EXTRA_INSTANCE_ID, instanceId)
        app.startService(intent)
    }

    fun stop(instanceId: String = InstanceRepository.DEFAULT_INSTANCE_ID) {
        val intent = Intent(app, RuntimeService::class.java)
            .setAction(RuntimeService.ACTION_STOP)
            .putExtra(RuntimeService.EXTRA_INSTANCE_ID, instanceId)
        app.startService(intent)
    }
}
