package io.github.clinal.cordis

import android.app.Application
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.runtime.RuntimeInstaller
import io.github.clinal.cordis.runtime.RuntimeSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CordisApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val instanceRepository by lazy { InstanceRepository(this) }
    val runtimeSupervisor by lazy { RuntimeSupervisor(this, instanceRepository) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            RuntimeInstaller(this@CordisApplication).prepareBootstrap()
        }
    }
}
