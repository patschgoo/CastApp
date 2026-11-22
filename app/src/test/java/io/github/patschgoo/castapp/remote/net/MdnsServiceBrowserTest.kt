package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.discovery.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Finding `_androidtvremote2._tcp` instances in an mDNS answer. */
class MdnsServiceBrowserTest {
    private class Packet {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Long) { u16((v ushr 16).toInt()); u16((v and 0xFFFF).toInt()) }
        fun name(n: String) = ByteArrayOutputStream().apply {
            for (l in n.split('.')) { val b = l.toByteArray(); write(b.size); write(b) }
            write(0)
        }.toByteArray()
        fun record(n: String, type: Int, data: ByteArray) {
            out.write(name(n)); u16(type); u16(0x8001); u32(120); u16(data.size); out.write(data)
        }
    }

    @Test
    fun parsesAnswer() {
        val service = "_androidtvremote2._tcp.local"
        val instance = "Living Room TV.$service"
        val p = Packet()
        p.u16(0); p.u16(0x8400); p.u16(0); p.u16(4); p.u16(0); p.u16(0)
        p.record(service, Dns.TYPE_PTR, p.name(instance))
        val srv = ByteArrayOutputStream().apply {
            write(0); write(0); write(0); write(0)
            write(6466 ushr 8); write(6466 and 0xFF)
            write(p.name("Android-3.local"))
        }.toByteArray()
        p.record(instance, Dns.TYPE_SRV, srv)
        val txt = "bt=AA:BB:CC:DD:EE:FF".toByteArray()
        p.record(instance, Dns.TYPE_TXT, byteArrayOf(txt.size.toByte()) + txt)
        p.record("Android-3.local", Dns.TYPE_A, byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val bytes = p.out.toByteArray()

        val found = MdnsServiceBrowser.parse(service, Dns.parse(bytes), InetAddress.getByName("192.168.1.99"))
        assertEquals(1, found.size)
        val s = found[0]
        assertEquals("Living Room TV", s.name)
        assertEquals("192.168.1.20", s.host.hostAddress)
        assertEquals(6466, s.port)
        assertEquals("AA:BB:CC:DD:EE:FF", s.txt["bt"])

        // Without SRV/A records, the sender's address is used.
        val q = Packet()
        q.u16(0); q.u16(0x8400); q.u16(0); q.u16(1); q.u16(0); q.u16(0)
        q.record(service, Dns.TYPE_PTR, q.name(instance))
        val bare = MdnsServiceBrowser.parse(service, Dns.parse(q.out.toByteArray()), InetAddress.getByName("192.168.1.99")).single()
        assertEquals("192.168.1.99", bare.host.hostAddress)
        assertEquals(0, bare.port)
    }
}
