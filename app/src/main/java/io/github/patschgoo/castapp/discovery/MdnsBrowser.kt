package io.github.patschgoo.castapp.discovery

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Our own multicast DNS browser for `_googlecast._tcp` (and `_airplay._tcp`). It sends "one-shot"
 * queries from a random port (devices answer straight back to us by unicast)
 * and also listens on port 5353 for regular multicast answers and announcements.
 * Running this next to Android's NsdManager makes discovery much more robust.
 */
class MdnsBrowser(private val onDevice: (CastDevice) -> Unit) {
    private val group: InetAddress = InetAddress.getByName("224.0.0.251")
    @Volatile private var running = false
    private val sockets = ArrayList<MulticastSocket>()
    private val threads = ArrayList<Thread>()

    fun start(ifaces: List<NetworkInterface>) {
        if (running) return
        running = true

        // Socket 1: random port, used to send queries and receive unicast replies.
        try {
            val query = MulticastSocket(0)
            query.timeToLive = 255
            ifaces.firstOrNull()?.let { try { query.networkInterface = it } catch (_: Exception) {} }
            query.soTimeout = 1000
            sockets.add(query)
            threads.add(thread("mdns-query") { queryLoop(query, ifaces) })
            threads.add(thread("mdns-unicast") { receiveLoop(query) })
        } catch (_: Exception) {
        }

        // Socket 2: the standard mDNS port, for multicast responses.
        try {
            val listen = MulticastSocket(null as java.net.SocketAddress?)
            listen.reuseAddress = true
            listen.bind(InetSocketAddress(5353))
            listen.soTimeout = 1000
            var joined = false
            for (iface in ifaces) {
                try {
                    listen.joinGroup(InetSocketAddress(group, 5353), iface)
                    joined = true
                } catch (_: Exception) {
                }
            }
            if (!joined) listen.joinGroup(InetSocketAddress(group, 5353), null)
            sockets.add(listen)
            threads.add(thread("mdns-listen") { receiveLoop(listen) })
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        synchronized(sockets) {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            sockets.clear()
        }
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    /** Sends an extra round of queries right now (e.g. when the user taps refresh). */
    fun queryNow() {
        val socket = synchronized(sockets) { sockets.firstOrNull() } ?: return
        sendQuery(socket, emptyList())
    }

    private fun queryLoop(socket: MulticastSocket, ifaces: List<NetworkInterface>) {
        // Fast at first, then slow down: 0s, 1s, 3s, 7s, then every 15s.
        val delays = longArrayOf(0, 1000, 2000, 4000)
        var i = 0
        while (running) {
            sendQuery(socket, ifaces)
            val delay = if (i < delays.size - 1) delays[++i] else 15_000L
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
        }
    }

    private fun sendQuery(socket: MulticastSocket, ifaces: List<NetworkInterface>) {
        for (service in listOf(MdnsCastInfo.SERVICE, MdnsAirPlayInfo.SERVICE)) {
            val packetData = Dns.buildQuery(service, Dns.TYPE_PTR, unicastResponse = true)
            val packet = DatagramPacket(packetData, packetData.size, group, 5353)
            if (ifaces.size <= 1) {
                try { socket.send(packet) } catch (_: Exception) {}
                continue
            }
            for (iface in ifaces) {
                try {
                    socket.networkInterface = iface
                    socket.send(packet)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun receiveLoop(socket: MulticastSocket) {
        val buf = ByteArray(9000)
        while (running && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val records = Dns.parse(buf, packet.length)
                if (records.isEmpty()) continue
                for (info in MdnsCastInfo.fromRecords(records, packet.address)) {
                    info.toDevice()?.let(onDevice)
                }
                for (info in MdnsAirPlayInfo.fromRecords(records, packet.address)) {
                    info.toDevice()?.let(onDevice)
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (!running) return
            }
        }
    }

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { isDaemon = true; start() }
}
