package io.github.clinal.cordis

import android.app.Application
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.runtime.RuntimeInstaller
import io.github.clinal.cordis.runtime.RuntimeSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CordisApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableBootstrapInstallState = MutableStateFlow(BootstrapInstallState())

    val instanceRepository by lazy { InstanceRepository(this) }
    val runtimeSupervisor by lazy { RuntimeSupervisor(this, instanceRepository) }
    val bootstrapInstallState: StateFlow<BootstrapInstallState> = mutableBootstrapInstallState

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            val installer = RuntimeInstaller(this@CordisApplication)
            val bootstrapReady = if (installer.needsBootstrapInstall()) {
                mutableBootstrapInstallState.value = BootstrapInstallState(
                    installing = true,
                    message = "Extracting runtime bootstrap.",
                )
                runCatching {
                    installer.prepareBootstrap { line ->
                        mutableBootstrapInstallState.value = BootstrapInstallState(
                            installing = true,
                            message = line,
                        )
                    }
                }.isSuccess
            } else {
                true
            }
            mutableBootstrapInstallState.value = BootstrapInstallState()
            if (bootstrapReady) {
                runtimeSupervisor.restoreAutoStartedInstances()
            }
        }
    }
}

data class BootstrapInstallState(
    val installing: Boolean = false,
    val message: String = "",
)
