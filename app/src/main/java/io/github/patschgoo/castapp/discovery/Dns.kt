package io.github.patschgoo.castapp.discovery

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Just enough DNS to speak multicast DNS / DNS-SD (RFC 6762 / 6763). */
object Dns {
    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33
    const val CLASS_IN = 1

    class Srv(val priority: Int, val weight: Int, val port: Int, val target: String)

    class Record(val name: String, val type: Int, val ttl: Long, val data: Any?)

    fun buildQuery(name: String, type: Int, unicastResponse: Boolean = true, id: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(64)
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id)
        u16(0) // standard query
        u16(1) // QDCOUNT
        u16(0); u16(0); u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(type)
        u16(if (unicastResponse) CLASS_IN or 0x8000 else CLASS_IN)
        return out.toByteArray()
    }

    /** Parses all resource records (answers, authority and additional) of a DNS message. */
    fun parse(packet: ByteArray, length: Int = packet.size): List<Record> {
        if (length < 12) return emptyList()
        var pos = 0
        fun u8(): Int { if (pos >= length) throw IllegalArgumentException("truncated"); return packet[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun readName(start: Int): Pair<String, Int> {
            val labels = ArrayList<String>()
            var p = start
            var end = -1
            var jumps = 0
            while (true) {
                if (p >= length) throw IllegalArgumentException("truncated name")
                val len = packet[p].toInt() and 0xFF
                when {
                    len == 0 -> { p++; break }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= length) throw IllegalArgumentException("truncated pointer")
                        val target = ((len and 0x3F) shl 8) or (packet[p + 1].toInt() and 0xFF)
                        if (end < 0) end = p + 2
                        if (++jumps > 32) throw IllegalArgumentException("pointer loop")
                        p = target
                    }
                    else -> {
                        if (p + 1 + len > length) throw IllegalArgumentException("truncated label")
                        labels.add(String(packet, p + 1, len, Charsets.UTF_8))
                        p += 1 + len
                    }
                }
            }
            return labels.joinToString(".") to (if (end >= 0) end else p)
        }

        return try {
            pos = 4
            val qd = u16()
            val an = u16()
            val ns = u16()
            val ar = u16()
            repeat(qd) {
                pos = readName(pos).second
                pos += 4
            }
            val records = ArrayList<Record>()
            repeat(an + ns + ar) {
                val (name, after) = readName(pos)
                pos = after
                val type = u16()
                u16() // class (with cache-flush bit)
                val ttl = u32()
                val rdLength = u16()
                val rdStart = pos
                if (rdStart + rdLength > length) return records
                val data: Any? = when (type) {
                    TYPE_PTR -> readName(rdStart).first
                    TYPE_SRV -> {
                        pos = rdStart
                        val priority = u16()
                        val weight = u16()
                        val port = u16()
                        Srv(priority, weight, port, readName(pos).first)
                    }
                    TYPE_TXT -> parseTxt(packet, rdStart, rdLength)
                    TYPE_A -> if (rdLength == 4) InetAddress.getByAddress(packet.copyOfRange(rdStart, rdStart + 4)) else null
                    else -> null
                }
                pos = rdStart + rdLength
                records.add(Record(name, type, ttl, data))
            }
            records
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun parseTxt(packet: ByteArray, start: Int, length: Int): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var p = start
        val end = start + length
        while (p < end) {
            val len = packet[p].toInt() and 0xFF
            p++
            if (len == 0 || p + len > end) { p += len; continue }
            val entry = String(packet, p, len, Charsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) map[entry.substring(0, eq).lowercase()] = entry.substring(eq + 1)
            else map[entry.lowercase()] = ""
        }
        return map
    }
}

/** A Cast service instance extracted from mDNS records. */
class MdnsCastInfo(val instance: String, val address: InetAddress?, val port: Int, val txt: Map<String, String>) {
    /** Converts to a device. Speakers and speaker groups are kept but marked as audio-only. */
    fun toDevice(): CastDevice? {
        val ip = address?.hostAddress ?: return null
        val caps = txt["ca"]?.toIntOrNull()
        val model = txt["md"]?.takeIf { it.isNotBlank() }
        val group = (caps != null && caps and CAP_GROUP != 0) || model == "Google Cast Group"
        val video = !group && (caps == null || caps and CAP_VIDEO_OUT != 0)
        val name = txt["fn"]?.takeIf { it.isNotBlank() } ?: instance.substringBefore("._googlecast").substringBeforeLast('-')
        return CastDevice(
            id = txt["id"]?.takeIf { it.isNotBlank() } ?: "host:$ip:$port",
            name = name,
            host = ip,
            port = if (port > 0) port else 8009,
            model = model,
            status = txt["rs"]?.takeIf { it.isNotBlank() },
            video = video,
            group = group,
        )
    }

    companion object {
        const val SERVICE = "_googlecast._tcp.local"
        private const val CAP_VIDEO_OUT = 0x01
        private const val CAP_GROUP = 0x20

        fun fromRecords(records: List<Dns.Record>, source: InetAddress?): List<MdnsCastInfo> {
            val instances = LinkedHashSet<String>()
            for (r in records) {
                if (r.type == Dns.TYPE_PTR && r.name.equals(SERVICE, true)) (r.data as? String)?.let { instances.add(it) }
                if ((r.type == Dns.TYPE_SRV || r.type == Dns.TYPE_TXT) && r.name.endsWith(".$SERVICE", true)) instances.add(r.name)
            }
            return instances.map { instance ->
                val srv = records.firstOrNull { it.type == Dns.TYPE_SRV && it.name.equals(instance, true) }?.data as? Dns.Srv
                @Suppress("UNCHECKED_CAST")
                val txt = records.firstOrNull { it.type == Dns.TYPE_TXT && it.name.equals(instance, true) }?.data as? Map<String, String>
                val a = srv?.let { s -> records.firstOrNull { it.type == Dns.TYPE_A && it.name.equals(s.target, true) }?.data as? InetAddress }
                MdnsCastInfo(instance, a ?: source, srv?.port ?: 8009, txt ?: emptyMap())
            }
        }
    }
}
