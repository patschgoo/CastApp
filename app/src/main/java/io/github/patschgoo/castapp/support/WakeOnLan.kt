package io.github.patschgoo.castapp.support

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.NetworkScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Wake-on-LAN: switching on a TV that sleeps in network standby. */
object WakeOnLan {
    /** Parses "aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF" or "aabbccddeeff" into 6 bytes; null if invalid. */
    fun parseMac(text: String?): ByteArray? {
        if (text == null) return null
        val hex = text.trim().replace(":", "").replace("-", "").replace(".", "")
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val bytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }) return null
        return bytes
    }

    /** The normal way to write a MAC address ("AA:BB:..."), or null if [text] is not one. */
    fun normalizeMac(text: String?): String? =
        parseMac(text)?.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** 6 bytes of 0xFF followed by the MAC address 16 times (102 bytes). */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "A MAC address has 6 bytes" }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (r in 0 until 16) System.arraycopy(mac, 0, packet, 6 + r * 6, 6)
        return packet
    }

    /** The broadcast address of the subnet of [address]/[prefixLength] (e.g. 192.168.1.255). */
    fun broadcastAddress(address: Inet4Address, prefixLength: Int): InetAddress {
        val prefix = prefixLength.coerceIn(8, 30)
        val ip = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val b = ip or (mask.inv() and 0xFFFFFFFFL)
        return InetAddress.getByAddress(byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte()))
    }

    /** Sends the magic packet to port 9 on every local subnet and to 255.255.255.255. Returns false if nothing could be sent. */
    suspend fun wake(macText: String): Boolean = withContext(Dispatchers.IO) {
        val mac = parseMac(macText) ?: return@withContext false
        val data = magicPacket(mac)
        val targets = LinkedHashSet<InetAddress>()
        for (net in NetworkScanner.localNetworks()) targets.add(broadcastAddress(net.address, net.prefixLength))
        targets.add(InetAddress.getByName("255.255.255.255"))
        var sent = false
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in targets) {
                    try {
                        // Twice: UDP may drop the first one on a waking network card.
                        repeat(2) { socket.send(DatagramPacket(data, data.size, target, 9)) }
                        sent = true
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        sent
    }

    /** Reads the Wi-Fi MAC address from the JSON of a Samsung TV's `/api/v2/`; null if it has none. */
    fun parseSamsungMac(json: String): String? = try {
        normalizeMac(JSONObject(json).optJSONObject("device")?.optString("wifiMac"))
    } catch (_: Exception) {
        null
    }

    /** Asks the device itself for its MAC address (Samsung TVs report it). Returns null if it does not. */
    suspend fun discoverMac(device: CastDevice): String? = withContext(Dispatchers.IO) {
        val body = NetworkScanner.simpleHttpGet(device.host, 8001, "/api/v2/", tls = false, timeoutMs = 1500) ?: return@withContext null
        parseSamsungMac(body)
    }
}
