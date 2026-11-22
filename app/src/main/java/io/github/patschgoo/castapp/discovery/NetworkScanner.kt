package io.github.patschgoo.castapp.discovery

import android.annotation.SuppressLint
import io.github.patschgoo.castapp.cast.CastClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** A local IPv4 network the phone is attached to (Wi-Fi, hotspot, Ethernet). */
class LocalNetwork(val iface: NetworkInterface, val address: Inet4Address, val prefixLength: Int)

/**
 * The fallback that makes devices show up even when multicast discovery is
 * broken on the router: probe every address of the local subnet for an open
 * Cast port and ask each hit for its name.
 */
object NetworkScanner {
    private const val CONNECT_TIMEOUT_MS = 450
    private const val PARALLELISM = 64

    fun localNetworks(): List<LocalNetwork> {
        val result = ArrayList<LocalNetwork>()
        val ifaces = try { NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        for (iface in ifaces) {
            try {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                    name.startsWith("dummy") || name.startsWith("ppp") || name.startsWith("v4-rmnet")
                ) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    if (!addr.isSiteLocalAddress) continue
                    result.add(LocalNetwork(iface, addr, ia.networkPrefixLength.toInt()))
                }
            } catch (_: Exception) {
            }
        }
        // Wi-Fi first, then hotspot, then the rest.
        return result.sortedBy {
            val n = it.iface.name.lowercase()
            when {
                n.startsWith("wlan") -> 0
                n.startsWith("swlan") || n.startsWith("ap") -> 1
                n.startsWith("eth") -> 2
                else -> 3
            }
        }
    }

    /** All host addresses of [net], limited to the /24 around the phone for very large networks. */
    fun hostsOf(net: LocalNetwork): List<InetAddress> = hostsOf(net.address, net.prefixLength)

    fun hostsOf(address: Inet4Address, prefixLength: Int): List<InetAddress> {
        val prefix = if (prefixLength in 22..30) prefixLength else 24
        val ip = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val hosts = ArrayList<InetAddress>()
        var h = network + 1
        while (h < broadcast) {
            if (h != ip) {
                hosts.add(InetAddress.getByAddress(byteArrayOf((h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte())))
            }
            h++
        }
        return hosts
    }

    fun isPortOpen(host: InetAddress, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    /** Scans [net] and reports every Cast device found through [onFound]. */
    suspend fun scan(net: LocalNetwork, onFound: (CastDevice) -> Unit) = coroutineScope {
        val gate = Semaphore(PARALLELISM)
        hostsOf(net).map { host ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    if (isPortOpen(host, CastClient.DEFAULT_PORT)) identify(host.hostAddress!!)?.let(onFound)
                }
            }
        }.awaitAll()
        Unit
    }

    /** Scans every local network for the device with [id] (used when a TV got a new IP address). */
    suspend fun findById(id: String): CastDevice? {
        var match: CastDevice? = null
        for (net in localNetworks().distinctBy { it.address }) {
            scan(net) { d -> if (d.id == id) match = d }
            if (match != null) break
        }
        return match
    }

    /**
     * Works out what is behind [ip]: asks the device's own mDNS responder, then the
     * setup API, then falls back to a plain Cast handshake. Returns null when it is
     * not a Cast receiver.
     */
    suspend fun identify(ip: String, port: Int = CastClient.DEFAULT_PORT): CastDevice? = withContext(Dispatchers.IO) {
        val address = try { InetAddress.getByName(ip) } catch (_: Exception) { return@withContext null }
        unicastMdns(address)?.let { info ->
            val device = info.toDevice() ?: return@withContext null
            return@withContext device.copy(host = ip, port = if (info.port > 0) info.port else port)
        }
        eurekaInfo(ip)?.let { return@withContext it.copy(port = port) }
        if (castHandshake(ip, port)) CastDevice(id = "host:$ip", name = "Cast device $ip", host = ip, port = port) else null
    }

    /** Sends a DNS-SD query straight to the device's mDNS port (no multicast needed). */
    fun unicastMdns(address: InetAddress, timeoutMs: Int = 700): MdnsCastInfo? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val query = Dns.buildQuery(MdnsCastInfo.SERVICE, Dns.TYPE_PTR, unicastResponse = true, id = 0x4341)
            socket.send(DatagramPacket(query, query.size, address, 5353))
            val buf = ByteArray(9000)
            val deadline = System.currentTimeMillis() + timeoutMs
            var found: MdnsCastInfo? = null
            while (found == null && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                found = MdnsCastInfo.fromRecords(Dns.parse(buf, packet.length), packet.address)
                    .firstOrNull { it.address == null || it.address == address || packet.address == address }
                    ?.let { MdnsCastInfo(it.instance, address, it.port, it.txt) }
            }
            found
        }
    } catch (_: Exception) {
        null
    }

    private fun eurekaInfo(ip: String): CastDevice? {
        val path = "/setup/eureka_info?params=name,device_info"
        val body = simpleHttpGet(ip, 8008, path, tls = false) ?: simpleHttpGet(ip, 8443, path, tls = true) ?: return null
        return try {
            val json = JSONObject(body)
            val name = json.optString("name").ifEmpty { return null }
            val info = json.optJSONObject("device_info")
            val model = info?.optString("model_name")?.ifEmpty { null }
            val udn = info?.optString("ssdp_udn")?.replace("-", "")?.ifEmpty { null }
            CastDevice(id = udn ?: "host:$ip", name = name, host = ip, model = model)
        } catch (_: Exception) {
            null
        }
    }

    private fun castHandshake(ip: String, port: Int): Boolean {
        val client = CastClient(ip, port)
        return try {
            client.connect(2000)
            kotlinx.coroutines.runBlocking { withTimeoutOrNull(2500) { client.getReceiverStatus(2500) } } != null
        } catch (_: Exception) {
            false
        } finally {
            client.close()
        }
    }

    /** Minimal HTTP GET over a raw socket (keeps the app free of cleartext-HTTP exceptions). */
    fun simpleHttpGet(host: String, port: Int, path: String, tls: Boolean, timeoutMs: Int = 1500): String? = try {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        val socket = if (tls) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).also { it.soTimeout = timeoutMs; it.startHandshake() }
        } else raw
        socket.use { s ->
            s.soTimeout = timeoutMs
            val req = "GET $path HTTP/1.0\r\nHost: $host:$port\r\nAccept: application/json\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(req.toByteArray()); flush() }
            val out = ByteArrayOutputStream()
            val input = s.getInputStream()
            val buf = ByteArray(4096)
            while (out.size() < 256 * 1024) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            val text = out.toString(Charsets.UTF_8.name())
            val status = text.substringBefore("\r\n")
            if (!status.contains(" 200")) null else text.substringAfter("\r\n\r\n", "")
        }
    } catch (_: Exception) {
        null
    }

    // Cast receivers present self-signed certificates and the connection never leaves the
    // local network, so (like other Cast senders) we do not validate them against a CA.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
