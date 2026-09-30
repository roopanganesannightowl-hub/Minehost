package com.minehost.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelModelsTest {

    @Test
    fun `public endpoint only exists when active with a real port`() {
        val active = TunnelState(
            status = TunnelStatus.ACTIVE,
            host = "bore.pub",
            publicPort = 41234,
            localPort = 25565
        )
        assertEquals("bore.pub:41234", active.publicEndpoint)
    }

    @Test
    fun `no endpoint while connecting or idle`() {
        assertNull(
            TunnelState(status = TunnelStatus.CONNECTING, host = "bore.pub", publicPort = 41234).publicEndpoint
        )
        assertNull(
            TunnelState(status = TunnelStatus.IDLE, host = "bore.pub", publicPort = 41234).publicEndpoint
        )
        assertNull(
            TunnelState(status = TunnelStatus.ACTIVE, host = "bore.pub", publicPort = 0).publicEndpoint
        )
    }

    @Test
    fun `reconnecting counts as active so the UI keeps the stop button`() {
        val state = TunnelState(status = TunnelStatus.RECONNECTING, host = "bore.pub", localPort = 25565)
        assertEquals(true, state.isActive)
    }
}
