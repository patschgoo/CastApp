package io.github.patschgoo.castapp.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StreamTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0xAC.toByte(), 0xD9.toByte(), 0x40)
    private val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte(), 0xCB.toByte())

    /** Walks top-level / nested boxes and returns "type@offset:size" for the whole tree. */
    private fun boxTree(data: ByteArray, start: Int = 0, end: Int = data.size, depth: Int = 0, out: MutableList<String> = ArrayList()): List<String> {
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf", "dinf")
        var p = start
        while (p + 8 <= end) {
            val size = ByteBuffer.wrap(data, p, 4).int
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            assertTrue("box $type at $p has bad size $size", size >= 8 && p + size <= end)
            out.add("  ".repeat(depth) + type)
            when (type) {
                in containers -> boxTree(data, p + 8, p + size, depth + 1, out)
                "stsd" -> boxTree(data, p + 16, p + size, depth + 1, out) // full box + entry count
                "avc1" -> boxTree(data, p + 8 + 78, p + size, depth + 1, out) // visual sample entry fields
                "mp4a" -> boxTree(data, p + 8 + 28, p + size, depth + 1, out) // audio sample entry fields
            }
            p += size
        }
        assertEquals("boxes must fill their parent exactly", end, p)
        return out
    }

    @Test
    fun splitsAnnexBAndConvertsToAvcc() {
        val annexB = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        val nals = AvcUtils.splitAnnexB(annexB)
        assertEquals(3, nals.size)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1])
        assertTrue(AvcUtils.containsIdr(nals))
        // SPS/PPS are dropped from samples, the slice gets a 4-byte length prefix.
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 0x65, 0x11, 0x22), AvcUtils.toAvccSample(nals))
        assertEquals("avc1.64001F", AvcUtils.codecString(sps))
    }

    @Test
    fun initSegmentStructure() {
        val asc = byteArrayOf(0x11, 0x90.toByte())
        val init = FragmentedMp4.initSegment(VideoConfig(1280, 720, sps, pps), AudioConfig(48000, 2, asc, 128000))
        val tree = boxTree(init).map { it.trim() }
        assertEquals(listOf("ftyp", "moov"), boxTree(init).filter { !it.startsWith(" ") })
        assertEquals(2, tree.count { it == "trak" })
        assertEquals(2, tree.count { it == "trex" })
        assertTrue(tree.containsAll(listOf("avc1", "avcC", "mp4a", "esds", "mvhd", "tkhd", "mdhd", "hdlr", "stts", "stco")))
    }

    @Test
    fun fragmentDataOffsetPointsAtSampleData() {
        val sample = byteArrayOf(0, 0, 0, 2, 0x65, 0x42)
        val frag = FragmentedMp4.fragment(7, FragmentedMp4.VIDEO_TRACK_ID, 90_000, listOf(FragmentSample(sample, 3000, true)))
        val tree = boxTree(frag)
        assertEquals(listOf("moof", "  mfhd", "  traf", "    tfhd", "    tfdt", "    trun", "mdat"), tree)
        // trun data_offset is relative to the start of moof and must point right after the mdat header.
        val trun = indexOf(frag, "trun") - 4
        val dataOffset = ByteBuffer.wrap(frag, trun + 16, 4).int
        assertArrayEquals(sample, frag.copyOfRange(dataOffset, dataOffset + sample.size))
        // tfdt version 1 carries the 64-bit decode time.
        val tfdt = indexOf(frag, "tfdt") - 4
        assertEquals(90_000L, ByteBuffer.wrap(frag, tfdt + 12, 8).long)
    }

    private fun indexOf(data: ByteArray, fourcc: String): Int {
        val needle = fourcc.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - 4) {
            for (j in 0 until 4) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun hubComputesVideoDurationsFromNextFrame() {
        val hub = StreamHub()
        hub.setVideoConfig(VideoConfig(1280, 720, sps, pps))
        val got = ArrayList<EncodedSample>()
        hub.subscribe { got.add(it) }
        hub.pushVideo(byteArrayOf(1), 1_000_000, true)
        assertTrue(got.isEmpty()) // held back until the next frame arrives
        hub.pushVideo(byteArrayOf(2), 1_033_000, false)
        hub.pushVideo(byteArrayOf(3), 1_100_000, false)
        assertEquals(2, got.size)
        assertEquals(33_000, got[0].durationUs)
        assertTrue(got[0].isKey)
        assertEquals(67_000, got[1].durationUs)
    }

    @Test
    fun initWaitsForAudioThenFallsBackToVideoOnly() {
        val hub = StreamHub()
        hub.expectAudio(true)
        hub.setVideoConfig(VideoConfig(1280, 720, sps, pps))
        assertNull(hub.currentInitSegment())
        val init = hub.awaitInitSegment(timeoutMs = 3000, audioGraceMs = 200)
        assertNotNull(init)
        assertNull(hub.publishedAudioRate)
    }

    @Test
    fun serializerStartsAtKeyFrameAndStitchesDiscontinuities() {
        val s = Mp4FragmentSerializer(audioSampleRate = 48000)
        fun v(pts: Long, key: Boolean) = EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, byteArrayOf(1), pts, 40_000, key)
        assertNull(s.serialize(v(10_000_000, false))) // never start with a delta frame
        val first = s.serialize(v(10_040_000, true))!!
        assertEquals(0L, tfdt(first))
        val second = s.serialize(v(10_080_000, false))!!
        assertEquals(3600L, tfdt(second)) // 40 ms at 90 kHz
        // 5 seconds were dropped; the timeline continues seamlessly.
        val resumed = s.serialize(v(15_080_000, true), discontinuity = true)!!
        assertEquals(7200L, tfdt(resumed))
        // Audio follows the same (stitched) clock and is contiguous.
        val a1 = s.serialize(EncodedSample(FragmentedMp4.AUDIO_TRACK_ID, byteArrayOf(9), 15_090_000, 21_333, true))!!
        val a2 = s.serialize(EncodedSample(FragmentedMp4.AUDIO_TRACK_ID, byteArrayOf(9), 15_111_333, 21_333, true))!!
        assertEquals(tfdt(a1) + 1024, tfdt(a2))
        assertEquals((90_000L * 48_000) / 1_000_000, tfdt(a1)) // 90 ms after the stitched point
    }

    private fun tfdt(fragment: ByteArray): Long {
        val i = indexOf(fragment, "tfdt") - 4
        return ByteBuffer.wrap(fragment, i + 12, 8).long
    }

    @Test
    fun slowClientDropsBacklogAndResyncsOnKeyFrame() {
        val hub = StreamHub()
        var keyRequests = 0
        hub.keyFrameRequester = { keyRequests++ }
        val q = LiveClientQueue(hub, maxLagUs = 500_000)
        q.onSample(EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, byteArrayOf(1), 0, 33_000, false)) // ignored: not a key frame
        assertNull(q.take(0))
        q.onSample(EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, byteArrayOf(1), 33_000, 33_000, true))
        for (i in 2..30) q.onSample(EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, byteArrayOf(1), i * 33_000L, 33_000, false))
        // More than 500 ms queued -> everything dropped, waiting for a key frame.
        assertTrue(keyRequests >= 1)
        assertNull(q.take(0))
        q.onSample(EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, byteArrayOf(1), 1_100_000, 33_000, true))
        val item = q.take(0)!!
        assertTrue(item.sample.isKey)
        assertTrue(item.discontinuity)
    }

    @Test
    fun hlsPlaylistSlidesOverSegments() {
        val hub = StreamHub()
        hub.setVideoConfig(VideoConfig(1280, 720, sps, pps))
        val seg = HlsSegmenter(hub, targetDurationUs = 1_000_000, playlistSize = 3)
        var pts = 0L
        repeat(8 * 30) { i ->
            seg.onSample(EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, byteArrayOf(1, 2, 3), pts, 33_333, i % 30 == 0))
            pts += 33_333
        }
        val playlist = seg.playlist()
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\""))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:1"))
        assertEquals(3, playlist.lines().count { it.startsWith("#EXTINF:1.0") })
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:4"))
        assertNotNull(seg.segment(6))
        assertNull(seg.segment(99))
    }
}
