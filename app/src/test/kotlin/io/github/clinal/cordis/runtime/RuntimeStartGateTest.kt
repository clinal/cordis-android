package io.github.clinal.cordis.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStartGateTest {
    private val gate = RuntimeStartGate()

    @Test
    fun `start requested during teardown runs after cleanup`() {
        assertTrue(gate.request(INSTANCE_ID))
        assertFalse(gate.request(INSTANCE_ID))

        assertTrue(gate.finish(INSTANCE_ID))
        assertFalse(gate.finish(INSTANCE_ID))
    }

    @Test
    fun `repeated pending starts are coalesced`() {
        assertTrue(gate.request(INSTANCE_ID))
        assertFalse(gate.request(INSTANCE_ID))
        assertFalse(gate.request(INSTANCE_ID))

        assertTrue(gate.finish(INSTANCE_ID))
        assertFalse(gate.finish(INSTANCE_ID))
    }

    @Test
    fun `stop cancels a pending restart`() {
        assertTrue(gate.request(INSTANCE_ID))
        assertFalse(gate.request(INSTANCE_ID))

        gate.cancelPending(INSTANCE_ID)

        assertFalse(gate.finish(INSTANCE_ID))
        assertTrue(gate.request(INSTANCE_ID))
    }

    private companion object {
        const val INSTANCE_ID = "instance-1"
    }
}
