package io.github.clinal.cordis

import android.app.Application
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.runtime.RuntimeSupervisor

class CordisApplication : Application() {
    val instanceRepository by lazy { InstanceRepository(this) }
    val runtimeSupervisor by lazy { RuntimeSupervisor(this, instanceRepository) }
}
