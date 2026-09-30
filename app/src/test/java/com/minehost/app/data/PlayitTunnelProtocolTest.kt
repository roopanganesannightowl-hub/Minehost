package com.minehost.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Checks the wire-format pieces of the playit.gg tunnel protocol without
 * touching the network: binary framing, address and response parsing, and
 * the encoded shapes of the messages we send.
 */
class PlayitTunnelProtocolTest {

    private val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val ipv6Loopback = InetAddress.getByAddress(
        ByteArray(16).also { it[15] = 1.toByte() }
    )

    @Test
    fun `encodes PingV2 as id plus u64 plus two absent options`() {
        val bytes = PlayitMessages.Ping.encode(1_234L)
        // ControlRequestId::PingV2 = 6 (the enum is 1-based; 5 is
        // AgentCheckPortMappingV1 and gets silently dropped by the server),
        // then u64 now, Option::None, Option::None.
        val expected = byteArrayOf(
            0, 0, 0, 6,                              // request id 6 (u32)
            0, 0, 0, 0, 0, 0, 4, (-46).toByte(),    // 1234 (u64)
            0,                                       // Option<u32>: None
            0                                        // Option<AgentSessionId>: None
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `parses a real production Pong datagram`() {
        // Captured from control.playit.gg:5525 in response to a PingV2 with
        // request_id 777; every field decodes against the Rust Pong layout.
        val datagram = hexToBytes(
            "00000001000000000000030900000001000001a0ed5afac8000001a0ed5afc12" +
                "00000000000000020000001c043d02eb0d918204d1198f01159500"
        )
        val feed = PlayitMessages.readFeed(datagram) as PlayitMessages.ControlFeed.Response
        assertEquals(777L, feed.rpc.requestId)
        val pong = (PlayitMessages.readResponse(feed.rpc)
            as PlayitMessages.ControlResponse.Pong).pong
        assertEquals(1_790_688_558_098L, pong.serverNow)
        assertEquals("61.2.235.13", pong.clientAddr.address.hostAddress)
        assertEquals(37_250, pong.clientAddr.port)
        assertEquals("209.25.143.1", pong.tunnelAddr.address.hostAddress)
        assertEquals(5_525, pong.tunnelAddr.port)
    }

    @Test
    fun `parses routing targets as bare ips and appends the control port`() {
        // /agents/routing/get returns targets4/targets6 as bare IP strings
        // (Rust Ipv4Addr/Ipv6Addr); the port is appended by the client.
        val routing = org.json.JSONObject(
            "{\"targets4\":[\"209.25.143.1\",\"209.25.142.1\"],\"targets6\":[\"2602:fbaf:810::1\"]}"
        )
        assertEquals(
            listOf(
                InetSocketAddress("209.25.143.1", 5_525),
                InetSocketAddress("209.25.142.1", 5_525),
                InetSocketAddress("2602:fbaf:810::1", 5_525)
            ),
            PlayitTunnelClient.parseRoutingTargets(routing)
        )
    }

    @Test
    fun `disable_ip6 skips ipv6 targets`() {
        val routing = org.json.JSONObject(
            "{\"targets4\":[\"209.25.143.1\"],\"targets6\":[\"2602:fbaf:810::1\"],\"disable_ip6\":true}"
        )
        assertEquals(
            listOf(InetSocketAddress("209.25.143.1", 5_525)),
            PlayitTunnelClient.parseRoutingTargets(routing)
        )
    }

    @Test
    fun `routing parse survives junk entries`() {
        val routing = org.json.JSONObject(
            "{\"targets4\":[\"\",\"not an ip\"],\"targets6\":[]}"
        )
        assertTrue(PlayitTunnelClient.parseRoutingTargets(routing).isEmpty())
    }

    /** Hex helper for captured wire bytes. */
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    @Test
    fun `encodes AgentKeepAlive as id plus session triple`() {
        val id = PlayitMessages.AgentSessionId(sessionId = 7L, accountId = 8L, agentId = 9L)
        val bytes = PlayitMessages.AgentKeepAlive.encode(id)
        val expected = byteArrayOf(
            0, 0, 0, 3,                  // ControlRequestId::AgentKeepAliveV1 = 3
            0, 0, 0, 0, 0, 0, 0, 7,      // session_id
            0, 0, 0, 0, 0, 0, 0, 8,      // account_id
            0, 0, 0, 0, 0, 0, 0, 9       // agent_id
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `wraps and unwraps the RPC envelope`() {
        val payload = PlayitMessages.Ping.encode(42L)
        val rpc = PlayitMessages.ControlRpc(requestId = 99L, payload = payload)
        val decoded = PlayitMessages.ControlRpc.decode(rpc.encode())
        assertEquals(99L, decoded.requestId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `parses a Pong with ipv4 addresses`() {
        val payload = PlayitMessages.Writer().also {
            it.u32(1)                          // ControlResponse::Pong
            it.u64(1_000L)                     // request_now
            it.u64(2_000L)                     // server_now
            it.u64(3)                          // server_id
            it.u32(4)                          // data_center_id
            it.bytes(playitAddress(ipv4Loopback, 5_000))   // client_addr
            it.bytes(playitAddress(ipv4Loopback, 5_525))   // tunnel_addr
            it.u8(0)                           // session_expire_at: None
        }.toByteArray()

        val response = PlayitMessages.readResponse(PlayitMessages.ControlRpc(7L, payload))
        val pong = (response as PlayitMessages.ControlResponse.Pong).pong
        assertEquals(2_000L, pong.serverNow)
        assertEquals(5_000, pong.clientAddr.port)
        assertEquals(ipv4Loopback, pong.clientAddr.address)
        assertEquals(5_525, pong.tunnelAddr.port)
    }

    @Test
    fun `parses a Pong with ipv6 addresses`() {
        val payload = PlayitMessages.Writer().also {
            it.u32(1)
            it.u64(1_000L)
            it.u64(2_000L)
            it.u64(3)
            it.u32(4)
            it.bytes(playitAddress(ipv6Loopback, 5_000))
            it.bytes(playitAddress(ipv6Loopback, 5_525))
            it.u8(0)
        }.toByteArray()

        val response = PlayitMessages.readResponse(PlayitMessages.ControlRpc(7L, payload))
        val pong = (response as PlayitMessages.ControlResponse.Pong).pong
        assertEquals(16, pong.clientAddr.address.address.size)
        assertEquals(ipv6Loopback, pong.clientAddr.address)
    }

    @Test
    fun `parses AgentRegistered from a feed datagram`() {
        val inner = PlayitMessages.Writer().also {
            it.u32(6)                          // ControlResponse::AgentRegistered
            it.bytes(PlayitMessages.AgentSessionId(1L, 2L, 3L).encode())
            it.u64(9_999L)                     // expires_at
        }.toByteArray()
        val datagram = PlayitMessages.Writer().also {
            it.u32(1)                          // ControlFeed::Response
            it.u64(55L)                        // request_id
            it.bytes(inner)
        }.toByteArray()

        val feed = PlayitMessages.readFeed(datagram)
        val response = (feed as PlayitMessages.ControlFeed.Response).rpc
        assertEquals(55L, response.requestId)
        val registered = PlayitMessages.readResponse(response)
        val id = (registered as PlayitMessages.ControlResponse.AgentRegistered).id
        assertEquals(1L, id.sessionId)
        assertEquals(2L, id.accountId)
        assertEquals(3L, id.agentId)
    }

    @Test
    fun `parses NewClient with a claim token`() {
        val token = byteArrayOf(1, 2, 3, 4, 5)
        val datagram = PlayitMessages.Writer().also {
            it.u32(3)                                       // ControlFeed::NewClient
            it.bytes(playitAddress(ipv4Loopback, 1_000))    // connect_addr
            it.bytes(playitAddress(ipv4Loopback, 2_000))    // peer_addr
            it.u32(12)                                      // data_center_id
            it.u64(34_567L)                                 // tunnel_id
            it.u16(0)                                       // port_offset
            it.bytes(playitAddress(ipv4Loopback, 3_000))    // claim address
            it.u64(token.size.toLong())                     // Vec length
            it.bytes(token)                                 // claim token
        }.toByteArray()

        val client = (PlayitMessages.readFeed(datagram) as PlayitMessages.ControlFeed.NewClient).client
        assertEquals(34_567L, client.tunnelId)
        assertEquals(3_000, client.claimAddress?.port)
        assertArrayEquals(token, client.claimToken)
        assertEquals(2_000, client.peerAddr?.port)
    }

    @Test
    fun `round-trips NewClient parse on the same bytes it encodes`() {
        // Re-parse must be deterministic: same datagram, same fields.
        val datagram = PlayitMessages.Writer().also {
            it.u32(3)
            it.bytes(playitAddress(ipv6Loopback, 1_000))
            it.bytes(playitAddress(ipv6Loopback, 2_000))
            it.u32(12)
            it.u64(34_567L)
            it.u16(0)
            it.bytes(playitAddress(ipv6Loopback, 3_000))
            it.u64(0)
        }.toByteArray()

        val first = (PlayitMessages.readFeed(datagram) as PlayitMessages.ControlFeed.NewClient).client
        val second = (PlayitMessages.readFeed(datagram) as PlayitMessages.ControlFeed.NewClient).client
        assertEquals(first.tunnelId, second.tunnelId)
        assertEquals(first.claimAddress, second.claimAddress)
        assertArrayEquals(first.claimToken, second.claimToken)
    }

    @Test
    fun `returns null for unknown feed ids`() {
        assertNull(PlayitMessages.readFeed(byteArrayOf(0, 0, 0, 99)))
    }

    @Test
    fun `rejects oversized vectors`() {
        val datagram = PlayitMessages.Writer().also {
            it.u32(3)
            it.bytes(playitAddress(ipv4Loopback, 1_000))
            it.bytes(playitAddress(ipv4Loopback, 2_000))
            it.u32(12)
            it.u64(1)
            it.u16(0)
            it.bytes(playitAddress(ipv4Loopback, 3_000))
            it.u64(1_000_000L)  // claims a megabyte but no bytes follow
        }.toByteArray()
        assertNull(PlayitMessages.readFeed(datagram))
    }

    /** A client that never touches the network for these pure-format checks. */
    private fun client() = PlayitTunnelClient { PlayitTunnelClient.PlayitTunnelSettings() }

    @Test
    fun `encodes observed addresses as playit socket-addr strings`() {
        // /proto/register wants `host:port` strings (Rust SocketAddr), not
        // objects; objects make playit.gg reject the whole registration.
        val client = PlayitTunnelClient { PlayitTunnelClient.PlayitTunnelSettings() }
        assertEquals(
            "127.0.0.1:5525",
            client.encodeSocketAddress(InetSocketAddress(ipv4Loopback, 5_525))
        )
        val ipv6 = client.encodeSocketAddress(InetSocketAddress(ipv6Loopback, 5_000))
        assertTrue("IPv6 host must be bracketed, was $ipv6", ipv6.startsWith("["))
        assertTrue("IPv6 port must be appended, was $ipv6", ipv6.endsWith("]:5000"))
    }

    @Test
    fun `claim codes match the official 10-char hex format`() {
        val client = PlayitTunnelClient { PlayitTunnelClient.PlayitTunnelSettings() }
        repeat(20) {
            val code = client.generateClaimCode()
            assertEquals(10, code.length)
            assertTrue(code.all { it.isDigit() || it in 'a'..'f' })
        }
    }

    @Test
    fun `backoff grows but stays bounded`() {
        assertTrue(PlayitTunnelClient.backoffDelayMs(1) < PlayitTunnelClient.backoffDelayMs(4))
        assertEquals(15_000L, PlayitTunnelClient.backoffDelayMs(10))
    }

    @Test
    fun `hex decoding accepts uppercase and whitespace`() {
        val hex = "DE AD be ef"
        val expected = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertArrayEquals(expected, PlayitTunnelClient.hexToByteArray(hex))
        assertArrayEquals(expected, PlayitTunnelClient.hexToByteArray("deadbeef"))
    }

    /** Encodes a SocketAddr the way the Rust `message-encoding` crate does. */
    private fun playitAddress(address: InetAddress, port: Int): ByteArray =
        PlayitMessages.Writer().also {
            it.u8(if (address.address.size == 4) 4 else 6)
            it.bytes(address.address)
            it.u16(port)
        }.toByteArray()
}
