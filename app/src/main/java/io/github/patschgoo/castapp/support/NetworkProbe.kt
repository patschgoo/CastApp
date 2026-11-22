package io.github.patschgoo.castapp.support

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.discovery.Dns
import io.github.patschgoo.castapp.discovery.MdnsAirPlayInfo
import io.github.patschgoo.castapp.discovery.MdnsCastInfo
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.dlna.Dlna
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket

/** Takes the measurements of the network check on the phone. */
object NetworkProbe {
    private const val LISTEN_MS = 3500L

    suspend fun measure(context: Context): NetworkMeasurements = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val (transport0, vpn) = transportOf(cm)
        val nets = NetworkScanner.localNetworks()
        val primary = nets.firstOrNull()
        // A hotspot or an unusual interface has no Wi-Fi network of its own but still a local address.
        val transport = if (transport0 == Transport.NONE || transport0 == Transport.CELLULAR) {
            if (primary != null && transport0 == Transport.NONE) Transport.OTHER_LOCAL else transport0
        } else transport0

        val repo = CastApp.get(app).devices
        val saved = repo.devices.value.filter { it.saved }

        var lock: WifiManager.MulticastLock? = null
        try {
            lock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("CastApp-check").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }

        val result = coroutineScope {
            val mdns = async { if (primary != null) mdnsReplies(primary.iface) else 0 }
            val ssdp = async { if (primary != null) ssdpReplies(primary.iface) else 0 }
            val router = async { gateway(cm)?.let { routerAnswers(it) } }
            val savedChecks = saved.map { e ->
                async {
                    val ok = try { NetworkScanner.isPortOpen(InetAddress.getByName(e.device.host), e.device.port, 1500) } catch (_: Exception) { false }
                    SavedTvCheck(e.device.name, e.device.host, ok)
                }
            }
            NetworkMeasurements(
                transport = transport,
                vpnActive = vpn,
                phoneAddress = primary?.address?.hostAddress,
                prefixLength = primary?.prefixLength ?: 24,
                routerAnswered = router.await(),
                mdnsReplies = mdns.await(),
                ssdpReplies = ssdp.await(),
                savedTvs = savedChecks.awaitAll(),
            )
        }
        try { lock?.release() } catch (_: Exception) {}

        // Devices the app's own discovery has found by now (it keeps running while the screen is open).
        val found = repo.devices.value.filter { it.online }.map { it.device.name }.distinct()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        result.copy(
            foundDevices = found,
            dataSaver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            batterySaver = pm.isPowerSaveMode,
        )
    }

    @Suppress("DEPRECATION")
    private fun transportOf(cm: ConnectivityManager): Pair<Transport, Boolean> {
        var wifi = false
        var eth = false
        var cell = false
        var vpn = false
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { vpn = true; continue }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) eth = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
        }
        val t = when {
            wifi -> Transport.WIFI
            eth -> Transport.ETHERNET
            cell -> Transport.CELLULAR
            else -> Transport.NONE
        }
        return t to vpn
    }

    /** The router's address from the default route of the Wi-Fi/Ethernet network. */
    @Suppress("DEPRECATION")
    private fun gateway(cm: ConnectivityManager): InetAddress? {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
            val props = cm.getLinkProperties(n) ?: continue
            props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.let { return it }
        }
        return null
    }

    /** A router answers if it accepts or actively refuses a connection (a silent drop is a timeout). */
    private suspend fun routerAnswers(gateway: InetAddress): Boolean = coroutineScope {
        val tries = listOf(80, 443, 53).map { port ->
            async(Dispatchers.IO) {
                try {
                    Socket().use { it.connect(InetSocketAddress(gateway, port), 1200) }
                    true
                } catch (e: ConnectException) {
                    // "Connection refused" still means something answered.
                    e.message?.contains("refused", ignoreCase = true) == true
                } catch (_: Exception) {
                    false
                }
            }
        }
        tries.awaitAll().any { it } || try { gateway.isReachable(1200) } catch (_: Exception) { false }
    }

    private fun localAddresses(): Set<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() }?.toSet() ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    /** Sends mDNS questions and counts the hosts that answer within a few seconds. */
    private fun mdnsReplies(iface: NetworkInterface): Int {
        val own = localAddresses()
        val sources = HashSet<InetAddress>()
        try {
            MulticastSocket(0).use { socket ->
                socket.timeToLive = 255
                try { socket.networkInterface = iface } catch (_: Exception) {}
                socket.soTimeout = 500
                val group = InetAddress.getByName("224.0.0.251")
                for (name in listOf(MdnsCastInfo.SERVICE, MdnsAirPlayInfo.SERVICE, "_services._dns-sd._udp.local")) {
                    val q = Dns.buildQuery(name, Dns.TYPE_PTR, unicastResponse = true)
                    try { socket.send(DatagramPacket(q, q.size, group, 5353)) } catch (_: Exception) {}
                }
                val buf = ByteArray(9000)
                val end = System.currentTimeMillis() + LISTEN_MS
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        if (p.address !in own) sources.add(p.address)
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return sources.size
    }

    /** Sends an SSDP search for everything and counts the hosts that answer within a few seconds. */
    private fun ssdpReplies(iface: NetworkInterface): Int {
        val own = localAddresses()
        val sources = HashSet<InetAddress>()
        try {
            MulticastSocket(0).use { socket ->
                socket.timeToLive = 4
                try { socket.networkInterface = iface } catch (_: Exception) {}
                socket.soTimeout = 500
                val data = Dlna.searchMessage("ssdp:all").toByteArray()
                val group = InetAddress.getByName(Dlna.SSDP_ADDRESS)
                repeat(2) { try { socket.send(DatagramPacket(data, data.size, group, Dlna.SSDP_PORT)) } catch (_: Exception) {} }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + LISTEN_MS
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        if (p.address !in own) sources.add(p.address)
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return sources.size
    }
}
