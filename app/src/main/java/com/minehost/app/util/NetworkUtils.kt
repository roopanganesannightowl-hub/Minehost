package com.minehost.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun localIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") && !it.startsWith("169.254.") && !isTailnetAddress(it) }
    }.getOrNull()

    /**
     * The address Tailscale gives a device on a tailnet (100.64.0.0/10).
     * It only exists while the Tailscale app is connected, and it is reachable
     * from every other device in the same tailnet without port forwarding.
     */
    fun tailnetIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { isTailnetAddress(it) }
    }.getOrNull()

    /** 100.64.0.0/10 is shared address space: carrier NAT, or a Tailscale tailnet. */
    internal fun isTailnetAddress(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val numbers = parts.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return numbers[0] == 100 && numbers[1] in 64..127
    }
}
