package io.github.patschgoo.castapp.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Copied pictures with B-frames: frames shown later than they're decoded (trun composition offsets). */
class CompositionOffsetTest {
    private fun trun(fragment: ByteArray): ByteBuffer {
        val at = String(fragment, Charsets.ISO_8859_1).indexOf("trun")
        return ByteBuffer.wrap(fragment, at + 4, fragment.size - at - 4).slice()
    }

    @Test
    fun encodedPicturesKeepTheirLayout() {
        val f = FragmentedMp4.fragment(1, FragmentedMp4.VIDEO_TRACK_ID, 0, listOf(FragmentSample(byteArrayOf(1, 2), 3600, true)))
        val t = trun(f)
        assertEquals(0x000701, t.getInt(0) and 0xFFFFFF) // no offsets field
        assertEquals(1, t.getInt(4))
        assertEquals(3600, t.getInt(12))
        assertEquals(2, t.getInt(16))
    }

    @Test
    fun offsetsAreWrittenWhenAFrameIsShownLater() {
        val f = FragmentedMp4.fragment(
            1, FragmentedMp4.VIDEO_TRACK_ID, 0,
            listOf(FragmentSample(byteArrayOf(1), 3600, true, compositionOffset = 7200), FragmentSample(byteArrayOf(2), 3600, false)),
        )
        val t = trun(f)
        assertEquals(0x000F01, t.getInt(0) and 0xFFFFFF)
        assertEquals(2, t.getInt(4))
        // Per sample: duration, size, flags, offset.
        assertEquals(7200, t.getInt(12 + 12))
        assertEquals(0, t.getInt(12 + 16 + 12))
        // The data offset still points at the sample data: mdat payload right after.
        val dataOffset = t.getInt(8)
        assertEquals(1.toByte(), f[dataOffset])
        assertEquals(2.toByte(), f[dataOffset + 1])
    }

    @Test
    fun theHubAndTheSerializerCarryTheOffset() {
        val hub = StreamHub()
        val got = ArrayList<EncodedSample>()
        hub.subscribe { got += it }
        // Decoding times 0, 40, 80 ms; the first frame is shown 80 ms later.
        hub.pushVideo(byteArrayOf(1), 0, true, compositionOffsetUs = 80_000)
        hub.pushVideo(byteArrayOf(2), 40_000, false)
        hub.pushVideo(byteArrayOf(3), 80_000, false, compositionOffsetUs = 40_000)
        assertEquals(listOf(80_000L, 0L), got.map { it.compositionOffsetUs })
        assertEquals(listOf(40_000L, 40_000L), got.map { it.durationUs })
        val s = Mp4FragmentSerializer(null)
        val first = s.serialize(got[0])!!
        assertEquals(7200, trun(first).getInt(12 + 12)) // 80 ms in 90 kHz ticks
        val second = s.serialize(got[1])!!
        assertTrue(trun(second).getInt(0) and 0x800 == 0)
    }
}
