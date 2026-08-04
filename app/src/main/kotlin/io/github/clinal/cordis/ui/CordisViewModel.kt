package io.github.clinal.cordis.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.github.clinal.cordis.CordisApplication
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.domain.CordisButton
import io.github.clinal.cordis.runtime.RuntimeService
import io.github.clinal.cordis.terminal.TerminalActivity

class CordisViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CordisApplication
    val instances = app.instanceRepository.instances
    val homeShortcuts = app.instanceRepository.homeShortcuts
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

    fun updateInstanceConfig(
        instanceId: String,
        name: String,
        port: Int,
        dns: String,
        androidControlEnabled: Boolean,
        hasWebService: Boolean,
        patchPort: Boolean,
    ) {
        app.instanceRepository.updateInstanceConfig(
            instanceId,
            name,
            port,
            dns,
            androidControlEnabled,
            hasWebService,
            patchPort,
        )
    }

    fun openGlobalTerminal() {
        val intent = TerminalActivity.globalIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun openInstanceTerminal(instanceId: String) {
        val intent = TerminalActivity.instanceIntent(app, instanceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
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

    fun addHomeShortcut(instanceId: String, button: CordisButton) {
        app.instanceRepository.addHomeShortcut(instanceId, button)
    }

    fun removeHomeShortcut(instanceId: String, buttonId: String) {
        app.instanceRepository.removeHomeShortcut(instanceId, buttonId)
    }

    fun clickBridgeButton(instanceId: String, buttonId: String) {
        app.runtimeSupervisor.clickBridgeButton(instanceId, buttonId)
    }
}
