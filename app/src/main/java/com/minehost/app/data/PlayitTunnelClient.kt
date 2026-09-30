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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Public playit.gg tunnel, implemented natively in-app (zero dependencies).
 *
 * Speaks the same protocols as the official Rust agent
 * (https://github.com/playit-cloud/playit-agent):
 *
 *  1. Claim: [startClaim] POSTs `/claim/setup` and opens
 *     `https://playit.gg/mk/<code>` for the user to accept; [exchangeClaim]
 *     then swaps the code for a persistent secret key. Guests work; the
 *     address stays the same after the account is upgraded.
 *  2. Setup: [fetchControlAddresses] resolves the control servers over UDP
 *     DNS, [pingControl] pings one until it answers, and [registerAgent]
 *     exchanges the observed public addresses for pre-signed registration
 *     bytes at `/proto/register` — the agent never signs anything locally.
 *  3. Control: `AgentRegister` (replaying the signed bytes) + `AgentKeepAlive`
 *     run over a UDP control channel on port 5525. The control feed pushes a
 *     [PlayitMessages.NewClient] for every incoming player.
 *  4. Data: for each NewClient the agent connects to the claim address, sends
 *     the claim token, reads the 8-byte confirmation, then splices raw bytes
 *     with the local Minecraft server.
 *
 * Player pipes outlive control reconnects on purpose: a control hiccup should
 * not cut an active game.
 */
class PlayitTunnelClient(private val configProvider: suspend () -> PlayitTunnelSettings) {

    data class PlayitTunnelSettings(
        val localPort: Int = 25565,
        /** Persistent agent secret from the claim flow, blank until claimed. */
        val secretKey: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PlayitTunnelState())
    val state: StateFlow<PlayitTunnelState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private val openSockets = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private var controlSocket: DatagramSocket? = null

    fun start() {
        if (_state.value.isActive) return
        stopRequested.set(false)
        sessionJob = scope.launch { runSessionLoop() }
    }

    fun stop() {
        stopRequested.set(true)
        sessionJob?.cancel()
        sessionJob = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        // Force-closes every blocking read inside the player jobs.
        openSockets.forEach { socket -> runCatching { socket.close() } }
        openSockets.clear()
        _state.value = PlayitTunnelState()
    }

    /** Called when the server stops; an empty server has nothing to tunnel. */
    fun resetForServerStop() {
        if (_state.value.isActive) stop()
    }

    private suspend fun runSessionLoop() {
        val settings = runCatching { configProvider() }.getOrElse {
            _state.value = PlayitTunnelState(status = TunnelStatus.FAILED, error = "Tunnel settings unavailable")
            return
        }
        if (settings.secretKey.isBlank()) {
            _state.value = PlayitTunnelState(
                status = TunnelStatus.FAILED,
                error = "Claim a playit.gg agent first in Settings"
            )
            return
        }
        var attempt = 0
        while (!stopRequested.get() && attempt < MAX_SESSION_ATTEMPTS) {
            attempt += 1
            _state.value = PlayitTunnelState(
                status = if (attempt == 1) TunnelStatus.CONNECTING else TunnelStatus.RECONNECTING,
                error = _state.value.error.takeIf { attempt > 1 }
            )
            try {
                runSingleSession(settings)
                if (!stopRequested.get()) {
                    _state.value = _state.value.copy(status = TunnelStatus.RECONNECTING)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (stopRequested.get()) return
                _state.value = _state.value.copy(status = TunnelStatus.RECONNECTING, error = friendlyError(error))
            }
            if (!stopRequested.get()) delay(backoffDelayMs(attempt))
        }
        if (!stopRequested.get()) {
            _state.value = _state.value.copy(
                status = TunnelStatus.FAILED,
                error = _state.value.error ?: "Could not reach the playit.gg network"
            )
        }
    }

    /**
     * One control session over a single UDP socket: ping, register, keepalive
     * loop, feed dispatch. The socket stays the same for all three steps like
     * the official agent — the control server keys the session to the NAT
     * mapping it observed, so a new socket per step can orphan the registration.
     */
    private suspend fun runSingleSession(settings: PlayitTunnelSettings) {
        val secret = settings.secretKey.trim()
        val api = PlayitApi(secret)

        // 1. Resolve control servers (an API call, so the network is up) and
        // order IPv4 first — a UDP send to an unreachable IPv6 target throws
        // synchronously, and carriers rarely hand phones a v6 route.
        val controlTargets = fetchControlAddresses(api)
        if (stopRequested.get()) return
        _state.value = _state.value.copy(status = TunnelStatus.CONNECTING)

        DatagramSocket().use { socket ->
            controlSocket = socket
            try {
                // 2. Ping until one control server answers; it echoes the
                // public address it observed and pins the session to this
                // socket's NAT mapping.
                val (pong, controlAddr) = pingControl(socket, controlTargets)
                if (stopRequested.get()) return

                // 3. Trade secret + observed addresses for pre-signed bytes.
                val signed = registerAgent(api, pong)

                // 4. Replay the signed registration on the SAME socket, then
                // run the keepalive/feed loop.
                runControlChannel(socket, controlAddr, signed.bytes, api, settings)
            } finally {
                controlSocket = null
            }
        }
    }

    private suspend fun runControlChannel(
        socket: DatagramSocket,
        controlAddr: InetSocketAddress,
        signedBytes: ByteArray,
        api: PlayitApi,
        settings: PlayitTunnelSettings
    ) {
        socket.soTimeout = CONTROL_RECV_TIMEOUT_MS
        val buffer = ByteArray(CONTROL_BUFFER_SIZE)

        fun sendTo(msg: ByteArray, addr: InetSocketAddress) {
            val packet = DatagramPacket(msg, msg.size, addr.address, addr.port)
            // Tolerate a route disappearing mid-session: the silence timeout
            // below is what reports a genuinely dead control channel.
            runCatching { socket.send(packet) }
        }

        /** Reads one datagram from the control server, or null on timeout. */
        fun recvFrom(): PlayitMessages.ControlFeed? {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(packet) }.getOrNull() ?: return null
            val from = InetSocketAddress(packet.address, packet.port)
            if (from.address != controlAddr.address || from.port != controlAddr.port) return null
            return runCatching { PlayitMessages.readFeed(buffer, packet.length) }.getOrNull()
        }

        // Register: replay the pre-signed bytes until the server accepts them.
        // The official agent re-sends up to 5 times while waiting; the control
        // server can drop the first datagram while it validates the flow.
        val request = PlayitMessages.ControlRpc(PLAYIT_REQUEST_ID, signedBytes).encode()
        var sessionId: PlayitMessages.AgentSessionId? = null
        var registered = false
        for (round in 1..REGISTER_ROUNDS) {
            sendTo(request, controlAddr)
            repeat(REGISTER_ATTEMPTS) {
                if (registered) return@repeat
                val feed = recvFrom() ?: return@repeat
                val response = (feed as? PlayitMessages.ControlFeed.Response)?.rpc
                    ?.let { PlayitMessages.readResponse(it) }
                when (response) {
                    is PlayitMessages.ControlResponse.AgentRegistered -> {
                        sessionId = response.id
                        registered = true
                    }
                    is PlayitMessages.ControlResponse.Unauthorized ->
                        throw IOException("playit.gg rejected this agent key — relink the agent in Settings")
                    // A Pong to the raw slice means the flow changed; retry.
                    is PlayitMessages.ControlResponse.Pong, null -> Unit
                }
            }
            if (registered) break
        }
        if (!registered || sessionId == null) {
            throw IOException("playit.gg did not accept the agent registration")
        }

        _state.value = _state.value.copy(status = TunnelStatus.ACTIVE, connectedAt = System.currentTimeMillis(), error = null)

        // The agent is online now; look up the public `*.at.ply.gg` address the
        // network handed this agent so the UI can show what friends join with.
        // Re-checked periodically: a tunnel created on playit.gg AFTER this
        // session started only shows up on a later rundata fetch.
        scope.launch {
            while (!stopRequested.get() && _state.value.status == TunnelStatus.ACTIVE) {
                val endpoint = fetchPublicEndpoint(api)
                if (endpoint != null && !stopRequested.get() && _state.value.status == TunnelStatus.ACTIVE) {
                    _state.value = _state.value.copy(publicEndpoint = endpoint)
                }
                delay(ENDPOINT_REFRESH_INTERVAL_MS)
            }
        }

        var lastKeepAlive = System.currentTimeMillis()
        var lastServerActivity = lastKeepAlive

        while (kotlin.coroutines.coroutineContext.isActive && !stopRequested.get()) {
            val feed = recvFrom()
            if (feed != null) {
                lastServerActivity = System.currentTimeMillis()
                when (feed) {
                    is PlayitMessages.ControlFeed.NewClient -> {
                        // Player pipes outlive the control session on purpose.
                        scope.launch { handlePlayerConnection(settings, feed.client) }
                    }
                    is PlayitMessages.ControlFeed.Response -> {
                        if (PlayitMessages.readResponse(feed.rpc) is PlayitMessages.ControlResponse.Unauthorized) {
                            throw IOException("Agent session expired; re-registering")
                        }
                    }
                }
            } else if (System.currentTimeMillis() - lastServerActivity > SESSION_SILENCE_TIMEOUT_MS) {
                throw IOException("playit.gg control channel went silent")
            }

            val now = System.currentTimeMillis()
            if (now - lastKeepAlive >= KEEPALIVE_INTERVAL_MS) {
                lastKeepAlive = now
                val keepAlive = PlayitMessages.ControlRpc(
                    PLAYIT_REQUEST_ID,
                    PlayitMessages.AgentKeepAlive.encode(checkNotNull(sessionId))
                ).encode()
                sendTo(keepAlive, controlAddr)
            }
        }
    }

    /**
     * Accepts one forwarded player: connect to the claim address, present the
     * token, wait for the 8-byte confirmation, then splice raw TCP bytes with
     * the local server.
     */
    private suspend fun handlePlayerConnection(settings: PlayitTunnelSettings, client: PlayitMessages.NewClient) =
        withContext(Dispatchers.IO) {
            val claimAddr = client.claimAddress
            if (claimAddr == null || client.claimToken.isEmpty()) return@withContext
            val relay = register(Socket())
            try {
                relay.tcpNoDelay = true
                relay.connect(claimAddr, CONNECT_TIMEOUT_MS)
                relay.getOutputStream().write(client.claimToken)
                relay.getOutputStream().flush()

                val confirmation = ByteArray(CLAIM_CONFIRMATION_BYTES)
                val input = DataInputStream(relay.getInputStream().buffered())
                input.readFully(confirmation)

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
    }    /**
     * Surface errors as-is when they already carry a stage marker so remote
     * debugging stays possible, and translate raw socket noise into hints.
     */
    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("Routing lookup failed:") ||
                message.startsWith("Control ping failed:") ||
                message.startsWith("Registration failed:") -> message
            message.contains("timeout", ignoreCase = true) ->
                "Control ping failed: the playit.gg network did not answer (timeout)"
            message.contains("unreachable", ignoreCase = true) ||
                message.contains("no route", ignoreCase = true) ->
                    "Control ping failed: this network has no route to playit.gg — " +
                        "turn off any VPN, then try Wi-Fi or mobile data"
            else -> error.message ?: "Unknown tunnel error"
        }
    }

    // ------------------------------------------------------------------
    // Claim flow (one-time; the secret persists in DataStore afterwards)
    // Mirrors the official agent: a 10-char hex code, /claim/setup polled
    // until the user accepts, then /claim/exchange for the secret.
    // ------------------------------------------------------------------

    enum class ClaimPhase { WAITING_FOR_VISIT, WAITING_FOR_USER, ACCEPTED, REJECTED }

    data class ClaimState(val code: String, val url: String)

    /**
     * Registers a fresh claim code and returns the URL the user must visit.
     * The code is exactly what the official CLI generates: hex(5 random
     * bytes) — playit.gg rejects anything else with InvalidCode.
     */
    suspend fun startClaim(): ClaimState {
        val code = generateClaimCode()
        val response = PlayitApi("").post(
            "/claim/setup",
            JSONObject()
                .put("code", code)
                .put("agent_type", CLAIM_AGENT_TYPE)
                .put("version", "playit ${PlayitApi.agentVersionString()}")
        )
        when (response.optString("status")) {
            "success" -> Unit
            "fail" -> throw IOException(
                "playit.gg rejected the claim start (${response.optString("data")})"
            )
            else -> throw IOException("Unexpected reply from playit.gg")
        }
        return ClaimState(code, CLAIM_URL_BASE + code)
    }

    /**
     * Polls /claim/setup for the user's decision, exactly like the official
     * agent does before it ever touches /claim/exchange.
     */
    suspend fun pollClaim(code: String): ClaimPhase {
        val response = PlayitApi("").post(
            "/claim/setup",
            JSONObject()
                .put("code", code)
                .put("agent_type", CLAIM_AGENT_TYPE)
                .put("version", "playit ${PlayitApi.agentVersionString()}")
        )
        return when (response.optString("status")) {
            "success" -> when (response.optString("data")) {
                "UserAccepted" -> ClaimPhase.ACCEPTED
                "UserRejected" -> ClaimPhase.REJECTED
                "WaitingForUser" -> ClaimPhase.WAITING_FOR_USER
                else -> ClaimPhase.WAITING_FOR_VISIT
            }
            "fail" -> {
                val reason = response.optString("data")
                if (reason == "CodeExpired" || reason == "InvalidCode") {
                    throw IOException("The claim code expired — start again")
                }
                ClaimPhase.WAITING_FOR_VISIT
            }
            else -> throw IOException("Unexpected reply from playit.gg")
        }
    }

    /**
     * Swaps an accepted claim for the persistent secret. Returns null while
     * the acceptance has not propagated yet; throws on rejection.
     */
    suspend fun exchangeClaim(code: String): String? {
        val response = PlayitApi("").post("/claim/exchange", JSONObject().put("code", code))
        return when (response.optString("status")) {
            "success" -> response.getJSONObject("data").getString("secret_key")
            "fail" -> when (val reason = response.optString("data")) {
                // CodeNotFound also appears right after the user accepts while
                // the acceptance is propagating (verified against production).
                "NotAccepted", "NotSetup", "CodeNotFound" -> null
                "UserRejected" -> throw IOException("The claim was rejected in the browser")
                else -> throw IOException("Claim failed: $reason")
            }
            else -> throw IOException("Unexpected reply from playit.gg")
        }
    }

    /** Same shape as the official CLI: hex-encode 5 random bytes. */
    internal fun generateClaimCode(): String {
        val bytes = ByteArray(CLAIM_CODE_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // API + control setup helpers
    // ------------------------------------------------------------------

    /**
     * Resolves the control servers from the API. The entries are `host:port`
     * strings, NOT bare IPs (the old parser read them with `getByName`, which
     * threw on a `:5525` suffix and silently left zero targets). IPv4 targets
     * are tried first: a UDP send to an unreachable IPv6 address throws
     * synchronously, and phones rarely have a v6 route on mobile data.
     */
    private suspend fun fetchControlAddresses(api: PlayitApi): List<InetSocketAddress> {
        val response = api.post("/agents/routing/get", JSONObject().put("agent_id", JSONObject.NULL))
        if (response.optString("status") != "success") {
            throw IOException(
                "Routing lookup failed: " + (apiErrorMessage(response) ?: "no control servers returned")
            )
        }
        val routing = response.getJSONObject("data")
        val targets = parseRoutingTargets(routing).toMutableList()
        if (targets.isEmpty()) {
            for (host in FALLBACK_CONTROL_HOSTS) {
                runCatching { targets.add(InetSocketAddress(InetAddress.getByName(host), CONTROL_PORT)) }
            }
        }
        if (targets.isEmpty()) {
            throw IOException("Routing lookup failed: playit.gg returned no control servers")
        }
        return targets
    }

    /**
     * Pings the control servers one at a time until one answers — the same
     * strategy as the official agent's `connect_to_first`. A synchronous send
     * failure (no v6 route, no route at all) only skips that one target.
     * Returns the Pong plus the target that answered; the Pong anchors the
     * registration to this socket's NAT mapping, and replay must go to the
     * same server the Pong came from.
     */
    private fun pingControl(
        socket: DatagramSocket,
        controlTargets: List<InetSocketAddress>
    ): Pair<PlayitMessages.Pong, InetSocketAddress> {
        val ping = PlayitMessages.ControlRpc(
            PLAYIT_REQUEST_ID,
            PlayitMessages.Ping.encode(System.currentTimeMillis())
        ).encode()
        val buffer = ByteArray(CONTROL_BUFFER_SIZE)
        val deadline = System.currentTimeMillis() + PING_DEADLINE_MS
        for (target in controlTargets) {
            repeat(PING_ATTEMPTS_PER_TARGET) {
                if (System.currentTimeMillis() >= deadline) return@repeat
                if (stopRequested.get()) return@repeat
                // A send that throws only means this target is unreachable
                // from this network — try the next one.
                runCatching {
                    socket.send(DatagramPacket(ping, ping.size, target.address, target.port))
                }.getOrElse { return@repeat }
                socket.soTimeout = PING_TIMEOUT_MS
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.getOrElse { return@repeat }
                val feed = runCatching { PlayitMessages.readFeed(buffer, packet.length) }.getOrNull() ?: return@repeat
                val response = (feed as? PlayitMessages.ControlFeed.Response)
                    ?.rpc?.let { PlayitMessages.readResponse(it) }
                val pong = (response as? PlayitMessages.ControlResponse.Pong)?.pong ?: return@repeat
                // Validate the source like the official agent does.
                if (packet.address != target.address || packet.port != target.port) return@repeat
                return pong to target
            }
        }
        throw IOException(
            "Control ping failed: no answer from the playit.gg control servers — " +
                "mobile carriers sometimes block UDP; try another network"
        )
    }

    /**
     * Exchanges the secret + the addresses the control server just observed
     * for pre-signed registration bytes. `client_addr` must be the address
     * from the SAME UDP socket that will replay the signed bytes, or the
     * control server discards the registration as a flow mismatch.
     */
    private suspend fun registerAgent(
        api: PlayitApi,
        pong: PlayitMessages.Pong
    ): PlayitMessages.SignedAgentRegister {
        // Shape matches the official agent's ReqProtoRegister: `client_addr`
        // and `tunnel_addr` are `host:port` strings (Rust `SocketAddr`) and
        // `platform` is the lowercase platform enum, not a display name.
        val body = JSONObject()
            .put("agent_version", JSONObject.NULL)
            .put("version", PlayitApi.agentVersion())
            .put("platform", "android")
            .put("proto_version", 2)
            .put("client_addr", encodeSocketAddress(pong.clientAddr))
            .put("tunnel_addr", encodeSocketAddress(pong.tunnelAddr))
        val response = api.post("/proto/register", body)
        if (response.optString("status") != "success") {
            val detail = apiErrorMessage(response) ?: "playit.gg refused to register this agent"
            throw IOException(
                if (detail.contains("InvalidSignature", ignoreCase = true)) {
                    "Registration failed: $detail (check the device clock)"
                } else {
                    "Registration failed: $detail"
                }
            )
        }
        val data = response.getJSONObject("data")
        val hex = data.getString("key")
        return PlayitMessages.SignedAgentRegister(hexToByteArray(hex))
    }

    /**
     * playit.gg encodes observed endpoints as a single `host:port` string
     * (Rust `SocketAddr`); IPv6 hosts use the bracketed form. Sending an
     * `{ip, port}` object makes `/proto/register` reject the agent.
     */
    internal fun encodeSocketAddress(addr: SocketAddress): String {
        val inet = addr as? InetSocketAddress ?: throw IOException("Unexpected address type")
        val host = inet.address?.hostAddress ?: throw IOException("Unresolved address")
        val rendered = if (inet.address is Inet6Address) "[$host]" else host
        return "$rendered:${inet.port}"
    }

    /** Pulls a readable message out of a playit `fail`/`error` response body. */
    private fun apiErrorMessage(response: JSONObject): String? = when (val data = response.opt("data")) {
        is String -> data.takeIf { it.isNotBlank() }
        is JSONObject -> data.optString("message").takeIf { it.isNotBlank() }
            ?: data.optString("type").takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * Reads the agent's tunnels and returns the public join address, so the UI
     * can show the real `*.at.ply.gg` name instead of just "online".
     */
    private suspend fun fetchPublicEndpoint(api: PlayitApi): String? = runCatching {
        val response = api.post("/agents/rundata", JSONObject())
        if (response.optString("status") != "success") return@runCatching null
        val tunnels = response.getJSONObject("data").optJSONArray("tunnels") ?: return@runCatching null
        for (index in 0 until tunnels.length()) {
            val tunnel = tunnels.optJSONObject(index) ?: continue
            // A disabled tunnel still reports an address, but players cannot
            // join it, so never advertise one.
            if (readText(tunnel, "disabled_reason") != null) continue
            for (key in TUNNEL_ADDRESS_KEYS) {
                readText(tunnel, key)?.let { return@runCatching it }
            }
        }
        null
    }.getOrNull()

    /**
     * Reads a JSON value as non-blank text, treating an explicit `null` the way
     * every JSON implementation does. Android's `optString` renders a JSON null
     * as the literal string "null", so it cannot be used for optional fields.
     */
    private fun readText(json: JSONObject, key: String): String? {
        val value = json.opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        return value.toString().trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        /**
         * Last-resort control servers when /agents/routing/get returns no
         * usable targets. `control.playit.gg` answers pings on UDP 5525
         * (verified against production); the old `pop1.hk.production...`
         * name no longer resolves in DNS at all.
         */
        const val FALLBACK_CONTROL_HOSTS_FIRST = "control.playit.gg"
        const val FALLBACK_CONTROL_HOSTS_SECOND = "pop1.hk.production.playit.gg"
        val FALLBACK_CONTROL_HOSTS = listOf(FALLBACK_CONTROL_HOSTS_FIRST, FALLBACK_CONTROL_HOSTS_SECOND)
        const val CONTROL_PORT = 5525
        const val PING_ATTEMPTS_PER_TARGET = 2

        /**
         * Reads the routing response. `targets4`/`targets6` are BARE IP
         * strings (Rust `Ipv4Addr`/`Ipv6Addr` — the official agent appends
         * port 5525 itself); `disable_ip6` skips v6 targets, which playit
         * sets when an account is known to be v4-only. IPv4 first either way.
         */
        internal fun parseRoutingTargets(routing: JSONObject): List<InetSocketAddress> {
            val targets = mutableListOf<InetSocketAddress>()
            val order = if (routing.optBoolean("disable_ip6", false)) {
                listOf("targets4")
            } else {
                listOf("targets4", "targets6")
            }
            for (key in order) {
                val array = routing.optJSONArray(key) ?: continue
                for (i in 0 until array.length()) {
                    val ip = array.optString(i).trim().removePrefix("/")
                    if (ip.isEmpty()) continue
                    runCatching { targets.add(InetSocketAddress(InetAddress.getByName(ip), CONTROL_PORT)) }
                }
            }
            return targets
        }
        const val CONNECT_TIMEOUT_MS = 10_000
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_SESSION_ATTEMPTS = 6
        const val KEEPALIVE_INTERVAL_MS = 30_000L
        const val SESSION_SILENCE_TIMEOUT_MS = 120_000L
        const val ENDPOINT_REFRESH_INTERVAL_MS = 60_000L
        const val PING_TIMEOUT_MS = 3_000
        const val PING_DEADLINE_MS = 12_000
        const val CONTROL_RECV_TIMEOUT_MS = 5_000
        const val CONTROL_BUFFER_SIZE = 2048
        const val REGISTER_ROUNDS = 3
        const val REGISTER_ATTEMPTS = 5
        const val CLAIM_CONFIRMATION_BYTES = 8
        const val CLAIM_POLL_INTERVAL_MS = 2_000L
        const val CLAIM_POLL_ATTEMPTS = 150
        const val CLAIM_EXCHANGE_ATTEMPTS = 5
        const val CLAIM_CODE_BYTES = 5
        const val CLAIM_URL_BASE = "https://playit.gg/claim/"

        /**
         * Where `/agents/rundata` hides a tunnel's join address, most specific
         * first: `display_address` is what the dashboard shows, and the two
         * domain fields cover tunnels with a custom or assigned hostname.
         */
        internal val TUNNEL_ADDRESS_KEYS = listOf("display_address", "assigned_domain", "custom_domain")

        /**
         * `assignable` is what the official program's setup uses: the agent
         * appears in the playit.gg dashboard and tunnels created there are
         * attached to it. `self-managed` is for agents that create their own
         * tunnels through the API, which MineHost does not do (yet).
         */
        const val CLAIM_AGENT_TYPE = "assignable"
        /** Arbitrary correlation id; the API matches replies by this value. */
        const val PLAYIT_REQUEST_ID = 777L

        /** Decodes the hex secret key returned by /proto/register. */
        internal fun hexToByteArray(hex: String): ByteArray {
            val clean = hex.replace(" ", "").replace("\n", "")
            require(clean.length % 2 == 0) { "Invalid hex key from playit.gg" }
            return ByteArray(clean.length / 2) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
        }

        fun backoffDelayMs(attempt: Int): Long = (2_000L * attempt).coerceAtMost(15_000L)
    }
}

/**
 * Minimal JSON POST client for api.playit.gg. Uses `Agent-Key <secret>` auth
 * exactly like the official agent, and never follows a request off-host.
 */
class PlayitApi(private val secretKey: String) {

    suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = open(path)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (secretKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Agent-Key ${secretKey.trim()}")
            }
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (text.isBlank()) throw IOException("playit.gg returned HTTP $code with no body")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        if (!path.startsWith("/")) throw IOException("API path must start with /")
        val url = URI.create("$API_BASE_URL$path").toURL()
        val connection = url.openConnection() as HttpURLConnection
        // HTTPS allowlist, same policy as the rest of MineHost.
        require(connection.url.host == "api.playit.gg") { "Unexpected API host" }
        return connection
    }

    companion object {
        const val API_BASE_URL = "https://api.playit.gg"

        /** Mirrors the official agent's version reporting. */
        fun agentVersion(): JSONObject = JSONObject()
            .put("variant_id", AGENT_VARIANT_ID)
            .put("version_major", 1)
            .put("version_minor", 0)
            .put("version_patch", 10)

        fun agentVersionString(): String = "1.0.10"

        const val AGENT_VARIANT_ID = "308943e8-faef-4835-a2ba-270351f72aa3"
    }
}

/**
 * Hand-rolled encoders/decoders for the playit binary protocol. All integers
 * are big-endian; see the `message-encoding` crate and `agent_proto` package
 * of playit-agent for the authoritative definitions.
 */
object PlayitMessages {

    // ControlRequest ids (ControlRequestId enum, per playit-agent's
    // control_messages.rs; ids are 1-based: _PingV1=1, AgentRegisterV1=2,
    // AgentKeepAliveV1=3, SetupUdpChannelV1=4, AgentCheckPortMappingV1=5,
    // PingV2=6). Sending 5 pings as AgentCheckPortMapping and is dropped.
    private const val REQ_PING_V2 = 6
    private const val REQ_AGENT_KEEP_ALIVE_V1 = 3

    // ControlResponse ids (ControlResponse enum).
    private const val RESP_PONG = 1
    private const val RESP_UNAUTHORIZED = 3
    private const val RESP_AGENT_REGISTERED = 6

    // ControlFeed ids (ControlFeed enum).
    private const val FEED_RESPONSE = 1
    private const val FEED_NEW_CLIENT = 3

    class Buffer(val bytes: ByteArray) {
        var pos: Int = 0
        val remaining: Int get() = bytes.size - pos
        fun u8(): Int {
            require(pos < bytes.size) { "Truncated playit message" }
            return bytes[pos++].toInt() and 0xFF
        }
        fun u16(): Int {
            require(pos + 2 <= bytes.size) { "Truncated playit message" }
            val v = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }
        fun u32(): Long {
            require(pos + 4 <= bytes.size) { "Truncated playit message" }
            var v = 0L
            repeat(4) { v = (v shl 8) or (bytes[pos + it].toLong() and 0xFF) }
            pos += 4
            return v
        }
        fun u64(): Long {
            require(pos + 8 <= bytes.size) { "Truncated playit message" }
            var v = 0L
            repeat(8) { v = (v shl 8) or (bytes[pos + it].toLong() and 0xFF) }
            pos += 8
            return v
        }
        fun bytes(count: Int): ByteArray {
            require(pos + count <= bytes.size) { "Truncated playit message" }
            val out = bytes.copyOfRange(pos, pos + count)
            pos += count
            return out
        }
    }

    class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) { out.write(v) }
        fun u16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) { u16((v shr 16) and 0xFFFF); u16(v and 0xFFFF) }
        fun u32(v: Long) { u32(v.toInt()) }
        fun u64(v: Long) {
            for (shift in 56 downTo 8 step 8) out.write(((v shr shift) and 0xFF).toInt())
            out.write((v and 0xFF).toInt())
        }
        fun bytes(data: ByteArray) { out.write(data) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** RPC envelope: u64 request_id followed by the payload. */
    class ControlRpc(val requestId: Long, val payload: ByteArray) {
        fun encode(): ByteArray = Writer().also { it.u64(requestId); it.bytes(payload) }.toByteArray()
        companion object {
            fun decode(bytes: ByteArray): ControlRpc {
                val buffer = Buffer(bytes)
                return ControlRpc(buffer.u64(), buffer.bytes(buffer.remaining))
            }
        }
    }

    data class AgentSessionId(val sessionId: Long, val accountId: Long, val agentId: Long) {
        fun encode(): ByteArray = Writer().also { it.u64(sessionId); it.u64(accountId); it.u64(agentId) }.toByteArray()
        companion object {
            fun decode(buffer: Buffer) = AgentSessionId(buffer.u64(), buffer.u64(), buffer.u64())
        }
    }

    data class Pong(
        val clientAddr: InetSocketAddress,
        val tunnelAddr: InetSocketAddress,
        val serverNow: Long
    )

    data class NewClient(
        val tunnelId: Long,
        val claimAddress: InetSocketAddress?,
        val claimToken: ByteArray,
        val peerAddr: InetSocketAddress?
    )

    sealed class ControlFeed {
        data class Response(val rpc: ControlRpc) : ControlFeed()
        data class NewClient(val client: PlayitMessages.NewClient) : ControlFeed()
    }

    sealed class ControlResponse {
        object Unauthorized : ControlResponse()
        data class AgentRegistered(val id: AgentSessionId) : ControlResponse()
        data class Pong(val pong: PlayitMessages.Pong) : ControlResponse()
    }

    data class SignedAgentRegister(val bytes: ByteArray)

    object AgentKeepAlive {
        fun encode(id: AgentSessionId): ByteArray = Writer()
            .also { it.u32(REQ_AGENT_KEEP_ALIVE_V1); it.bytes(id.encode()) }
            .toByteArray()
    }

    object Ping {
        fun encode(now: Long): ByteArray = Writer()
            .also {
                it.u32(REQ_PING_V2)
                it.u64(now)
                it.u8(0) // Option<u32> current_ping: None
                it.u8(0) // Option<AgentSessionId> session_id: None
            }
            .toByteArray()
    }

    /**
     * Parses one ControlFeed datagram; returns null for unhandled kinds or
     * malformed payloads so a garbage datagram can never crash the loop.
     */
    fun readFeed(bytes: ByteArray, length: Int = bytes.size): ControlFeed? {
        val buffer = Buffer(bytes.copyOf(length))
        return when (buffer.u32().toInt()) {
            FEED_RESPONSE -> ControlFeed.Response(ControlRpc.decode(buffer.bytes(buffer.remaining)))
            FEED_NEW_CLIENT -> runCatching { ControlFeed.NewClient(readNewClient(buffer)) }.getOrNull()
            else -> null
        }
    }

    private fun readNewClient(buffer: Buffer): NewClient {
        val connectAddr = readSocketAddress(buffer)
        val peerAddr = readSocketAddress(buffer)
        buffer.u32() // data_center_id
        val tunnelId = buffer.u64()
        buffer.u16() // port_offset
        val claimAddr = readSocketAddress(buffer)
        val token = readVec(buffer)
        return NewClient(
            tunnelId = tunnelId,
            claimAddress = claimAddr,
            claimToken = token,
            peerAddr = peerAddr
        )
    }

    fun readResponse(rpc: ControlRpc): ControlResponse? {
        val buffer = Buffer(rpc.payload)
        return when (buffer.u32().toInt()) {
            RESP_PONG -> {
                buffer.u64() // request_now
                val serverNow = buffer.u64()
                buffer.u64() // server_id
                buffer.u32() // data_center_id
                val clientAddr = readSocketAddress(buffer) ?: return null
                val tunnelAddr = readSocketAddress(buffer) ?: return null
                if (buffer.remaining > 0 && buffer.u8() == 1) buffer.u64() // session_expire_at: Some
                ControlResponse.Pong(Pong(clientAddr, tunnelAddr, serverNow))
            }
            RESP_UNAUTHORIZED -> ControlResponse.Unauthorized
            RESP_AGENT_REGISTERED -> ControlResponse.AgentRegistered(AgentSessionId.decode(buffer))
            else -> null
        }
    }

    private fun readSocketAddress(buffer: Buffer): InetSocketAddress? = runCatching {
        when (val kind = buffer.u8()) {
            4 -> {
                val ip = InetAddress.getByAddress(buffer.bytes(4))
                InetSocketAddress(ip, buffer.u16())
            }
            6 -> {
                val ip = InetAddress.getByAddress(buffer.bytes(16))
                InetSocketAddress(ip, buffer.u16())
            }
            else -> throw IOException("Invalid playit address kind $kind")
        }
    }.getOrNull()

    private fun readVec(buffer: Buffer): ByteArray {
        val length = buffer.u64()
        require(length >= 0 && length <= buffer.remaining) { "Oversized playit vector" }
        return buffer.bytes(length.toInt())
    }
}
