package io.github.patschgoo.castapp.dlna

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Finds DLNA media renderers (smart TVs) with SSDP M-SEARCH. */
class SsdpBrowser(private val onDevice: (CastDevice) -> Unit) {
    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val fetcher = Executors.newFixedThreadPool(4) { r -> Thread(r, "ssdp-describe").apply { isDaemon = true } }

    fun start(ifaces: List<NetworkInterface>) {
        if (running) return
        running = true
        val s = try {
            MulticastSocket(0).apply {
                timeToLive = 4
                soTimeout = 1000
                ifaces.firstOrNull()?.let { try { networkInterface = it } catch (_: Exception) {} }
            }
        } catch (_: Exception) {
            running = false
            return
        }
        socket = s
        Thread({ receiveLoop(s) }, "ssdp-receive").apply { isDaemon = true; start() }
        Thread({ searchLoop(s) }, "ssdp-search").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    /** Forget what was found so a refresh reports everything again. */
    fun searchAgain() {
        seen.clear()
        socket?.let { send(it) }
    }

    private fun searchLoop(s: MulticastSocket) {
        val delays = longArrayOf(0, 1500, 4000, 15_000)
        var i = 0
        while (running) {
            send(s)
            try { Thread.sleep(if (i < delays.size - 1) delays[++i] else 20_000) } catch (_: InterruptedException) { return }
        }
    }

    private fun send(s: MulticastSocket) {
        val data = Dlna.searchMessage().toByteArray()
        try {
            s.send(DatagramPacket(data, data.size, InetAddress.getByName(Dlna.SSDP_ADDRESS), Dlna.SSDP_PORT))
        } catch (_: Exception) {
        }
    }

    private fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(4096)
        while (running && !s.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val headers = Dlna.parseSsdp(String(buf, 0, packet.length, Charsets.UTF_8))
                val location = headers["location"] ?: continue
                if (!seen.add(location)) continue
                fetcher.execute { describe(location) }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (!running) return
            }
        }
    }

    private fun describe(location: String) {
        val xml = Dlna.fetch(location) ?: run { seen.remove(location); return }
        val d = Dlna.parseDescription(xml, location)
        if (!d.isRenderer || d.avTransportUrl == null) return
        val uri = try { URI(location) } catch (_: Exception) { return }
        val host = uri.host ?: return
        onDevice(
            CastDevice(
                id = "dlna:" + (d.udn ?: "$host:${uri.port}"),
                name = d.friendlyName,
                host = host,
                port = if (uri.port > 0) uri.port else 80,
                model = listOfNotNull(d.manufacturer, d.modelName).joinToString(" ").ifEmpty { null },
                kind = DeviceKind.DLNA,
                avTransportUrl = d.avTransportUrl,
                renderingControlUrl = d.renderingControlUrl,
            ),
        )
    }
}
