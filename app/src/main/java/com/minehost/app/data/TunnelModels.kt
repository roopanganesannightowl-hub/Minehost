package com.minehost.app.data

enum class TunnelStatus {
    /** Not running and not requested. */
    IDLE,

    /** Connecting to the relay and registering the local port. */
    CONNECTING,

    /** Registered; players can reach [TunnelState.publicEndpoint]. */
    ACTIVE,

    /** The relay dropped; retrying with backoff. */
    RECONNECTING,

    /** Gave up after repeated failures; [TunnelState.error] says why. */
    FAILED
}

data class TunnelState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    /** Relay host the tunnel points at, e.g. bore.pub. */
    val host: String = "",
    /** Public TCP port assigned by the relay, 0 until known. */
    val publicPort: Int = 0,
    /** Local Minecraft port traffic is forwarded to. */
    val localPort: Int = 0,
    val error: String? = null,
    val connectedAt: Long? = null
) {
    /** Address friends enter in Minecraft once the tunnel is active. */
    val publicEndpoint: String?
        get() = if (status == TunnelStatus.ACTIVE && publicPort > 0) "$host:$publicPort" else null

    val isActive: Boolean
        get() = status == TunnelStatus.ACTIVE || status == TunnelStatus.CONNECTING || status == TunnelStatus.RECONNECTING
}

/**
 * State of the playit.gg tunnel. Reuses [TunnelStatus] because the lifecycle
 * is identical to the bore relay; the public endpoint is the persistent
 * `*.at.ply.gg` address handed out by the playit network instead of a
 * relay-assigned port.
 */
data class PlayitTunnelState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    /** Public endpoint friends join with, e.g. `fast-gl.at.ply.gg:1234`. */
    val publicEndpoint: String? = null,
    val error: String? = null,
    val connectedAt: Long? = null
) {
    val isActive: Boolean
        get() = status == TunnelStatus.ACTIVE || status == TunnelStatus.CONNECTING || status == TunnelStatus.RECONNECTING
}
