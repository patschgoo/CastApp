package io.github.patschgoo.castapp.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress

class DnsTest {
    /** Builds an mDNS answer like a Chromecast sends, using name compression everywhere. */
    private fun chromecastResponse(caps: String, includeA: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun label(s: String) { out.write(s.length); out.write(s.toByteArray()) }
        fun pointer(offset: Int) { out.write(0xC0 or (offset ushr 8)); out.write(offset and 0xFF) }
        fun rdata(body: () -> Unit) {
            val lenPos = out.size()
            u16(0)
            val start = out.size()
            body()
            val bytes = out.toByteArray()
            val len = bytes.size - start
            bytes[lenPos] = (len ushr 8).toByte(); bytes[lenPos + 1] = len.toByte()
            out.reset(); out.write(bytes)
        }

        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(if (includeA) 3 else 2)
        val serviceOffset = out.size()
        label("_googlecast"); label("_tcp"); label("local"); out.write(0)
        val localOffset = serviceOffset + 1 + 11 + 1 + 4
        u16(Dns.TYPE_PTR); u16(1); u32(120)
        var instanceOffset = 0
        rdata {
            instanceOffset = out.size()
            label("Chromecast-1234abcd"); pointer(serviceOffset)
        }
        // SRV
        pointer(instanceOffset); u16(Dns.TYPE_SRV); u16(0x8001); u32(120)
        var targetOffset = 0
        rdata {
            u16(0); u16(0); u16(8009)
            targetOffset = out.size()
            label("1234abcd"); pointer(localOffset)
        }
        // TXT
        pointer(instanceOffset); u16(Dns.TYPE_TXT); u16(0x8001); u32(4500)
        rdata {
            for (entry in listOf("id=1234abcd", "md=Chromecast", "fn=Living Room TV", "ca=$caps", "rs=YouTube")) {
                out.write(entry.length); out.write(entry.toByteArray())
            }
        }
        if (includeA) {
            pointer(targetOffset); u16(Dns.TYPE_A); u16(0x8001); u32(120)
            rdata { out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 50)) }
        }
        return out.toByteArray()
    }

    @Test
    fun parsesChromecastAnswer() {
        val records = Dns.parse(chromecastResponse("4101"))
        assertEquals(4, records.size)
        val infos = MdnsCastInfo.fromRecords(records, null)
        assertEquals(1, infos.size)
        val device = infos[0].toDevice()!!
        assertEquals("Living Room TV", device.name)
        assertEquals("1234abcd", device.id)
        assertEquals("192.168.1.50", device.host)
        assertEquals(8009, device.port)
        assertEquals("Chromecast", device.model)
        assertEquals("YouTube", device.status)
    }

    @Test
    fun fallsBackToPacketSourceWithoutARecord() {
        val source = InetAddress.getByName("10.0.0.7")
        val device = MdnsCastInfo.fromRecords(Dns.parse(chromecastResponse("4101", includeA = false)), source).single().toDevice()!!
        assertEquals("10.0.0.7", device.host)
    }

    @Test
    fun hidesSpeakersWithoutVideoOutput() {
        // ca=2052 (0x804) is an audio-only device such as a Nest speaker.
        assertNull(MdnsCastInfo.fromRecords(Dns.parse(chromecastResponse("2052")), null).single().toDevice())
    }

    @Test
    fun queryHasQuestionForService() {
        val q = Dns.buildQuery(MdnsCastInfo.SERVICE, Dns.TYPE_PTR)
        assertEquals(1, q[5].toInt()) // QDCOUNT
        val name = String(q, 12, q.size - 12 - 5, Charsets.US_ASCII)
        assertTrue(name.contains("_googlecast") && name.contains("_tcp") && name.contains("local"))
        assertEquals(0x80, q[q.size - 2].toInt() and 0x80) // unicast-response bit
    }

    @Test
    fun garbageDoesNotCrash() {
        assertTrue(Dns.parse(ByteArray(5)).isEmpty())
        val bad = chromecastResponse("4101").copyOf(40)
        Dns.parse(bad) // must not throw
    }

    @Test
    fun subnetHosts() {
        val hosts = NetworkScanner.hostsOf(InetAddress.getByName("192.168.1.23") as Inet4Address, 24)
        assertEquals(253, hosts.size) // .1 to .254 without ourselves
        assertEquals("192.168.1.1", hosts.first().hostAddress)
        assertEquals("192.168.1.254", hosts.last().hostAddress)
        // Huge networks are limited to the /24 around the phone.
        assertEquals(253, NetworkScanner.hostsOf(InetAddress.getByName("10.4.5.6") as Inet4Address, 8).size)
        assertEquals(1021, NetworkScanner.hostsOf(InetAddress.getByName("10.4.5.6") as Inet4Address, 22).size)
    }
}
