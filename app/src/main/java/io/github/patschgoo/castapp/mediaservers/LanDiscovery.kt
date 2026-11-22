package io.github.patschgoo.castapp.mediaservers

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/** A Plex server that answered the "G'Day Mate" broadcast. */
data class GdmServer(val name: String, val machineId: String, val host: String, val port: Int)

/** Finds Jellyfin / Emby (UDP 7359) and Plex (GDM, UDP 32414) servers on the Wi-Fi. */
object LanDiscovery {
    const val JELLYFIN_PORT = 7359
    const val GDM_PORT = 32414
    const val JELLYFIN_QUERY = "who is JellyfinServer?"
    const val EMBY_QUERY = "who is EmbyServer?"
    const val GDM_QUERY = "M-SEARCH * HTTP/1.1\r\n\r\n"

    /** Reads the answer of a Jellyfin / Emby server: {"Address":"http://192.168.1.2:8096","Id":"...","Name":"..."}. */
    fun parseJellyfinReply(text: String, kind: ServerKind): FoundServer? = try {
        val o = JSONObject(text.trim())
        val address = o.str("Address")?.trimEnd('/') ?: return null
        FoundServer(o.str("Name") ?: address, address, o.str("Id").orEmpty(), kind)
    } catch (_: Exception) {
        null
    }

    /** Reads the answer of a Plex server: HTTP-like header lines with Name, Port and Resource-Identifier. */
    fun parseGdmReply(text: String, sourceHost: String): GdmServer? {
        val fields = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val i = line.indexOf(':')
            if (i > 0) fields[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (fields["content-type"]?.contains("plex/media-server", true) != true && !fields.containsKey("resource-identifier")) return null
        val id = fields["resource-identifier"] ?: return null
        val port = fields["port"]?.toIntOrNull() ?: 32400
        return GdmServer(fields["name"] ?: "Plex", id, sourceHost, port)
    }

    /** Broadcast addresses of the Wi-Fi, plus the general one. */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        try {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces() ?: return listOf(InetAddress.getByName("255.255.255.255")))) {
                if (!iface.isUp || iface.isLoopback) continue
                iface.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
            }
        } catch (_: Exception) {
        }
        result += InetAddress.getByName("255.255.255.255")
        return result.toList()
    }

    /** Asks the network for Jellyfin and Emby servers and collects answers for [timeoutMs]. */
    fun findJellyfin(timeoutMs: Int = 2500): List<FoundServer> {
        val found = Collections.synchronizedList(ArrayList<FoundServer>())
        val threads = listOf(JELLYFIN_QUERY to ServerKind.JELLYFIN, EMBY_QUERY to ServerKind.EMBY).map { (query, kind) ->
            Thread {
                try {
                    collect(query.toByteArray(), JELLYFIN_PORT, timeoutMs) { text, _ -> parseJellyfinReply(text, kind)?.let { found += it } }
                } catch (_: Exception) {
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(timeoutMs + 1500L) }
        return found.distinctBy { it.address }
    }

    /** Asks the network for Plex servers. They need a sign-in before they show anything. */
    fun findPlex(timeoutMs: Int = 2500): List<GdmServer> {
        val found = ArrayList<GdmServer>()
        try {
            collect(GDM_QUERY.toByteArray(), GDM_PORT, timeoutMs, extra = listOf(InetAddress.getByName("239.0.0.250"))) { text, from ->
                parseGdmReply(text, from)?.let { found += it }
            }
        } catch (_: Exception) {
        }
        return found.distinctBy { it.machineId }
    }

    private fun collect(message: ByteArray, port: Int, timeoutMs: Int, extra: List<InetAddress> = emptyList(), onReply: (String, String) -> Unit) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 400
            for (address in broadcastAddresses() + extra) {
                try {
                    socket.send(DatagramPacket(message, message.size, address, port))
                } catch (_: Exception) {
                }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                onReply(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress.orEmpty())
            }
        }
    }
}
