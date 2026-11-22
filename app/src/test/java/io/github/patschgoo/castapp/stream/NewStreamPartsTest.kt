package io.github.patschgoo.castapp.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class NewStreamPartsTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0xAC.toByte(), 0xD9.toByte(), 0x40)
    private val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte(), 0xCB.toByte())
    private val aac = AudioConfig(48000, 2, byteArrayOf(0x11, 0x90.toByte()), 128000)

    @Test
    fun adtsHeader() {
        val h = Adts.header(aac, 100)
        assertEquals(7, h.size)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals(0xF1, h[1].toInt() and 0xFF) // MPEG-4, no CRC
        assertEquals(1, (h[2].toInt() and 0xFF) ushr 6) // AAC-LC
        assertEquals(3, ((h[2].toInt() and 0xFF) ushr 2) and 0xF) // 48 kHz
        val channels = ((h[2].toInt() and 1) shl 2) or ((h[3].toInt() and 0xFF) ushr 6)
        assertEquals(2, channels)
        val length = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) ushr 5)
        assertEquals(107, length)
    }

    @Test
    fun tsPacketsAreWellFormed() {
        val mux = TsMuxer(VideoConfig(1280, 720, sps, pps), aac)
        val idr = byteArrayOf(0, 0, 0, 5, 0x65, 1, 2, 3, 4) // one length-prefixed IDR slice
        assertNull(mux.mux(EncodedSample(FragmentedMp4.AUDIO_TRACK_ID, ByteArray(10), 0, 21_333, true))) // waits for a key frame
        val out = ArrayList<Byte>()
        mux.mux(EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, idr + ByteArray(900), 1_000_000, 33_333, true))!!.forEach { out.add(it) }
        mux.mux(EncodedSample(FragmentedMp4.AUDIO_TRACK_ID, ByteArray(300), 1_010_000, 21_333, true))!!.forEach { out.add(it) }
        val bytes = out.toByteArray()
        assertEquals(0, bytes.size % TsMuxer.PACKET)
        val lastCc = HashMap<Int, Int>()
        for (p in 0 until bytes.size / TsMuxer.PACKET) {
            val o = p * TsMuxer.PACKET
            assertEquals(0x47, bytes[o].toInt() and 0xFF)
            val pid = ((bytes[o + 1].toInt() and 0x1F) shl 8) or (bytes[o + 2].toInt() and 0xFF)
            val cc = bytes[o + 3].toInt() and 0x0F
            lastCc[pid]?.let { assertEquals("continuity of pid $pid", (it + 1) and 0xF, cc) }
            lastCc[pid] = cc
        }
        assertTrue(lastCc.keys.containsAll(listOf(TsMuxer.PAT_PID, TsMuxer.PMT_PID, TsMuxer.VIDEO_PID, TsMuxer.AUDIO_PID)))
        // A PSI section followed by its CRC has a CRC of zero.
        val sectionLength = ((bytes[6].toInt() and 0x0F) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(0, TsMuxer.crc32(bytes.copyOfRange(5, 8 + sectionLength)))
    }

    @Test
    fun adaptiveBitrateBacksOffAndRecovers() {
        var now = 0L
        val applied = ArrayList<Int>()
        val abr = AdaptiveBitrate(maxBps = 8_000_000, minBps = 1_000_000, clock = { now }) { applied.add(it) }
        // Healthy connection: nothing changes.
        repeat(50) { now += 100; abr.onClientLag(20_000) }
        assertTrue(applied.isEmpty())
        // Sustained lag: down by 30 %.
        repeat(10) { now += 100; abr.onClientLag(600_000) }
        assertEquals(5_600_000, abr.currentBps)
        // Frames had to be dropped: halve.
        now += 1500
        abr.onClientDropped()
        assertEquals(2_800_000, abr.currentBps)
        // Ten smooth seconds later it creeps back up.
        repeat(120) { now += 100; abr.onClientLag(10_000) }
        assertTrue(abr.currentBps > 2_800_000)
        assertTrue(abr.currentBps <= 8_000_000)
        // Never below the minimum.
        repeat(20) { now += 3000; abr.onClientDropped() }
        assertEquals(1_000_000, abr.currentBps)
    }

    @Test
    fun hlsPlaylistsAreRewritten() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            https://cdn.example.com/low.m3u8?token=abc
        """.trimIndent()
        val out = HlsRewriter.rewrite(master, URI("https://site.example.com/videos/master.m3u8")) { "P[$it]" }
        assertTrue(out.contains("P[https://site.example.com/videos/720p/index.m3u8]"))
        assertTrue(out.contains("P[https://cdn.example.com/low.m3u8?token=abc]"))
        assertTrue(out.contains("#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720"))

        val media = "#EXTM3U\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"../keys/k1\"\r\n#EXT-X-MAP:URI=\"init.mp4\"\r\n#EXTINF:4.0,\r\nseg 1.m4s\r\n"
        val out2 = HlsRewriter.rewrite(media, URI("https://h.example/a/b/list.m3u8")) { "P[$it]" }
        assertTrue(out2.contains("URI=\"P[https://h.example/a/keys/k1]\""))
        assertTrue(out2.contains("URI=\"P[https://h.example/a/b/init.mp4]\""))
        assertTrue(out2.contains("P[https://h.example/a/b/seg%201.m4s]"))
        assertTrue(HlsRewriter.isPlaylist("https://x/y.M3U8?a=1", null))
        assertTrue(HlsRewriter.isPlaylist("https://x/y", "application/vnd.apple.mpegurl"))
    }

    @Test
    fun srtBecomesWebVtt() {
        val srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\nHallo Welt\r\n\r\n2\r\n0:01:02,05 --> 0:01:04,000\r\nZwei\r\n"
        val vtt = Subtitles.toWebVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:03.000"))
        assertTrue(vtt.contains("00:01:02.050 --> 00:01:04.000"))
        assertEquals("WEBVTT\n\n00:00.000 --> 00:01.000\nx", Subtitles.toWebVtt("WEBVTT\n\n00:00.000 --> 00:01.000\nx"))
        // Old German SRTs are often Windows-1252, not UTF-8.
        val cp1252 = byteArrayOf('G'.code.toByte(), 0xFC.toByte(), 'n'.code.toByte())
        assertEquals("Gün", Subtitles.decode(cp1252))
        assertEquals("Gün", Subtitles.decode("Gün".toByteArray()))
        assertEquals("Gün", Subtitles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Gün".toByteArray()))
    }

    @Test
    fun interleavingQueueOrdersTracksAndFinishes() {
        val q = InterleavingQueue(setOf(FragmentedMp4.VIDEO_TRACK_ID, FragmentedMp4.AUDIO_TRACK_ID))
        fun s(track: Int, pts: Long) = EncodedSample(track, byteArrayOf(1), pts, 1, true)
        q.onSample(s(FragmentedMp4.VIDEO_TRACK_ID, 0))
        q.onSample(s(FragmentedMp4.VIDEO_TRACK_ID, 40))
        assertNull(q.take(0)) // waits until audio has something too
        q.onSample(s(FragmentedMp4.AUDIO_TRACK_ID, 20))
        assertEquals(0L, q.take(0)!!.ptsUs)
        assertEquals(20L, q.take(0)!!.ptsUs)
        assertNull(q.take(0)) // audio empty again
        q.endTrack(FragmentedMp4.AUDIO_TRACK_ID)
        assertEquals(40L, q.take(0)!!.ptsUs)
        q.endTrack(FragmentedMp4.VIDEO_TRACK_ID)
        assertTrue(q.isFinished)
        assertArrayEquals(byteArrayOf(1), s(1, 0).data)
    }
}
