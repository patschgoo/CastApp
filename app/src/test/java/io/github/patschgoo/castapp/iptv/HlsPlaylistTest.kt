package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HlsPlaylistTest {
    private val base = "http://host.example/live/index.m3u8"

    @Test
    fun readsAMediaPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:6.006,
            seg100.ts
            #EXTINF:5.5,title
            /abs/seg101.ts?token=1
            #EXTINF:6,
            http://cdn.example/seg102.ts
        """.trimIndent()
        val p = HlsPlaylist.parse(text, base) as HlsPlaylist.Media
        assertEquals(6, p.targetDurationSec)
        assertEquals(100L, p.mediaSequence)
        assertFalse(p.endList)
        assertEquals(
            listOf("http://host.example/live/seg100.ts", "http://host.example/abs/seg101.ts?token=1", "http://cdn.example/seg102.ts"),
            p.segments.map { it.uri },
        )
        assertEquals(listOf(100L, 101L, 102L), p.segments.map { it.sequence })
        assertEquals(6.006, p.segments[0].durationSec, 0.0001)
        assertEquals("ts", p.extension)
        assertFalse(p.isFmp4)
    }

    @Test
    fun endListAndFmp4WithInitSegment() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4,
            a.m4s
            #EXTINF:4,
            b.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylist.parse(text, base) as HlsPlaylist.Media
        assertTrue(p.endList)
        assertTrue(p.isFmp4)
        assertEquals("mp4", p.extension)
        assertEquals("http://host.example/live/init.mp4", p.segments[0].init!!.uri)
        assertEquals(p.segments[0].init, p.segments[1].init)
    }

    @Test
    fun audioOnlySegmentsGetAnAacExtension() {
        val p = HlsPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.aac\n#EXTINF:4,\nb.aac\n", base) as HlsPlaylist.Media
        assertEquals("aac", p.extension)
    }

    @Test
    fun masterPlaylistPicksQuality() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2500000,RESOLUTION=1280x720
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000
            mid/index.m3u8
        """.trimIndent()
        val master = HlsPlaylist.parse(text, base) as HlsPlaylist.Master
        assertEquals(3, master.variants.size)
        assertEquals("http://host.example/live/high/index.m3u8", master.pick()!!.uri)
        assertEquals(2_500_000L, master.pick()!!.bandwidth)
        assertEquals("http://host.example/live/mid/index.m3u8", master.pick(2_000_000)!!.uri)
        assertEquals("http://host.example/live/low/index.m3u8", master.pick(100)!!.uri)
        assertNull(HlsPlaylist.Master(emptyList()).pick())
    }

    @Test
    fun byteRangesContinueWhereThePreviousOneEnded() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@0
            big.ts
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            big.ts
            #EXTINF:4,
            plain.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylist.parse(text, base) as HlsPlaylist.Media
        assertEquals(HlsByteRange(1000, 0), p.segments[0].range)
        assertEquals(HlsByteRange(500, 1000), p.segments[1].range)
        assertNull(p.segments[2].range)
    }

    @Test
    fun aes128KeysAreReadAndOtherEncryptionIsRefused() {
        val ok = HlsPlaylist.parse(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x000102030405060708090A0B0C0D0E0F\n#EXTINF:4,\na.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\nb.ts\n",
            base,
        ) as HlsPlaylist.Media
        val key = ok.segments[0].key!!
        assertEquals("http://host.example/live/key.bin", key.uri)
        assertArrayEquals(ByteArray(16) { it.toByte() }, key.iv)
        assertNull(ok.segments[1].key)
        try {
            HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:4,\na.ts\n", base)
            fail("SAMPLE-AES can't be recorded")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("SAMPLE-AES"))
        }
    }

    @Test
    fun decryptionUsesTheSequenceNumberWhenThereIsNoIv() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val plain = ByteArray(1000) { (it % 251).toByte() }
        val iv = ByteArray(16).also { it[15] = 7 }
        val encrypted = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            doFinal(plain)
        }
        assertArrayEquals(plain, HlsPlaylist.decrypt(encrypted, key, null, 7))
        assertArrayEquals(plain, HlsPlaylist.decrypt(encrypted, key, iv, 99))
    }

    @Test
    fun recognisesPlaylistsByTheirFirstBytes() {
        fun looks(s: String) = s.toByteArray().let { HlsPlaylist.looksLikePlaylist(it, it.size) }
        assertTrue(looks("#EXTM3U\n#EXT-X-VERSION:3"))
        assertTrue(looks("﻿#EXTM3U\n"))
        assertFalse(looks("G@\u0000\u0010 transport stream"))
        assertFalse(looks("#EXT"))
        assertFalse(looks("<html>"))
    }

    @Test
    fun aFileThatIsNotAPlaylistIsRefused() {
        try {
            HlsPlaylist.parse("<html></html>", base)
            fail()
        } catch (e: IOException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun retryDelaysDoubleUpToTheCeiling() {
        val r = RetryPolicy(baseDelayMs = 1000, maxDelayMs = 10_000)
        assertEquals(0L, r.delayFor(0))
        assertEquals(1000L, r.delayFor(1))
        assertEquals(2000L, r.delayFor(2))
        assertEquals(4000L, r.delayFor(3))
        assertEquals(8000L, r.delayFor(4))
        assertEquals(10_000L, r.delayFor(5))
        assertEquals(10_000L, r.delayFor(60))
        assertEquals(3000L, r.pollInterval(6))
        assertEquals(1000L, r.pollInterval(1))
        assertEquals(6000L, r.pollInterval(60))
    }
}
