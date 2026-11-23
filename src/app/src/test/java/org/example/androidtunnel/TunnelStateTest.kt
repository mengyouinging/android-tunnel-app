package org.example.androidtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelStateTest {
    @Test fun prefersDirectOnlyAfterPeerReply() {
        assertEquals(TunnelMode.DIRECT_IPV6, TunnelStateMachine.select(true, true, true))
        assertEquals(TunnelMode.FALLBACK, TunnelStateMachine.select(false, true, true))
    }

    @Test fun usesEmergencyOnlyWhenBothDataPathsFail() {
        assertEquals(TunnelMode.EMERGENCY, TunnelStateMachine.select(false, false, true))
        assertEquals(TunnelMode.FAILED, TunnelStateMachine.select(false, false, false))
    }

    @Test fun latencyRequiresPeerResponseAndDirectOrFallbackMode() {
        assertEquals(18L, TunnelStateMachine.validatedLatency(TunnelMode.DIRECT_IPV6, true, 18L))
        assertNull(TunnelStateMachine.validatedLatency(TunnelMode.DIRECT_IPV6, false, 18L))
        assertNull(TunnelStateMachine.validatedLatency(TunnelMode.EMERGENCY, true, 18L))
    }
}
