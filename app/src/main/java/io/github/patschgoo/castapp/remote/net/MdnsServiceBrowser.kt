package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.discovery.Dns
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** One DNS-SD service instance. */
class MdnsService(val instance: String, val host: InetAddress, val port: Int, val txt: Map<String, String>) {
    /** The instance name without the service type, e.g. "Living Room TV". */
    val name: String get() = instance.substringBefore("._")
}

/**
 * A small multicast DNS browser for one service type (here `_androidtvremote2._tcp`):
 * sends queries from a random port (answers come back by unicast) and also listens
 * on port 5353 next to the app's other mDNS browser.
 */
class MdnsServiceBrowser(private val service: String, private val onFound: (MdnsService) -> Unit) {
    private val group: InetAddress = InetAddress.getByName("224.0.0.251")
    @Volatile private var running = false
    private val sockets = ArrayList<MulticastSocket>()

    fun start(ifaces: List<NetworkInterface>) {
        if (running) return
        running = true
        try {
            val query = MulticastSocket(0)
            query.timeToLive = 255
            ifaces.firstOrNull()?.let { try { query.networkInterface = it } catch (_: Exception) {} }
            query.soTimeout = 1000
            synchronized(sockets) { sockets.add(query) }
            thread("mdns-remote-query") { queryLoop(query, ifaces) }
            thread("mdns-remote-unicast") { receiveLoop(query) }
        } catch (_: Exception) {
        }
        try {
            val listen = MulticastSocket(null as java.net.SocketAddress?)
            listen.reuseAddress = true
            listen.bind(InetSocketAddress(5353))
            listen.soTimeout = 1000
            var joined = false
            for (iface in ifaces) {
                try { listen.joinGroup(InetSocketAddress(group, 5353), iface); joined = true } catch (_: Exception) {}
            }
            if (!joined) listen.joinGroup(InetSocketAddress(group, 5353), null)
            synchronized(sockets) { sockets.add(listen) }
            thread("mdns-remote-listen") { receiveLoop(listen) }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        synchronized(sockets) {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            sockets.clear()
        }
    }

    private fun queryLoop(socket: MulticastSocket, ifaces: List<NetworkInterface>) {
        val delays = longArrayOf(0, 1000, 2000, 4000)
        var i = 0
        while (running) {
            val data = Dns.buildQuery(service, Dns.TYPE_PTR, unicastResponse = true)
            val packet = DatagramPacket(data, data.size, group, 5353)
            if (ifaces.size <= 1) {
                try { socket.send(packet) } catch (_: Exception) {}
            } else {
                for (iface in ifaces) {
                    try { socket.networkInterface = iface; socket.send(packet) } catch (_: Exception) {}
                }
            }
            val delay = if (i < delays.size - 1) delays[++i] else 15_000L
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
        }
    }

    private fun receiveLoop(socket: MulticastSocket) {
        val buf = ByteArray(9000)
        while (running && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                parse(service, Dns.parse(buf, packet.length), packet.address).forEach(onFound)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (!running) return
            }
        }
    }

    private fun thread(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }

    companion object {
        /** Service instances of [service] in a set of DNS records. */
        fun parse(service: String, records: List<Dns.Record>, source: InetAddress?): List<MdnsService> {
            val instances = LinkedHashSet<String>()
            for (r in records) {
                if (r.type == Dns.TYPE_PTR && r.name.equals(service, true)) (r.data as? String)?.let { instances.add(it) }
                if ((r.type == Dns.TYPE_SRV || r.type == Dns.TYPE_TXT) && r.name.endsWith(".$service", true)) instances.add(r.name)
            }
            return instances.mapNotNull { instance ->
                val srv = records.firstOrNull { it.type == Dns.TYPE_SRV && it.name.equals(instance, true) }?.data as? Dns.Srv
                @Suppress("UNCHECKED_CAST")
                val txt = records.firstOrNull { it.type == Dns.TYPE_TXT && it.name.equals(instance, true) }?.data as? Map<String, String>
                val a = srv?.let { s -> records.firstOrNull { it.type == Dns.TYPE_A && it.name.equals(s.target, true) }?.data as? InetAddress }
                val host = a ?: source ?: return@mapNotNull null
                MdnsService(instance, host, srv?.port ?: 0, txt ?: emptyMap())
            }
        }
    }
}
