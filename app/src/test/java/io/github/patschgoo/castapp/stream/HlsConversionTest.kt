package io.github.patschgoo.castapp.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HlsConversionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun video(ptsUs: Long, key: Boolean, durationUs: Long = 40_000) =
        EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, byteArrayOf(0, 0, 0, 1, 0x65), ptsUs, durationUs, key)

    @Test
    fun segmenterCutsAtKeyFramesAfterTheMinimumLength() {
        val segments = ArrayList<Pair<Long, Int>>()
        var keyRequests = 0
        val seg = VodSegmenter(null, minSegmentUs = 4_000_000, maxSegmentUs = 8_000_000, requestKeyFrame = { keyRequests++ }) { d, data ->
            segments += d to data.size
        }
        seg.add(video(0, key = false)) // before the first key frame: dropped
        // 25 fps, key frames every 2 s, for 10 s.
        for (f in 0 until 250) seg.add(video(1_000_000 + f * 40_000L, key = f % 50 == 0))
        seg.finish()
        assertEquals(listOf(4_000_000L, 4_000_000L, 2_000_000L), segments.map { it.first })
        assertTrue(segments.all { it.second > 0 })
        assertEquals(0, keyRequests)
    }

    @Test
    fun segmenterAsksForAKeyFrameWhenASegmentGetsLong() {
        var keyRequests = 0
        val durations = ArrayList<Long>()
        val seg = VodSegmenter(null, minSegmentUs = 4_000_000, maxSegmentUs = 8_000_000, requestKeyFrame = { keyRequests++ }) { d, _ -> durations += d }
        seg.add(video(0, key = true))
        for (f in 1 until 300) seg.add(video(f * 40_000L, key = false)) // 12 s without a key frame
        assertEquals(1, keyRequests) // only once per segment
        seg.add(video(300 * 40_000L, key = true))
        assertEquals(listOf(12_000_000L), durations)
    }

    @Test
    fun storeServesAGrowingEventPlaylistThenEnds() {
        val store = SegmentStore(tmp.newFolder("c1"), aheadLimit = 3, keepBehind = 1)
        assertNull(store.playlist(minSegments = 1, timeoutMs = 0)) // nothing yet
        store.setInit(byteArrayOf(1, 2, 3))
        store.add(4_000_000, byteArrayOf(10))
        store.add(3_500_000, byteArrayOf(11))
        val p = store.playlist(minSegments = 2)!!
        assertTrue(p.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertTrue(p.contains("#EXT-X-START:TIME-OFFSET=0"))
        assertTrue(p.contains("#EXT-X-MAP:URI=\"init.mp4\""))
        assertTrue(p.contains("#EXTINF:4.000,\nseg0.m4s"))
        assertTrue(p.contains("#EXTINF:3.500,\nseg1.m4s"))
        assertFalse(p.contains("#EXT-X-ENDLIST"))
        assertArrayEquals(byteArrayOf(11), store.segment(1))
        store.finish()
        assertTrue(store.playlist()!!.endsWith("#EXT-X-ENDLIST\n"))
        assertNull(store.segment(5, timeoutMs = 10)) // never coming
    }

    @Test
    fun storeHoldsTheConverterBackAndDeletesOldSegments() {
        val dir = tmp.newFolder("c2")
        val store = SegmentStore(dir, aheadLimit = 3, keepBehind = 1)
        store.setInit(byteArrayOf(0))
        repeat(3) { store.add(4_000_000, byteArrayOf(it.toByte())) }
        assertTrue(store.isAhead) // 3 ready, the TV has fetched none
        assertFalse(store.awaitRoom(timeoutMs = 20))
        assertNotNull(store.segment(0))
        assertTrue(store.awaitRoom(timeoutMs = 20))
        store.add(4_000_000, byteArrayOf(3))
        store.add(4_000_000, byteArrayOf(4))
        assertArrayEquals(byteArrayOf(3), store.segment(3))
        // The TV is at 3: everything before 2 is gone.
        assertFalse(java.io.File(dir, "seg0.m4s").exists())
        assertFalse(java.io.File(dir, "seg1.m4s").exists())
        assertTrue(java.io.File(dir, "seg2.m4s").exists())
        assertNull(store.segment(0))
        store.close()
        assertFalse(dir.exists())
    }

    @Test
    fun segmentWaitsForTheConverter() {
        val store = SegmentStore(tmp.newFolder("c3"))
        val t = Thread {
            Thread.sleep(50)
            store.add(4_000_000, byteArrayOf(42))
        }
        t.start()
        assertArrayEquals(byteArrayOf(42), store.segment(0, timeoutMs = 5000))
        t.join()
    }

    @Test
    fun conversionsAreReusedReplacedAndCleanedUp() {
        var now = 0L
        val stopped = ArrayList<String>()
        val conversions = HlsConversions(tmp.newFolder("root"), clock = { now })
        val a = conversions.obtain("1/0/1080/0") { c -> { stopped += c.key } }
        val again = conversions.obtain("1/0/1080/0") { error("must not start twice") }
        assertSame(a, again)
        assertSame(a, conversions.find("1/0/1080/0"))
        assertSame(a, conversions[a.id])
        // Seeking: a new conversion from the new position, the old one stops.
        val b = conversions.obtain("1/60000/1080/0") { c -> { stopped += c.key } }
        assertNotSame(a, b)
        assertTrue(a.stopped)
        assertEquals(listOf("1/0/1080/0"), stopped)
        assertNull(conversions.find("1/0/1080/0"))
        assertEquals(1, conversions.size)
        // Not fetched from for a long time: cleaned up.
        now += HlsConversions.IDLE_MS / 2
        conversions.sweep()
        assertFalse(b.stopped)
        now += HlsConversions.IDLE_MS
        conversions.sweep()
        assertTrue(b.stopped)
        assertEquals(0, conversions.size)
        assertNull(conversions.current)
        // Casting ends: everything stops.
        val c = conversions.obtain("2/0/720/1") { cc -> { stopped += cc.key } }
        conversions.stopAll()
        assertTrue(c.stopped)
        assertEquals(listOf("1/0/1080/0", "1/60000/1080/0", "2/0/720/1"), stopped)
    }

    @Test
    fun aFailedStartLeavesNothingBehind() {
        val conversions = HlsConversions(tmp.newFolder("root2"))
        try {
            conversions.obtain("k") { throw IllegalStateException("No video track") }
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, conversions.size)
        assertNull(conversions.current)
    }
}
