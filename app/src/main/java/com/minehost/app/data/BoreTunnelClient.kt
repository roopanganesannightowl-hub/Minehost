package com.minehost.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Free public relay tunnel, in-app, zero dependencies.
 *
 * Speaks the [bore](https://github.com/ekzhang/bore) server protocol on the
 * wire: JSON frames delimited by NUL bytes on the control port (bore's
 * `AnyDelimiterCodec([0], [0])`). The default relay `bore.pub` is free to use.
 *
 * Control flow:
 *  1. TCP connect to `server:7835`.
 *  2. Send `Hello(requestPort)` with a random high port; the relay binds that
 *     exact public port and echoes it back in `Hello(publicPort)` (verified
 *     live: a busy port answers `Error("port already in use")`, a free one is
 *     bound and echoed). Port 25565 itself is permanently taken on bore.pub,
 *     so MineHost requests a random one in [TUNNEL_PORT_MIN, TUNNEL_PORT_MAX]
 *     and keeps it sticky across reconnects for a stable address.
 *  3. Each incoming player arrives as a `Connection(uuid)` frame on the
 *     control socket.
 *  4. A second TCP connection sends `Accept(uuid)`, then raw bytes are
 *     spliced between that socket and the local Minecraft server port.
 *  5. `Heartbeat` frames keep the control connection alive.
 *
 * Sockets are registered in [openSockets] so [stop] can force-close blocking
 * reads immediately instead of waiting for stream EOF.
 */
class TunnelManager(private val configProvider: suspend () -> TunnelSettings) {

    data class TunnelSettings(
        val relayHost: String = DEFAULT_RELAY_HOST,
        val localPort: Int = 25565
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private val openSockets = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    /** Last public port the relay bound for us; reused so the address is stable. */
    private var preferredPort = 0

    fun start() {
        if (_state.value.isActive) return
        stopRequested.set(false)
        sessionJob = scope.launch { runSessionLoop() }
    }

    fun stop() {
        stopRequested.set(true)
        sessionJob?.cancel()
        sessionJob = null
        // Force-closes every blocking read inside the session and player jobs.
        openSockets.forEach { socket -> runCatching { socket.close() } }
        openSockets.clear()
        preferredPort = 0
        _state.value = TunnelState(status = TunnelStatus.IDLE)
    }

    /** Called when the server stops; an empty server has nothing to tunnel. */
    fun resetForServerStop() {
        if (_state.value.isActive) stop()
    }

    private suspend fun runSessionLoop() {
        val settings = runCatching { configProvider() }.getOrElse {
            _state.value = TunnelState(status = TunnelStatus.FAILED, error = "Tunnel settings unavailable")
            return
        }
        var attempt = 0
        while (!stopRequested.get() && attempt < MAX_SESSION_ATTEMPTS) {
            attempt += 1
            _state.value = TunnelState(
                status = if (attempt == 1) TunnelStatus.CONNECTING else TunnelStatus.RECONNECTING,
                host = settings.relayHost,
                localPort = settings.localPort,
                error = _state.value.error.takeIf { attempt > 1 }
            )
            try {
                runSingleSession(settings)
                if (!stopRequested.get()) {
                    _state.value = _state.value.copy(
                        status = TunnelStatus.RECONNECTING,
                        error = "Relay closed the connection"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (stopRequested.get()) return
                _state.value = _state.value.copy(
                    status = TunnelStatus.RECONNECTING,
                    error = friendlyError(error)
                )
            }
            if (!stopRequested.get()) delay(backoffDelayMs(attempt))
        }
        if (!stopRequested.get()) {
            _state.value = _state.value.copy(
                status = TunnelStatus.FAILED,
                error = _state.value.error ?: "Could not reach ${settings.relayHost} after $MAX_SESSION_ATTEMPTS attempts"
            )
        }
    }

    /** One registration attempt: control socket, Hello exchange, accept loop. */
    private suspend fun runSingleSession(settings: TunnelSettings) {
        val control = register(Socket())
        try {
            control.tcpNoDelay = true
            control.connect(InetSocketAddress(settings.relayHost, CONTROL_PORT), CONNECT_TIMEOUT_MS)

            val input = DataInputStream(control.getInputStream().buffered())
            val output = DataOutputStream(control.getOutputStream().buffered())

            // Ask for a free public port; on a collision the relay says so and
            // we try again with a different number.
            var publicPort = 0
            var lastError: String? = null
            for (attempt in 1..PORT_COLLISION_ATTEMPTS) {
                val requested = if (preferredPort in TUNNEL_PORT_MIN..TUNNEL_PORT_MAX) {
                    preferredPort
                } else {
                    pickRequestPort()
                }
                sendFrame(output, clientHello(requested))
                val frame = readFrame(input)
                val relayError = extractMessage(frame)
                if (relayError != null) {
                    // The sticky port was taken while we were away; pick fresh.
                    lastError = relayError
                    preferredPort = 0
                    continue
                }
                publicPort = parsePublicPort(frame)
                preferredPort = publicPort
                break
            }
            if (publicPort == 0) {
                throw IOException(lastError ?: "The relay did not assign a public port")
            }

            _state.value = TunnelState(
                status = TunnelStatus.ACTIVE,
                host = settings.relayHost,
                publicPort = publicPort,
                localPort = settings.localPort,
                connectedAt = System.currentTimeMillis(),
                error = null
            )

            while (kotlin.coroutines.coroutineContext.isActive && !stopRequested.get()) {
                // A blocking read doubles as the liveness probe: if the relay
                // dies without a FIN, the read timeout fires and we reconnect.
                control.soTimeout = HEARTBEAT_TIMEOUT_MS
                val frame = try {
                    readFrame(input)
                } catch (error: IOException) {
                    throw IOException("Relay connection lost", error)
                }
                when {
                    frame.contains("\"Heartbeat\"") -> sendFrame(output, serverHeartbeat())
                    frame.contains("\"Connection\"") -> {
                        val uuid = extractUuid(frame) ?: continue
                        // Player pipes outlive the control session on purpose:
                        // a relay hiccup should not cut an active game.
                        scope.launch { handlePlayerConnection(settings, uuid) }
                    }
                    frame.contains("\"Error\"") ->
                        throw IOException(extractMessage(frame) ?: "Relay reported an error")
                }
            }
        } finally {
            runCatching { control.close() }
            openSockets.remove(control)
        }
    }

    /**
     * Accept one forwarded player: connect to the relay as a data connection,
     * claim it with Accept(uuid), then pipe raw bytes to the local server.
     */
    private suspend fun handlePlayerConnection(settings: TunnelSettings, uuid: UUID) = withContext(Dispatchers.IO) {
        val relay = register(Socket())
        try {
            relay.tcpNoDelay = true
            relay.connect(InetSocketAddress(settings.relayHost, CONTROL_PORT), CONNECT_TIMEOUT_MS)
            relay.soTimeout = 15_000
            val relayInput = DataInputStream(relay.getInputStream().buffered())
            val relayOutput = DataOutputStream(relay.getOutputStream().buffered())
            sendFrame(relayOutput, acceptMessage(uuid))
            // The relay answers Accept with an empty NUL-delimited frame;
            // consume it, then treat the socket as a raw byte pipe.
            runCatching { readFrame(relayInput) }

            relay.soTimeout = 0
            val local = register(Socket())
            try {
                local.tcpNoDelay = true
                local.connect(InetSocketAddress("127.0.0.1", settings.localPort), CONNECT_TIMEOUT_MS)
                relay.getInputStream().copyTo(local.getOutputStream(), BUFFER_SIZE)
            } finally {
                runCatching { local.close() }
                openSockets.remove(local)
            }
        } finally {
            runCatching { relay.close() }
            openSockets.remove(relay)
        }
    }

    private fun register(socket: Socket): Socket {
        openSockets.add(socket)
        return socket
    }

    private fun pickRequestPort(): Int =
        java.util.concurrent.ThreadLocalRandom.current().nextInt(TUNNEL_PORT_MIN, TUNNEL_PORT_MAX + 1)

    private fun friendlyError(error: Throwable): String = when {
        error.message?.contains("timeout", ignoreCase = true) == true ->
            "The relay is not reachable right now (timeout)"
        error.message?.contains("refused", ignoreCase = true) == true ->
            "The relay refused the connection"
        error.message?.contains("unreachable", ignoreCase = true) == true ->
            "No network route to the relay — check internet access"
        else -> error.message ?: "Unknown tunnel error"
    }

    companion object {
        const val DEFAULT_RELAY_HOST = "bore.pub"
        const val CONTROL_PORT = 7835
        const val CONNECT_TIMEOUT_MS = 10_000
        const val HEARTBEAT_TIMEOUT_MS = 35_000
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_SESSION_ATTEMPTS = 6
        const val PORT_COLLISION_ATTEMPTS = 3

        /** Random public ports are drawn from this range. */
        const val TUNNEL_PORT_MIN = 20000
        const val TUNNEL_PORT_MAX = 60000

        fun backoffDelayMs(attempt: Int): Long = (2_000L * attempt).coerceAtMost(15_000L)

        fun clientHello(port: Int): String = "{\"Hello\":$port}"

        fun acceptMessage(uuid: UUID): String = "{\"Accept\":\"$uuid\"}"

        fun serverHeartbeat(): String = "{\"Heartbeat\":null}"

        fun parsePublicPort(frame: String): Int {
            val match = Regex("\"Hello\":(\\d+)").find(frame)
                ?: throw IOException("The relay did not assign a public port")
            return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IOException("The relay assigned an invalid port")
        }

        fun extractUuid(frame: String): UUID? = runCatching {
            UUID.fromString(Regex("\"([0-9a-fA-F-]{36})\"").find(frame)?.groupValues?.get(1))
        }.getOrNull()

        fun extractMessage(frame: String): String? =
            Regex("\"Error\":\"([^\"]+)\"").find(frame)?.groupValues?.get(1)
    }
}

/** One NUL-delimited JSON frame, bore's wire format. */
internal fun sendFrame(output: DataOutputStream, frame: String) {
    val bytes = frame.toByteArray(Charsets.US_ASCII)
    output.write(bytes)
    output.write(0)
    output.flush()
}

internal fun readFrame(input: DataInputStream): String {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte < 0) throw IOException("Relay closed the connection")
        if (byte == 0) return buffer.toString("UTF-8")
        if (buffer.size() > MAX_FRAME_BYTES) throw IOException("Relay sent an oversized frame")
        buffer.write(byte)
    }
}

private const val MAX_FRAME_BYTES = 512
