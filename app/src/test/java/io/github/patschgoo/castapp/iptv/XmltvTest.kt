package io.github.patschgoo.castapp.iptv

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream

class XmltvTest {
    /** 2026-11-22 20:00 UTC. */
    private val now = Xmltv.parseTime("20261122200000 +0000")!!

    private fun xml(vararg body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="test">
${body.joinToString("\n")}
</tv>"""

    private fun programme(channel: String, start: String, stop: String, title: String, desc: String? = null) =
        """<programme start="$start" stop="$stop" channel="$channel"><title lang="de">$title</title>""" +
            (desc?.let { "<desc>$it</desc>" } ?: "") + "<category>News</category></programme>"

    @Test
    fun parsesTimeStampsWithZones() {
        assertEquals(1795375800000L, Xmltv.parseTime("20261122203000 +0100"))
        assertEquals(Xmltv.parseTime("20261122193000 +0000"), Xmltv.parseTime("20261122203000 +0100"))
        assertEquals(Xmltv.parseTime("20261122193000"), Xmltv.parseTime("20261122203000 +0100"))
        assertEquals(Xmltv.parseTime("20261122193000 Z"), Xmltv.parseTime("20261122203000 +01:00"))
        assertEquals(Xmltv.parseTime("20261122203000 +0100"), Xmltv.parseTime("20261122153000 -0400"))
        assertEquals(Xmltv.parseTime("20261122200000 +0000")!! - 3600_000L * 5 - 30 * 60_000L, Xmltv.parseTime("20261122200000 +0530"))
        // Short forms and the epoch itself.
        assertEquals(0L, Xmltv.parseTime("19700101000000 +0000"))
        assertEquals(Xmltv.parseTime("20261122203000 +0000"), Xmltv.parseTime("202611222030"))
        assertEquals(Xmltv.parseTime("20261122000000 +0000"), Xmltv.parseTime("20261122"))
        assertNull(Xmltv.parseTime("garbage"))
        assertNull(Xmltv.parseTime("20261322203000"))
    }

    @Test
    fun keepsOnlyMatchingChannelsAndTheTimeWindow() {
        val doc = xml(
            """<channel id="Das.Erste.de"><display-name lang="de">Das Erste</display-name></channel>""",
            """<channel id="zdf.de"><display-name>ZDF HD</display-name><display-name>Zweites</display-name></channel>""",
            """<channel id="other.de"><display-name>Not in the list</display-name></channel>""",
            programme("das.erste.de", "20261122190000 +0000", "20261122200000 +0000", "Tagesschau", "News &amp; weather"),
            programme("das.erste.de", "20261122200000 +0000", "20261122210000 +0000", "Krimi"),
            // Too old: ended 3 hours ago.
            programme("das.erste.de", "20261122160000 +0000", "20261122170000 +0000", "Old news"),
            // Too far ahead: starts in 40 hours.
            programme("das.erste.de", "20261124120000 +0000", "20261124130000 +0000", "Far away"),
            programme("zdf.de", "20261122203000 +0100", "20261122213000 +0100", "heute-journal"),
            programme("other.de", "20261122190000 +0000", "20261122210000 +0000", "Unwanted"),
        )
        val channels = listOf(
            Channel("Das Erste HD", "http://x/1", tvgId = "das.erste.de"),
            Channel("DE: ZDF", "http://x/2"),
        )
        val guide = Xmltv.parse(ByteArrayInputStream(doc.toByteArray()), channels, now)

        assertEquals(setOf("das.erste.de", "zdf.de"), guide.programmes.keys)
        val erste = guide.forChannel(channels[0])
        assertEquals(listOf("Tagesschau", "Krimi"), erste.map { it.title })
        assertEquals("News & weather", erste[0].desc)
        assertNull(erste[1].desc)
        // The second channel has no tvg-id: matched by its name through the guide's display names.
        val zdf = guide.forChannel(channels[1])
        assertEquals(listOf("heute-journal"), zdf.map { it.title })
        assertEquals(Xmltv.parseTime("20261122193000 +0000"), zdf[0].start)

        val (onNow, next) = guide.nowAndNext(channels[0], now + 10 * 60_000L)
        assertEquals("Krimi", onNow?.title)
        assertNull(next)
        val (before, upcoming) = guide.nowAndNext(channels[0], now - 30 * 60_000L)
        assertEquals("Tagesschau", before?.title)
        assertEquals("Krimi", upcoming?.title)
    }

    @Test
    fun matchesByNameWhenTheTvgIdIsUnknownToTheGuide() {
        val doc = xml(
            """<channel id="ard"><display-name>Das Erste</display-name></channel>""",
            programme("ard", "20261122200000 +0000", "20261122210000 +0000", "Film"),
        )
        val c = Channel("DE | Das Erste FHD", "http://x", tvgId = "not-in-guide")
        val guide = Xmltv.parse(StringReader(doc), listOf(c), now)
        assertEquals(listOf("Film"), guide.forChannel(c).map { it.title })
    }

    @Test
    fun normalisesChannelNames() {
        assertEquals(EpgNames.normalise("Das Erste"), EpgNames.normalise("DE: Das Erste HD"))
        assertEquals(EpgNames.normalise("Das Erste"), EpgNames.normalise("|DE| DAS ERSTE FHD"))
        assertEquals("rtl2", EpgNames.normalise("RTL 2"))
        assertEquals(EpgNames.normalise("Canal Plus"), EpgNames.normalise("Canal+  Plus"))
        assertEquals("zdf", EpgNames.normalise("ZDF"))
        assertEquals(EpgNames.normalise("Cafe"), EpgNames.normalise("Café"))
        assertEquals("hd", EpgNames.normalise("HD"))
    }

    @Test
    fun toleratesEntitiesCdataCommentsAndBrokenEndings() {
        val doc = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE tv [ <!ELEMENT tv ANY> ]>
<tv>
<!-- a <comment> with markup -->
<channel id="a"/>
<programme start="20261122200000 +0000" stop="20261122210000 +0000" channel="a">
  <title><![CDATA[Tom & Jerry <3]]></title>
  <desc>Caf&eacute; &#233; &#x41; &lt;b&gt; &unknown; Fish &amp; Chips</desc>
  <icon src="http://x/y.png"/>
</programme>
<programme start="20261122210000 +0000" channel="a"><title>No stop given</title></programme>
<programme start="20261122220000 +0000" stop="20261122230000 +0000" channel="a"><title>Cut o"""
        val c = Channel("A", "http://x", tvgId = "a")
        val list = Xmltv.parse(StringReader(doc), listOf(c), now).forChannel(c)
        assertEquals(3, list.size)
        assertEquals("Tom & Jerry <3", list[0].title)
        assertEquals("Caf&eacute; é A <b> &unknown; Fish & Chips", list[0].desc)
        assertEquals("No stop given", list[1].title)
        // A missing end is the start of the next programme.
        assertEquals(Xmltv.parseTime("20261122220000 +0000"), list[1].stop)
        assertEquals("Cut o", list[2].title)
    }

    @Test
    fun readsTheDeclaredEncoding() {
        val doc = """<?xml version="1.0" encoding="ISO-8859-1"?><tv><channel id="a"><display-name>A</display-name></channel>
<programme start="20261122200000 +0000" stop="20261122210000 +0000" channel="a"><title>Käse</title></programme></tv>"""
        val c = Channel("A", "http://x")
        val guide = Xmltv.parse(ByteArrayInputStream(doc.toByteArray(Charsets.ISO_8859_1)), listOf(c), now)
        assertEquals("Käse", guide.forChannel(c).single().title)
    }

    @Test
    fun gzipIsRecognisedByContentNotName() {
        val doc = xml(
            """<channel id="a"><display-name>A</display-name></channel>""",
            programme("a", "20261122200000 +0000", "20261122210000 +0000", "Zipped"),
        )
        val c = Channel("A", "http://x", tvgId = "a")
        val zipped = gzip(doc.toByteArray())
        val guide = GuideLoader.load(listOf("http://unused/guide.xml"), listOf(c), now) { ByteArrayInputStream(zipped) }
        assertEquals("Zipped", guide.forChannel(c).single().title)
        val plain = GuideLoader.load(listOf("http://unused/guide.xml"), listOf(c), now) { ByteArrayInputStream(doc.toByteArray()) }
        assertEquals("Zipped", plain.forChannel(c).single().title)
    }

    @Test
    fun downloadsOverHttpWithGzipRedirectAndFallbackAddresses() {
        val doc = xml(
            """<channel id="a"><display-name>A</display-name></channel>""",
            programme("a", "20261122200000 +0000", "20261122210000 +0000", "From the server"),
        )
        val zipped = gzip(doc.toByteArray())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/guide.xml.gz") { ex ->
            ex.sendResponseHeaders(200, zipped.size.toLong())
            ex.responseBody.use { it.write(zipped) }
        }
        server.createContext("/encoded.xml") { ex ->
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(200, zipped.size.toLong())
            ex.responseBody.use { it.write(zipped) }
        }
        server.createContext("/moved") { ex ->
            ex.responseHeaders.add("Location", "/guide.xml.gz")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/missing") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val c = Channel("A", "http://x", tvgId = "a")
            for (path in listOf("/guide.xml.gz", "/encoded.xml", "/moved")) {
                val guide = GuideLoader.load(listOf(base + path), listOf(c), now)
                assertEquals(path, "From the server", guide.forChannel(c).single().title)
            }
            // One address failing is fine while another works.
            val guide = GuideLoader.load(listOf("$base/missing", "$base/guide.xml.gz"), listOf(c), now)
            assertEquals(1, guide.forChannel(c).size)
            // All failing: an error.
            try {
                GuideLoader.load(listOf("$base/missing"), listOf(c), now)
                throw AssertionError("expected an error")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("404"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun mergesSeveralGuides() {
        val a = xml(
            """<channel id="a"><display-name>A</display-name></channel>""",
            programme("a", "20261122200000 +0000", "20261122210000 +0000", "One"),
        )
        val b = xml(
            """<channel id="b"><display-name>B</display-name></channel>""",
            programme("b", "20261122200000 +0000", "20261122210000 +0000", "Two"),
        )
        val chans = listOf(Channel("A", "u", tvgId = "a"), Channel("B", "u", tvgId = "b"))
        val docs = mapOf("http://one" to a, "http://two" to b)
        val guide = GuideLoader.load(docs.keys.toList(), chans, now) { ByteArrayInputStream(docs.getValue(it).toByteArray()) }
        assertEquals("One", guide.forChannel(chans[0]).single().title)
        assertEquals("Two", guide.forChannel(chans[1]).single().title)
    }

    @Test
    fun savesAndReadsBackTheGuide() {
        val c = Channel("Das Erste", "u")
        val data = EpgData(
            programmes = mapOf("ard" to listOf(Programme(1000, 2000, "Tab\there", "Line\nbreak"), Programme(2000, 3000, "Next"))),
            names = mapOf(EpgNames.normalise("Das Erste") to "ard"),
            fetchedAt = 42L,
            signature = "http://a http://b",
        )
        val out = StringWriter()
        data.write(out)
        val back = EpgData.read(StringReader(out.toString()))
        assertEquals(42L, back.fetchedAt)
        assertEquals("http://a http://b", back.signature)
        assertEquals(listOf("Tab here", "Next"), back.forChannel(c).map { it.title })
        assertEquals("Line break", back.forChannel(c)[0].desc)
        assertFalse(back.isEmpty)
    }

    @Test
    fun programmeProgress() {
        val p = Programme(1000, 3000, "x")
        assertEquals(0.5f, p.progress(2000), 0.001f)
        assertEquals(0f, p.progress(0), 0.001f)
        assertEquals(1f, p.progress(9000), 0.001f)
        assertTrue(p.isOn(1000))
        assertFalse(p.isOn(3000))
    }

    @Test
    fun bigGuidesAreStreamedWithLittleMemory() {
        // Roughly 55 MB of XML, generated on the fly: 200 channels x 600 programmes, each with a long description.
        val channelCount = 200
        val perChannel = 600
        val filler = "x".repeat(60)
        val wantedFrom = Xmltv.parseTime("20261122190000 +0000")!!
        val stream = generatedGuide(channelCount, perChannel, filler, wantedFrom)
        val channels = listOf(Channel("Channel 7", "u", tvgId = "c7"), Channel("Channel 150", "u", tvgId = "c150"))
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val counting = object : java.io.FilterInputStream(stream) {
            var total = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) total += n
                return n
            }
        }
        val guide = Xmltv.parse(counting, channels, now)
        assertTrue("generated ${counting.total} bytes", counting.total > 50_000_000L)
        // Programmes are 30 minutes long, starting 19:00 UTC: 2 h back and 36 h ahead of 20:00 leaves ~ 3 + 72 entries.
        val seven = guide.forChannel(channels[0])
        assertNotNull(seven)
        assertTrue("got ${seven.size}", seven.size in 60..80)
        assertEquals(seven.size, seven.map { it.start }.distinct().size)
        assertEquals(setOf("c7", "c150"), guide.programmes.keys)
        // Only the wanted programmes are kept: after a collection almost nothing of the 55 MB remains.
        System.gc()
        val retained = runtime.totalMemory() - runtime.freeMemory() - before
        assertTrue("kept ${retained / 1_000_000} MB (guide ${guide.programmes.size})", retained < 20_000_000L)
    }

    private fun generatedGuide(channelCount: Int, perChannel: Int, filler: String, first: Long): InputStream {
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val lines = sequence {
            yield("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for (c in 1..channelCount) yield("<channel id=\"c$c\"><display-name>Channel $c</display-name></channel>\n")
            for (c in 1..channelCount) {
                for (i in 0 until perChannel) {
                    val start = first + i * 30 * 60_000L
                    val s = fmt.format(java.util.Date(start))
                    val e = fmt.format(java.util.Date(start + 30 * 60_000L))
                    yield(
                        "<programme start=\"$s +0000\" stop=\"$e +0000\" channel=\"c$c\"><title>Show $i</title>" +
                            "<desc>$filler $filler $filler $filler $filler</desc><category>x</category></programme>\n",
                    )
                }
            }
            yield("</tv>\n")
        }.iterator()
        return object : InputStream() {
            private var cur: ByteArray = ByteArray(0)
            private var idx = 0
            private fun ensure(): Boolean {
                while (idx >= cur.size) {
                    if (!lines.hasNext()) return false
                    cur = lines.next().toByteArray()
                    idx = 0
                }
                return true
            }

            override fun read(): Int = if (ensure()) cur[idx++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!ensure()) return -1
                val n = minOf(len, cur.size - idx)
                System.arraycopy(cur, idx, b, off, n)
                idx += n
                return n
            }
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
