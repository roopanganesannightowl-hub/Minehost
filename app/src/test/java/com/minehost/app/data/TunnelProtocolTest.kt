package com.minehost.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Checks the wire-format pieces of the bore tunnel protocol without touching
 * the network: frame framing, hello parsing and message extraction.
 */
class TunnelProtocolTest {

    @Test
    fun `frames are NUL delimited`() {
        val output = ByteArrayOutputStream()
        sendFrame(DataOutputStream(output), TunnelManager.clientHello(25565))
        val bytes = output.toByteArray()
        assertEquals(0, bytes.last().toInt())
        assertEquals("{\"Hello\":25565}", String(bytes, 0, bytes.size - 1, Charsets.US_ASCII))
    }

    @Test
    fun `reads a frame from a stream`() {
        val payload = "{\"Hello\":41234}\u0000"
        val input = DataInputStream(ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)))
        assertEquals("{\"Hello\":41234}", readFrame(input))
    }

    @Test
    fun `reads consecutive frames`() {
        val payload = "{\"Heartbeat\":null}\u0000{\"Connection\":\"abc\"}\u0000"
        val input = DataInputStream(ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)))
        assertEquals("{\"Heartbeat\":null}", readFrame(input))
        assertEquals("{\"Connection\":\"abc\"}", readFrame(input))
    }

    @Test
    fun `parses the assigned public port`() {
        assertEquals(41234, TunnelManager.parsePublicPort("{\"Hello\":41234}"))
    }

    @Test(expected = java.io.IOException::class)
    fun `rejects a frame without a port`() {
        TunnelManager.parsePublicPort("{\"Heartbeat\":null}")
    }

    @Test
    fun `extracts connection uuids`() {
        val uuid = UUID.randomUUID()
        val parsed = TunnelManager.extractUuid("{\"Connection\":\"$uuid\"}")
        assertEquals(uuid, parsed)
    }

    @Test
    fun `ignores malformed uuids`() {
        assertNull(TunnelManager.extractUuid("{\"Connection\":\"not-a-uuid\"}"))
    }

    @Test
    fun `extracts relay error messages`() {
        assertEquals(
            "requested port is taken",
            TunnelManager.extractMessage("{\"Error\":\"requested port is taken\"}")
        )
        assertNull(TunnelManager.extractMessage("{\"Heartbeat\":null}"))
    }

    @Test
    fun `backoff grows but stays bounded`() {
        assertTrue(TunnelManager.backoffDelayMs(1) < TunnelManager.backoffDelayMs(4))
        assertEquals(15_000L, TunnelManager.backoffDelayMs(10))
    }
}
