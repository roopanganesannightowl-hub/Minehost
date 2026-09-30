package com.minehost.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.minehost.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

private const val SSDP_ADDRESS = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val NAT_PMP_PORT = 5351
private const val MULTICAST_LOCK_TAG = "minehost-router-check"
private const val MAX_HTTP_RESPONSE_BYTES = 1_000_000
private const val PORT_MAPPING_LEASE_SECONDS = 3600

private val IPV4_PATTERN = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

enum class RouterNetworkType {
    WIFI,
    MOBILE,
    OTHER,
    NONE;

    val label: String
        get() = when (this) {
            WIFI -> "Wi-Fi"
            MOBILE -> "Mobile data"
            OTHER -> "Other network"
            NONE -> "No active network"
        }
}

enum class CgnatState {
    UNLIKELY,
    LIKELY,
    UNKNOWN;

    val label: String
        get() = when (this) {
            UNLIKELY -> "Not detected"
            LIKELY -> "Likely"
            UNKNOWN -> "Unknown"
        }
}

data class RouterCheckResult(
    val serverPort: Int,
    val networkType: RouterNetworkType = RouterNetworkType.NONE,
    val hasInternet: Boolean = false,
    val internetValidated: Boolean = false,
    val localIpv4: String? = null,
    val localIpv6: String? = null,
    val gatewayIpv4: String? = null,
    val publicIpv4: String? = null,
    val publicIpv6: String? = null,
    val routerExternalIpv4: String? = null,
    val cgnatState: CgnatState = CgnatState.UNKNOWN,
    val mappingMethod: String? = null,
    val mappingActive: Boolean = false,
    val mappingMessage: String? = null,
    val portReachable: Boolean? = null,
    val errorMessage: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
    /** Tailscale tailnet address, present only while the Tailscale app is up. */
    val tailnetIpv4: String? = null
) {
    val canRequestMapping: Boolean
        get() = networkType == RouterNetworkType.WIFI &&
            !localIpv4.isNullOrBlank() &&
            !gatewayIpv4.isNullOrBlank()

    val tailnetEndpoint: String?
        get() = tailnetIpv4?.let { "$it:$serverPort" }

    val publicEndpoint: String?
        get() = publicIpv4?.let { "$it:$serverPort" }

    val ipv6Endpoint: String?
        get() = publicIpv6?.let { "[$it]:$serverPort" }
}

data class RouterMappingResult(
    val success: Boolean,
    val method: String? = null,
    val message: String
)

private data class LocalNetworkInfo(
    val type: RouterNetworkType,
    val hasInternet: Boolean,
    val internetValidated: Boolean,
    val localIpv4: String?,
    val localIpv6: String?,
    val gatewayIpv4: String?
)

private data class UpnpDevice(
    val serviceType: String,
    val controlUrl: String
)

private data class NatPmpReply(
    val success: Boolean,
    val resultCode: Int = 0,
    val externalIpv4: String? = null,
    val lifetimeSeconds: Long? = null
)

/**
 * Small, dependency-free router doctor. It never changes router settings during inspection.
 * Port mapping is only sent after the user explicitly presses the mapping action.
 */
class RouterDiagnosticsManager(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lastUpnpDevice: UpnpDevice? = null
    private var lastNatPmpGateway: String? = null

    suspend fun inspect(config: ServerConfig, serverRunning: Boolean): RouterCheckResult =
        withContext(Dispatchers.IO) {
            val local = readLocalNetwork()
            val publicIpv4 = if (local.hasInternet) fetchPublicAddress(IPV4_URL) else null
            val publicIpv6 = if (local.hasInternet) fetchPublicAddress(IPV6_URL) else null

            val upnp = discoverUpnp(local.gatewayIpv4)
            lastUpnpDevice = upnp
            val upnpExternalIp = upnp?.let { readUpnpExternalAddress(it) }
            val natPmpIp = if (upnpExternalIp == null) {
                local.gatewayIpv4?.let { readNatPmpExternalAddress(it) }
            } else {
                null
            }
            if (natPmpIp != null) lastNatPmpGateway = local.gatewayIpv4

            val routerExternalIp = upnpExternalIp ?: natPmpIp
            val mappingMethod = when {
                upnp != null -> "UPnP"
                natPmpIp != null -> "NAT-PMP"
                else -> null
            }
            val portReachable = if (serverRunning) {
                isPortReachable(local.localIpv4 ?: "127.0.0.1", config.port)
            } else {
                null
            }

            RouterCheckResult(
                serverPort = config.port,
                networkType = local.type,
                hasInternet = local.hasInternet,
                internetValidated = local.internetValidated,
                localIpv4 = local.localIpv4,
                localIpv6 = local.localIpv6,
                gatewayIpv4 = local.gatewayIpv4,
                publicIpv4 = publicIpv4,
                publicIpv6 = publicIpv6,
                routerExternalIpv4 = routerExternalIp,
                cgnatState = classifyCgnat(local, publicIpv4, routerExternalIp),
                mappingMethod = mappingMethod,
                portReachable = portReachable,
                errorMessage = if (local.hasInternet) null else "No active internet connection",
                tailnetIpv4 = NetworkUtils.tailnetIpv4Address()
            )
        }

    fun requestPortMapping(result: RouterCheckResult): RouterMappingResult {
        val localIp = result.localIpv4
        val gateway = result.gatewayIpv4
        if (result.networkType != RouterNetworkType.WIFI || localIp.isNullOrBlank() || gateway.isNullOrBlank()) {
            return RouterMappingResult(
                success = false,
                message = "Connect MineHost to Wi-Fi before requesting a router port mapping"
            )
        }

        val upnp = lastUpnpDevice
        if (upnp != null) {
            val response = addUpnpPortMapping(upnp, localIp, result.serverPort)
            if (response) {
                lastNatPmpGateway = gateway
                return RouterMappingResult(
                    success = true,
                    method = "UPnP",
                    message = "Router port ${result.serverPort} mapped through UPnP"
                )
            }
        }

        val natReply = addNatPmpMapping(gateway, result.serverPort)
        if (natReply?.success == true) {
            lastNatPmpGateway = gateway
            return RouterMappingResult(
                success = true,
                method = "NAT-PMP",
                message = "Router port ${result.serverPort} mapped through NAT-PMP"
            )
        }

        val upnpMessage = if (upnp != null) {
            "UPnP was detected but the router rejected the mapping"
        } else {
            "No UPnP or NAT-PMP mapping service responded"
        }
        return RouterMappingResult(
            success = false,
            method = mappingMethodLabel(result.mappingMethod),
            message = "$upnpMessage. ${natPmpResultMessage(natReply?.resultCode ?: 8)}"
        )
    }

    fun removePortMapping(result: RouterCheckResult): RouterMappingResult {
        val localIp = result.localIpv4
        val gateway = result.gatewayIpv4 ?: lastNatPmpGateway
        if (localIp.isNullOrBlank() || gateway.isNullOrBlank()) {
            return RouterMappingResult(false, message = "No router mapping information is available")
        }

        val upnp = lastUpnpDevice
        if (upnp != null && removeUpnpPortMapping(upnp, result.serverPort)) {
            return RouterMappingResult(true, method = "UPnP", message = "Router port mapping removed")
        }

        val natReply = removeNatPmpMapping(gateway, result.serverPort)
        return if (natReply?.success == true) {
            RouterMappingResult(true, method = "NAT-PMP", message = "Router port mapping removed")
        } else {
            RouterMappingResult(
                success = false,
                method = mappingMethodLabel(result.mappingMethod),
                message = "MineHost could not remove the router mapping automatically"
            )
        }
    }

    private fun readLocalNetwork(): LocalNetworkInfo {
        val activeNetwork = connectivityManager.activeNetwork
        val activeCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        // With a VPN such as Tailscale connected, the LAN details live on the
        // underlying Wi-Fi or cellular network rather than on the tunnel.
        val onVpn = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val network = if (onVpn) {
            // Synchronous one-shot enumeration: the registerNetworkCallback API
            // is async-only and this inspect already runs on Dispatchers.IO.
            // Deprecated on API 31+ but still the only way to cover API 26-30.
            @Suppress("DEPRECATION")
            val candidates = connectivityManager.allNetworks
            candidates.firstOrNull { candidate ->
                val caps = connectivityManager.getNetworkCapabilities(candidate)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            } ?: activeNetwork
        } else {
            activeNetwork
        }
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProperties: LinkProperties? = network?.let { connectivityManager.getLinkProperties(it) }
        val type = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> RouterNetworkType.WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> RouterNetworkType.MOBILE
            capabilities == null -> RouterNetworkType.NONE
            else -> RouterNetworkType.OTHER
        }
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val addresses = linkProperties?.linkAddresses.orEmpty().map { it.address }
        val localIpv4 = addresses.asSequence()
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            // A tailnet address is not a LAN address and cannot be forwarded.
            .firstOrNull { !it.startsWith("127.") && !it.startsWith("169.254.") && !NetworkUtils.isTailnetAddress(it) }
        val localIpv6 = addresses.asSequence()
            .filterIsInstance<Inet6Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
        val routes = linkProperties?.routes.orEmpty()
        val gatewayIpv4 = routes.asSequence()
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
            ?: routes.asSequence()
                .mapNotNull { it.gateway as? Inet4Address }
                .firstOrNull()
                ?.hostAddress

        return LocalNetworkInfo(
            type = type,
            hasInternet = hasInternet,
            internetValidated = validated,
            localIpv4 = localIpv4,
            localIpv6 = localIpv6,
            gatewayIpv4 = gatewayIpv4
        )
    }

    private fun classifyCgnat(
        local: LocalNetworkInfo,
        publicIpv4: String?,
        routerExternalIpv4: String?
    ): CgnatState {
        if (local.type == RouterNetworkType.MOBILE) return CgnatState.LIKELY
        if (routerExternalIpv4 != null && isCarrierGradeNat(routerExternalIpv4)) {
            return CgnatState.LIKELY
        }
        if (publicIpv4 != null && isPrivateOrCarrierGradeIpv4(publicIpv4)) {
            return CgnatState.LIKELY
        }
        if (routerExternalIpv4 != null && !isPrivateOrCarrierGradeIpv4(routerExternalIpv4)) {
            return if (publicIpv4 == null) CgnatState.UNLIKELY else {
                if (normalizeIpv4(publicIpv4) == normalizeIpv4(routerExternalIpv4)) CgnatState.UNLIKELY else CgnatState.LIKELY
            }
        }
        if (publicIpv4 != null && routerExternalIpv4 != null) {
            return if (normalizeIpv4(publicIpv4) == normalizeIpv4(routerExternalIpv4)) {
                CgnatState.UNLIKELY
            } else {
                CgnatState.LIKELY
            }
        }
        return CgnatState.UNKNOWN
    }

    private fun isPortReachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 450)
            true
        }
    }.getOrDefault(false)

    private fun fetchPublicAddress(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 2500
        connection.readTimeout = 3000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "text/plain")
        connection.inputStream.bufferedReader().use { it.readText().trim() }
            .takeIf { isIpLiteral(it) }
    }.getOrNull()

    private fun discoverUpnp(gateway: String?): UpnpDevice? {
        val multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
        }
        var socket: java.net.MulticastSocket? = null
        return try {
            multicastLock?.acquire()
            socket = java.net.MulticastSocket(0).also { multicast ->
                multicast.reuseAddress = true
                multicast.soTimeout = 1200
                multicast.joinGroup(InetAddress.getByName(SSDP_ADDRESS))
            }
            val search = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 1\r\n")
                append("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            val multicastAddress = InetAddress.getByName(SSDP_ADDRESS)
            repeat(3) {
                socket?.send(DatagramPacket(search, search.size, multicastAddress, SSDP_PORT))
                repeat(2) {
                    val packet = DatagramPacket(ByteArray(16_384), 16_384)
                    try {
                        socket?.receive(packet)
                    } catch (_: IOException) {
                        return@repeat
                    }
                    val text = String(packet.data, 0, packet.length, StandardCharsets.ISO_8859_1)
                    val location = headerValue(text, "LOCATION") ?: return@repeat
                    if (!isLocalRouterUrl(location, gateway)) return@repeat
                    val device = readUpnpDevice(location) ?: return@repeat
                    return device
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
            runCatching { multicastLock?.release() }
        }
    }

    private fun readUpnpDevice(location: String): UpnpDevice? {
        val response = httpRequest(location, "GET", null, emptyMap()) ?: return null
        if (response.statusCode !in 200..299) return null
        val services = Regex("<service\\b[^>]*>.*?</service>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(response.body)
            .map { it.value }
            .toList()
        val service = services.firstOrNull { value ->
            val type = xmlTagValue(value, "serviceType").orEmpty()
            type.contains("WANIPConnection", ignoreCase = true) ||
                type.contains("WANPPPConnection", ignoreCase = true)
        } ?: return null
        val serviceType = xmlTagValue(service, "serviceType") ?: return null
        val control = xmlTagValue(service, "controlURL") ?: return null
        val resolved = runCatching { URL(URL(location), control).toString() }.getOrNull() ?: return null
        return UpnpDevice(serviceType, resolved)
    }

    private fun readUpnpExternalAddress(device: UpnpDevice): String? {
        val body = soapEnvelope(
            device.serviceType,
            "GetExternalIPAddress",
            "<NewExternalIPAddress></NewExternalIPAddress>"
        )
        val response = httpRequest(
            device.controlUrl,
            "POST",
            body,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPAction" to "\"${device.serviceType}#GetExternalIPAddress\""
            )
        ) ?: return null
        if (response.statusCode !in 200..299) return null
        return xmlTagValue(response.body, "NewExternalIPAddress")?.takeIf { isIpLiteral(it) }
    }

    private fun addUpnpPortMapping(device: UpnpDevice, internalIp: String, port: Int): Boolean {
        val innerXml = buildString {
            append("<NewRemoteHost></NewRemoteHost>")
            append("<NewExternalPort>$port</NewExternalPort>")
            append("<NewProtocol>TCP</NewProtocol>")
            append("<NewInternalPort>$port</NewInternalPort>")
            append("<NewInternalClient>${xmlEscape(internalIp)}</NewInternalClient>")
            append("<NewEnabled>1</NewEnabled>")
            append("<NewPortMappingDescription>MineHost Minecraft</NewPortMappingDescription>")
            append("<NewLeaseDuration>$PORT_MAPPING_LEASE_SECONDS</NewLeaseDuration>")
        }
        val body = soapEnvelope(device.serviceType, "AddPortMapping", innerXml)
        val response = httpRequest(
            device.controlUrl,
            "POST",
            body,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPAction" to "\"${device.serviceType}#AddPortMapping\""
            )
        ) ?: return false
        return response.statusCode in 200..299
    }

    private fun removeUpnpPortMapping(device: UpnpDevice, port: Int): Boolean {
        val innerXml = buildString {
            append("<NewRemoteHost></NewRemoteHost>")
            append("<NewExternalPort>$port</NewExternalPort>")
            append("<NewProtocol>TCP</NewProtocol>")
        }
        val body = soapEnvelope(device.serviceType, "DeletePortMapping", innerXml)
        val response = httpRequest(
            device.controlUrl,
            "POST",
            body,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPAction" to "\"${device.serviceType}#DeletePortMapping\""
            )
        ) ?: return false
        return response.statusCode in 200..299
    }

    private fun readNatPmpExternalAddress(gateway: String): String? {
        val reply = natPmpRequest(gateway, opcode = 0, port = null, lifetime = null, description = null)
        return reply?.takeIf { it.success }?.externalIpv4
    }

    private fun addNatPmpMapping(gateway: String, port: Int): NatPmpReply? =
        natPmpRequest(gateway, opcode = 2, port = port, lifetime = PORT_MAPPING_LEASE_SECONDS, description = "MineHost Minecraft")

    private fun removeNatPmpMapping(gateway: String, port: Int): NatPmpReply? =
        natPmpRequest(gateway, opcode = 2, port = port, lifetime = 0, description = "")

    private fun natPmpRequest(
        gateway: String,
        opcode: Int,
        port: Int?,
        lifetime: Int?,
        description: String?
    ): NatPmpReply? {
        val request = ByteArray(if (opcode == 0) 12 else 48)
        request[0] = 0
        request[1] = opcode.toByte()
        if (opcode != 0) {
            requireNotNull(port)
            requireNotNull(lifetime)
            writeInt(request, 12, port)
            writeInt(request, 16, port)
            writeInt(request, 20, lifetime)
            val descriptionBytes = (description ?: "MineHost").toByteArray(StandardCharsets.UTF_8)
            descriptionBytes.copyInto(request, 28, endIndex = minOf(descriptionBytes.size, 20))
        }

        return runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 1200
                val address = InetAddress.getByName(gateway)
                repeat(2) {
                    try {
                        socket.send(DatagramPacket(request, request.size, address, NAT_PMP_PORT))
                        val response = ByteArray(64)
                        val packet = DatagramPacket(response, response.size)
                        socket.receive(packet)
                        if (packet.length < 4) return@repeat
                        val resultCode = readShort(response, 2)
                        if (resultCode != 0) {
                            return@use NatPmpReply(false, resultCode = resultCode)
                        }
                        val external = if (packet.length >= 12 && opcode == 0) {
                            response.copyOfRange(8, 12).joinToString(".") { (it.toInt() and 0xff).toString() }
                        } else {
                            null
                        }
                        val returnedLifetime = if (opcode != 0 && packet.length >= 20) {
                            readInt(response, 16).toLong() and 0xffffffffL
                        } else {
                            null
                        }
                        return@use NatPmpReply(true, externalIpv4 = external, lifetimeSeconds = returnedLifetime)
                    } catch (_: IOException) {
                        // Try once more; some routers drop the first datagram.
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun httpRequest(
        urlString: String,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): HttpResponse? {
        val url = runCatching { URL(urlString) }.getOrNull() ?: return null
        if (!url.protocol.equals("http", ignoreCase = true)) return null
        val port = if (url.port > 0) url.port else 80
        val path = buildString {
            append(url.file.ifEmpty { "/" })
            if (url.query != null) append("?").append(url.query)
        }
        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
        val requestHeaders = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: ${url.host}:$port\r\n")
            append("Connection: close\r\n")
            append("User-Agent: MineHost/1.0\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (bodyBytes != null) append("Content-Length: ${bodyBytes.size}\r\n")
            append("\r\n")
        }
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(url.host, port), 2500)
                socket.soTimeout = 3500
                val output: OutputStream = socket.getOutputStream()
                output.write(requestHeaders.toByteArray(StandardCharsets.ISO_8859_1))
                if (bodyBytes != null) output.write(bodyBytes)
                output.flush()
                val outputBytes = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (outputBytes.size() < MAX_HTTP_RESPONSE_BYTES) {
                    val count = try {
                        socket.getInputStream().read(buffer)
                    } catch (_: SocketTimeoutException) {
                        if (outputBytes.size() > 0) break else throw SocketTimeoutException("Router response timed out")
                    }
                    if (count < 0) break
                    outputBytes.write(buffer, 0, count)
                }
                val raw = outputBytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
                val statusCode = Regex("HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@use null
                val bodyStart = raw.indexOf("\r\n\r\n").takeIf { it >= 0 }?.plus(4) ?: return@use null
                HttpResponse(statusCode, raw.substring(bodyStart))
            }
        }.getOrNull()
    }

    private fun soapEnvelope(serviceType: String, action: String, innerXml: String): String = buildString {
        append("<?xml version=\"1.0\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:$action xmlns:u=\"$serviceType\">")
        append(innerXml)
        append("</u:$action>")
        append("</s:Body></s:Envelope>")
    }

    private fun headerValue(text: String, name: String): String? =
        Regex("(?im)^$name\\s*:\\s*(.+)$").find(text)?.groupValues?.getOrNull(1)?.trim()

    private fun xmlTagValue(xml: String, tag: String): String? =
        Regex("<$tag\\s*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isLocalRouterUrl(value: String, gateway: String?): Boolean = runCatching {
        val url = URL(value)
        url.protocol.equals("http", ignoreCase = true) &&
            (isPrivateOrCarrierGradeIpv4(url.host) || (gateway != null && url.host == gateway))
    }.getOrDefault(false)

    private fun isPrivateOrCarrierGradeIpv4(value: String): Boolean {
        if (!IPV4_PATTERN.matches(value)) return false
        val parts = value.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 127 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            parts[0] == 169 && parts[1] == 254 -> true
            parts[0] == 100 && parts[1] in 64..127 -> true
            else -> false
        }
    }

    private fun isCarrierGradeNat(value: String): Boolean {
        if (!IPV4_PATTERN.matches(value)) return false
        val parts = value.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        return parts[0] == 100 && parts[1] in 64..127
    }

    private fun normalizeIpv4(value: String): String? {
        if (!IPV4_PATTERN.matches(value)) return null
        val parts = value.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.any { it !in 0..255 }) return null
        return parts.joinToString(".")
    }

    private fun isIpLiteral(value: String): Boolean {
        val trimmed = value.trim()
        if (IPV4_PATTERN.matches(trimmed)) {
            val normalized = normalizeIpv4(trimmed) ?: return false
            return !normalized.startsWith("0.")
        }
        return trimmed.contains(':') && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readShort(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun natPmpResultMessage(code: Int): String = when (code) {
        0 -> "The router accepted the mapping"
        1 -> "The router reported an unsupported NAT-PMP version"
        2 -> "The router refused the request"
        3 -> "The router reported a network failure"
        4 -> "The router is out of mapping resources"
        5 -> "The router does not support that operation"
        6 -> "The router has no available mapping resources"
        7 -> "The router does not support that option"
        8 -> "The mapping already exists"
        else -> "The router returned result code $code"
    }

    private fun mappingMethodLabel(method: String?): String? = when (method?.uppercase(Locale.US)) {
        "UPNP" -> "UPnP"
        "NAT-PMP" -> "NAT-PMP"
        else -> null
    }

    private data class HttpResponse(val statusCode: Int, val body: String)

    private companion object {
        const val IPV4_URL = "https://api.ipify.org"
        const val IPV6_URL = "https://api6.ipify.org"
    }
}
