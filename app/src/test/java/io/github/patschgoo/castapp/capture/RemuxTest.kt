package io.github.patschgoo.castapp.capture

import io.github.patschgoo.castapp.capture.Remux.VideoRoute
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which pictures are copied as they are instead of converted, and how their timing is worked out. */
class RemuxTest {
    // SPS NAL units of single frames x264 encoded (ffmpeg), in hex.
    private val p720High32 = sps("67640020acd9405005bb0110000003001000000640f1831960")
    private val p1080High41 = sps("67640029acd940780227e5c044000003000400000300c83c60c658")
    private val p1080High42 = sps("6764002aacd940780227e5c044000003000400000301903c60c658")
    private val i1080High40 = sps("67640028acd94078044fde0220000003002000000643e2c5b2c0")
    private val high10 = sps("676e000aa6cd94426c0440000003004000000c83c4896580")
    private val high422 = sps("677a000abcd94426c044000003000400000300c83c489658")
    private val uhdLevel51 = sps("6742c033da00f0010fb011000003000100000300320f1832a0")
    private val sdBaseline = sps("6742c01ed900b4126c0440000003004000000c83c58b92")

    private fun sps(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun route(sps: ByteArray, maxHeight: Int = 1080, mime: String = "video/avc", rotation: Int = 0, decodeOrder: Boolean = false, allowed: Boolean = true) =
        Remux.videoRoute(mime, Remux.parseSps(sps), rotation, maxHeight, decodeOrder, allowed)

    @Test
    fun readsTheSps() {
        val f = Remux.parseSps(p720High32)!!
        assertEquals(100, f.profileIdc)
        assertEquals(32, f.levelIdc)
        assertEquals(1280, f.width)
        assertEquals(720, f.height)
        assertEquals(8, f.bitDepth)
        assertEquals(1, f.chromaFormat)
        assertTrue(f.progressive)
        // 1088 coded lines, cropped to 1080.
        assertEquals(1080, Remux.parseSps(p1080High41)!!.height)
        assertEquals(10, Remux.parseSps(high10)!!.bitDepth)
        assertEquals(2, Remux.parseSps(high422)!!.chromaFormat)
        assertFalse(Remux.parseSps(i1080High40)!!.progressive)
        // With a start code in front (csd-0 from MediaExtractor) it's the same.
        assertEquals(Remux.parseSps(p720High32), Remux.parseSps(byteArrayOf(0, 0, 0, 1) + p720High32))
        assertNull(Remux.parseSps(byteArrayOf(0x68, 1, 2)))
    }

    @Test
    fun tvRecordingsAndFullHdFilesAreCopied() {
        // ARD / ZDF HD: 720p50 High, level 3.2.
        assertEquals(VideoRoute.Copy, route(p720High32))
        // A 1080p24 film, level 4.1.
        assertEquals(VideoRoute.Copy, route(p1080High41))
        // SD in Baseline.
        assertEquals(VideoRoute.Copy, route(sdBaseline))
    }

    @Test
    fun whatAChromecastMayNotPlayIsConverted() {
        assertTrue(route(p1080High42) is VideoRoute.Convert) // 1080p50: level 4.2
        assertTrue(route(high10) is VideoRoute.Convert) // 10-bit
        assertTrue(route(high422) is VideoRoute.Convert) // 4:2:2
        assertTrue(route(uhdLevel51) is VideoRoute.Convert) // 4K
        assertTrue(route(i1080High40) is VideoRoute.Convert) // 1080i (private HD channels)
        assertEquals(VideoRoute.Convert("not H.264"), route(p720High32, mime = "video/mpeg2"))
        assertEquals(VideoRoute.Convert("not H.264"), route(p720High32, mime = "video/hevc"))
        assertEquals(VideoRoute.Convert("no SPS"), Remux.videoRoute("video/avc", null))
    }

    @Test
    fun aSmallerQualityOrARotationNeedsTheConverter() {
        assertTrue(route(p1080High41, maxHeight = 720) is VideoRoute.Convert)
        assertEquals(VideoRoute.Copy, route(p720High32, maxHeight = 720))
        assertTrue(route(p720High32, maxHeight = 480) is VideoRoute.Convert)
        assertTrue(route(p720High32, rotation = 90) is VideoRoute.Convert)
        assertEquals(VideoRoute.Copy, route(p720High32, rotation = 360))
        assertTrue(Remux.fits(1080, 1920, 1080)) // portrait
        assertFalse(Remux.fits(1920, 1200, 1080))
    }

    @Test
    fun aviTimesAndARefusalByTheTvMeanConverting() {
        assertEquals(VideoRoute.Convert("no presentation times (AVI)"), route(p720High32, decodeOrder = true))
        assertEquals(VideoRoute.Convert("copying didn't play on this TV"), route(p720High32, allowed = false))
        // The facts as the probe keeps them say the same.
        val facts = Remux.VideoFacts("video/avc", Remux.parseSps(p720High32))
        assertEquals(VideoRoute.Copy, Remux.videoRoute(facts, 1080))
        assertTrue(Remux.videoRoute(facts, 1080, allowed = false) is VideoRoute.Convert)
        assertTrue(Remux.videoRoute(null, 1080) is VideoRoute.Convert)
        val check = ConvertCheck(null, video = facts)
        assertTrue(check.copiesPicture(1080))
        assertFalse(check.copiesPicture(480))
        assertFalse(check.copiesPicture(1080, allowed = false))
        assertFalse(ConvertCheck(ConvertProblem.NoVideoTrack, video = facts).copiesPicture(1080))
    }

    @Test
    fun onlyPlainAacIsCopied() {
        val lcStereo48 = byteArrayOf(0x11, 0x90.toByte()) // AAC-LC, 48 kHz, 2 channels
        val lcMono44 = byteArrayOf(0x12, 0x08) // AAC-LC, 44.1 kHz, 1 channel
        val lc51 = byteArrayOf(0x11, 0xB0.toByte()) // AAC-LC, 48 kHz, 6 channels
        val heAac = byteArrayOf(0x2B, 0x92.toByte(), 0x08, 0x00) // HE-AAC (SBR), 24 kHz core
        assertEquals(Remux.AacConfig(2, 48000, 2), Remux.parseAsc(lcStereo48))
        assertTrue(Remux.canCopyAudio("audio/mp4a-latm", lcStereo48, 2, 48000))
        assertTrue(Remux.canCopyAudio("audio/mp4a-latm", lcMono44, 1, 44100))
        assertFalse(Remux.canCopyAudio("audio/mp4a-latm", lc51, 6, 48000))
        assertFalse(Remux.canCopyAudio("audio/mp4a-latm", heAac, 2, 48000))
        assertFalse(Remux.canCopyAudio("audio/mp4a-latm", lcStereo48, 2, 44100)) // rate doesn't match
        assertFalse(Remux.canCopyAudio("audio/mp4a-latm", null, 2, 48000))
        // MP2 and AC-3 of TV recordings are converted to AAC.
        assertFalse(Remux.canCopyAudio("audio/mpeg-L2", lcStereo48, 2, 48000))
        assertFalse(Remux.canCopyAudio("audio/ac3", lcStereo48, 2, 48000))
    }

    // ---- decoding times --------------------------------------------------------------------------

    private fun times(ptsInFrames: List<Int>, window: Int = 16, frameUs: Long = 40_000): List<DecodeTimes.Timed<Int>> {
        val d = DecodeTimes<Int>(window)
        val out = ArrayList<DecodeTimes.Timed<Int>>()
        ptsInFrames.forEachIndexed { i, p -> out += d.add(i, p * frameUs) }
        out += d.flush()
        return out
    }

    private fun assertSane(out: List<DecodeTimes.Timed<Int>>, frameUs: Long = 40_000) {
        // Decoding order kept, decoding times strictly increasing, no frame shown before it's decoded.
        assertEquals(out.indices.toList(), out.map { it.item })
        out.zipWithNext().forEach { (a, b) -> assertTrue("${a.dtsUs} < ${b.dtsUs}", a.dtsUs < b.dtsUs) }
        out.forEach { assertEquals(it.ptsUs, it.dtsUs + it.offsetUs) }
        // A frame's decoding time step is a frame (constant frame rate in, constant out).
        out.zipWithNext().forEach { (a, b) -> assertEquals(frameUs, b.dtsUs - a.dtsUs) }
    }

    @Test
    fun noBFramesNoOffsets() {
        val out = times((0 until 40).toList())
        assertSane(out)
        assertTrue(out.all { it.offsetUs == 0L })
        assertEquals(0L, out.first().dtsUs)
    }

    @Test
    fun bFramesAreShownLaterThanDecoded() {
        // I0 P3 B1 B2 P6 B4 B5 ...: one frame of delay.
        val pts = listOf(0, 3, 1, 2, 6, 4, 5, 9, 7, 8, 12, 10, 11)
        val out = times(pts)
        assertSane(out)
        assertEquals(-40_000L, out[0].dtsUs)
        assertEquals(listOf(1, 3, 0, 0, 3, 0, 0), out.take(7).map { (it.offsetUs / 40_000).toInt() })
    }

    @Test
    fun bPyramidsToo() {
        // x264's b-pyramid: I0 P4 B2 b1 b3 P8 B6 b5 b7.
        val pts = listOf(0, 4, 2, 1, 3, 8, 6, 5, 7, 12, 10, 9, 11)
        val out = times(pts)
        assertSane(out)
        assertEquals(-80_000L, out[0].dtsUs) // two frames of delay
        assertTrue(out.all { it.offsetUs >= 0 })
    }

    @Test
    fun aDeeperReorderLaterIsClampedNotBroken() {
        // The first frames have no B-frames, later ones do: their offsets can't go below 0, decoding stays ordered.
        val pts = (0 until 20).toList() + listOf(22, 20, 21, 25, 23, 24)
        val out = times(pts, window = 4)
        out.zipWithNext().forEach { (a, b) -> assertTrue(a.dtsUs < b.dtsUs) }
        assertTrue(out.all { it.offsetUs >= 0 })
    }

    // ---- MP4 samples ---------------------------------------------------------------------------------

    private val spsNal = p720High32
    private val ppsNal = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte(), 0xCB.toByte(), 0x22, 0xC0.toByte())
    private val otherPps = byteArrayOf(0x68, 0x01, 0x02)
    private val aud = byteArrayOf(0x09, 0x10)
    private val sei = byteArrayOf(0x06, 0x05, 0x01, 0x02)
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    private val slice = byteArrayOf(0x41, 0x9A.toByte(), 0x02)
    private val filler = byteArrayOf(0x0C, 0xFF.toByte(), 0xFF.toByte())

    private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, n -> acc + byteArrayOf(0, 0, 0, 1) + n }
    private fun avcc(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, n ->
        acc + byteArrayOf((n.size ushr 24).toByte(), (n.size ushr 16).toByte(), (n.size ushr 8).toByte(), n.size.toByte()) + n
    }

    @Test
    fun accessUnitsBecomeMp4Samples() {
        val samples = AvcSamples(spsNal, ppsNal)
        // A TV recording's key frame: AUD, SPS, PPS, SEI, IDR. The repeated SPS/PPS and the AUD go, the SEI stays.
        val key = samples.convert(annexB(aud, spsNal, ppsNal, sei, idr))
        assertArrayEquals(avcc(sei, idr), key.data)
        assertTrue(key.isIdr)
        // A second PPS stays in the sample (players take it in-band), filler goes.
        val p = samples.convert(annexB(aud, otherPps, slice, filler))
        assertArrayEquals(avcc(otherPps, slice), p.data)
        assertFalse(p.isIdr)
        // Already length-prefixed (some readers): read by lengths.
        assertArrayEquals(avcc(sei, idr), samples.convert(avcc(spsNal, sei, idr)).data)
        assertEquals(listOf(sei.toList(), idr.toList()), AvcSamples.nalUnits(avcc(sei, idr)).map { it.toList() })
    }

    @Test
    fun theCopierStartsAtAKeyFrameWithItsParameterSets() {
        val started = ArrayList<Remux.AvcFacts>()
        val out = ArrayList<Pair<Long, Boolean>>()
        // No csd: the parameter sets are in-band only.
        val copier = PictureCopier(null, null, onStart = { f, _, _ -> started += f }, onSample = { _, dts, key, off -> out += (dts + off) to key })
        // A seek may land on frames before the key frame: skipped.
        assertTrue(copier.add(annexB(slice), 0, sync = false))
        assertFalse(copier.started)
        assertTrue(copier.add(annexB(aud, spsNal, ppsNal, idr), 40_000, sync = true))
        assertTrue(copier.started)
        assertEquals(1280, started.single().width)
        // An open GOP's B-frame shown before the key frame: dropped.
        assertTrue(copier.add(annexB(slice), 20_000, sync = false))
        assertTrue(copier.add(annexB(slice), 80_000, sync = false))
        copier.finish()
        assertEquals(listOf(40_000L to true, 80_000L to false), out)
    }

    @Test
    fun theCopierGivesUpWhereTheConverterHasToDoIt() {
        val none: (Remux.AvcFacts, ByteArray, ByteArray) -> Unit = { _, _, _ -> }
        val drop: (ByteArray, Long, Boolean, Long) -> Unit = { _, _, _, _ -> }
        // No key frame soon enough.
        val noKey = PictureCopier(spsNal, ppsNal, lookahead = 3, onStart = none, onSample = drop)
        repeat(3) { assertTrue(noKey.add(annexB(slice), it * 40_000L, sync = false)) }
        assertFalse(noKey.add(annexB(slice), 160_000, sync = false))
        assertNotNull(noKey.refused)
        // The stream's own SPS is 10-bit although the file said nothing.
        val deep = PictureCopier(null, null, onStart = none, onSample = drop)
        assertFalse(deep.add(annexB(high10, ppsNal, idr), 0, sync = true))
        assertFalse(deep.started)
        // A 1080p stream at the 720p quality.
        val big = PictureCopier(byteArrayOf(0, 0, 0, 1) + p1080High41, ppsNal, maxHeight = 720, onStart = none, onSample = drop)
        assertFalse(big.add(annexB(idr), 0, sync = true))
    }

    // ---- AVI: display order ---------------------------------------------------------------------------

    @Test
    fun aviPicturesGetTheirDisplayTimes() {
        // AVI times are the decoding order: I0 P3 B1 B2 P6 B4 B5 at 0, 1, 2 ... A decoder hands back
        // I0 B1 B2 P3 B4 B5 P6, each with its sample's time; they're shown at 0, 1, 2, 3, ...
        val t = DisplayOrderTimes()
        (0L..6L).forEach { t.queued(it) }
        val decoderTimes = listOf(0L, 2, 3, 1, 5, 6, 4)
        assertEquals((0L..6L).toList(), decoderTimes.map { t.shown(it) })
    }

    @Test
    fun aDropFrameTheDecoderSwallowsDoesNotDelayTheRest() {
        // Sample 1 never comes back (an empty "drop" frame); the others do, in order.
        val t = DisplayOrderTimes(window = 3)
        (0L..9L).forEach { t.queued(it) }
        val shown = listOf(0L, 2, 3, 4, 5, 6, 7, 8, 9).map { t.shown(it) }
        assertEquals(listOf(0L, 1, 2), shown.take(3))
        // Once three later pictures passed it, its time is given up: the rest catch up with their own.
        assertEquals(listOf(7L, 8, 9), shown.takeLast(3))
        assertTrue(shown.zipWithNext().all { (a, b) -> a < b })
    }
}
