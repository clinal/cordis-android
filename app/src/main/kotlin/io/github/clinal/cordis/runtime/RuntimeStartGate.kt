package io.github.clinal.cordis.runtime

internal class RuntimeStartGate {
    private val active = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()

    @Synchronized
    fun request(instanceId: String): Boolean {
        if (active.add(instanceId)) return true
        pending.add(instanceId)
        return false
    }

    @Synchronized
    fun finish(instanceId: String): Boolean {
        if (pending.remove(instanceId)) return true
        active.remove(instanceId)
        return false
    }

    @Synchronized
    fun cancelPending(instanceId: String) {
        pending.remove(instanceId)
    }

    @Synchronized
    fun release(instanceId: String) {
        pending.remove(instanceId)
        active.remove(instanceId)
    }
}
