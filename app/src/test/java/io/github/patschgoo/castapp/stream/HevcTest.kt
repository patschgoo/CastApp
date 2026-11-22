package io.github.patschgoo.castapp.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class HevcTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Parameter sets of a 1280x720 Main profile stream from x265 (ffmpeg -c:v libx265).
    private val vps = hex("40010c01ffff01600000030090000003000003005dba0240")
    private val sps = hex("42010101600000030090000003000003005da00280802d165ba4a4c2f0168080000003008000000f04")
    private val pps = hex("4401c073c189")

    /** The hvcC box ffmpeg's MP4 muxer writes for the same stream (`-tag:v hvc1`). */
    private val ffmpegHvcC = hex(
        "00000075687663430101600000009000000000005df000fcfdf8f800000f03a00001001840010c01ffff01600000030090000003000003005dba0240" +
            "a10001002942010101600000030090000003000003005da00280802d165ba4a4c2f0168080000003008000000f04a2000100064401c073c189",
    )

    @Test
    fun parsesX265Sps() {
        val info = HevcUtils.parseSps(sps)!!
        assertEquals(0, info.profileSpace)
        assertEquals(0, info.tierFlag)
        assertEquals(1, info.profileIdc) // Main
        assertEquals(0x60000000, info.compatibilityFlags)
        assertEquals(0x900000000000L, info.constraintFlags)
        assertEquals(93, info.levelIdc) // 3.1
        assertEquals(1, info.chromaFormatIdc)
        assertEquals(8, info.bitDepthLuma)
        assertEquals(8, info.bitDepthChroma)
        assertEquals(1, info.maxSubLayers)
        assertTrue(info.temporalIdNesting)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals("hvc1.1.6.L93.90", HevcUtils.codecString(sps))
    }

    @Test
    fun hvcCMatchesFfmpeg() {
        val record = HevcUtils.decoderConfigurationRecord(vps, sps, pps)
        assertArrayEquals(ffmpegHvcC.copyOfRange(8, ffmpegHvcC.size), record)
        // And inside the init segment, with the hvc1 sample entry.
        val init = FragmentedMp4.initSegment(VideoConfig(1280, 720, sps, pps, VideoCodec.HEVC, vps), null)
        val at = indexOf(init, "hvcC") - 4
        assertArrayEquals(ffmpegHvcC, init.copyOfRange(at, at + ffmpegHvcC.size))
        assertTrue(indexOf(init, "hvc1") > 0)
        assertEquals(-1, indexOf(init, "avc1")) // neither as sample entry nor as brand
        assertEquals(-1, indexOf(init, "avcC"))
    }

    @Test
    fun hvc1SampleEntryLayout() {
        val init = FragmentedMp4.initSegment(VideoConfig(1280, 720, sps, pps, VideoCodec.HEVC, vps), null)
        val entry = indexOf(init, "hvc1") - 4
        val size = ByteBuffer.wrap(init, entry, 4).int
        val b = ByteBuffer.wrap(init, entry + 8, size - 8)
        repeat(6) { assertEquals(0, b.get().toInt()) } // reserved
        assertEquals(1, b.short.toInt()) // data_reference_index
        repeat(16) { assertEquals(0, b.get().toInt()) } // pre_defined + reserved
        assertEquals(1280, b.short.toInt())
        assertEquals(720, b.short.toInt())
        assertEquals(0x00480000, b.int)
        assertEquals(0x00480000, b.int)
        assertEquals(0, b.int)
        assertEquals(1, b.short.toInt()) // frame_count
        repeat(32) { b.get() } // compressorname
        assertEquals(0x18, b.short.toInt()) // depth
        assertEquals(-1, b.short.toInt())
        // The hvcC box follows right away and fills the entry.
        assertEquals(size - 86, b.int)
        assertEquals("hvcC", String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII))
    }

    /** An SPS built by hand: Main 10, high tier, level 5.1, two sub-layers, a conformance window. */
    @Test
    fun parsesConstructedMain10Sps() {
        val w = Bits()
        w.u(0, 4) // vps id
        w.u(1, 3) // max_sub_layers_minus1 = 1
        w.u(0, 1) // temporal_id_nesting
        w.u(0, 2); w.u(1, 1); w.u(2, 5) // profile space 0, high tier, Main 10
        w.u(0x20000000, 32) // compatible with Main 10 (flag[2])
        w.u(0xB000, 16); w.u(0, 32) // progressive, non-packed, frame-only
        w.u(153, 8) // level 5.1
        w.u(1, 1); w.u(1, 1) // sub-layer 0: profile and level present
        repeat(7) { w.u(0, 2) } // reserved_zero_2bits for i = 1..7
        repeat(88) { w.u(1, 1) } // sub_layer profile (skipped)
        w.u(120, 8) // sub_layer level
        w.ue(0) // sps id
        w.ue(1) // 4:2:0
        w.ue(3840); w.ue(2176)
        w.u(1, 1); w.ue(0); w.ue(0); w.ue(0); w.ue(8) // crop 16 lines at the bottom
        w.ue(2); w.ue(2) // 10-bit
        w.u(1, 1) // stop bit
        val nal = byteArrayOf(0x42, 0x01) + escape(w.bytes())
        val info = HevcUtils.parseSps(nal)!!
        assertEquals(2, info.profileIdc)
        assertEquals(1, info.tierFlag)
        assertEquals(153, info.levelIdc)
        assertEquals(2, info.maxSubLayers)
        assertFalse(info.temporalIdNesting)
        assertEquals(3840, info.width)
        assertEquals(2160, info.height)
        assertEquals(10, info.bitDepthLuma)
        assertEquals("hvc1.2.4.H153.B0", HevcUtils.codecString(info))

        val record = HevcUtils.decoderConfigurationRecord(vps, nal, pps)
        assertEquals(1, record[0].toInt())
        assertEquals((1 shl 5) or 2, record[1].toInt() and 0xFF) // tier + profile
        assertEquals(0x20000000, ByteBuffer.wrap(record, 2, 4).int)
        assertEquals(0xB0, record[6].toInt() and 0xFF)
        assertEquals(153, record[12].toInt() and 0xFF)
        assertEquals(0xFD, record[16].toInt() and 0xFF) // chroma 4:2:0
        assertEquals(0xFA, record[17].toInt() and 0xFF) // luma 10 bit
        assertEquals(0xFA, record[18].toInt() and 0xFF)
        assertEquals((2 shl 3) or 3, record[21].toInt() and 0xFF) // 2 temporal layers, not nested, 4-byte lengths
        assertEquals(3, record[22].toInt())
        assertEquals(0xA0, record[23].toInt() and 0xFF) // VPS array, complete
    }

    @Test
    fun samplesDropParameterSetsAndFindKeyFrames() {
        val aud = byteArrayOf(0x46, 0x01, 0x50)
        val idr = byteArrayOf(0x28, 0x01, 0x11, 0x22) // IDR_N_LP (20)
        val trail = byteArrayOf(0x02, 0x01, 0x33) // TRAIL_R (1)
        val nals = listOf(aud, vps, sps, pps, idr)
        assertTrue(HevcUtils.containsKeyFrame(nals))
        assertFalse(HevcUtils.containsKeyFrame(listOf(trail)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 4) + idr, HevcUtils.toSample(nals))
        assertEquals(HevcUtils.NAL_VPS, HevcUtils.nalType(vps))
        assertEquals(HevcUtils.NAL_SPS, HevcUtils.nalType(sps))
        assertEquals(HevcUtils.NAL_PPS, HevcUtils.nalType(pps))
    }

    @Test
    fun masterPlaylistNamesTheCodecs() {
        val text = HlsSegmenter.masterPlaylist(VideoConfig(1280, 720, sps, pps, VideoCodec.HEVC, vps), true, 3_000_000)
        assertTrue(text, text.contains("CODECS=\"hvc1.1.6.L93.90,mp4a.40.2\""))
        assertTrue(text.contains("RESOLUTION=1280x720"))
        assertTrue(text.contains("BANDWIDTH=3128000"))
        assertTrue(text.trim().endsWith("live.m3u8"))
    }

    @Test
    fun hevcBitrateIsAboutAThirdLower() {
        val s = io.github.patschgoo.castapp.settings.CastSettings()
        assertEquals(4_500_000, s.bitrateFor(hevc = false))
        assertEquals(2_900_000, s.bitrateFor(hevc = true))
        assertEquals(6_000_000, s.copy(bitrateMbps = 6).bitrateFor(hevc = true)) // picked by the user: kept
        assertEquals(650_000, AdaptiveBitrate.minBpsFor(VideoCodec.HEVC))
    }

    /**
     * Muxes a real x265 stream with our writer and lets ffprobe/ffmpeg check it.
     * Needs ffmpeg with libx265 (skipped otherwise, e.g. in CI).
     */
    @Test
    fun ffmpegDecodesOurHevcFragments() {
        val dir = File(System.getProperty("java.io.tmpdir"), "castapp-hevc-test").apply { mkdirs() }
        val raw = File(dir, "in.hevc")
        assumeTrue(run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
            "-frames:v", "45", "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "keyint=15:bframes=0:log-level=error",
            "-pix_fmt", "yuv420p", "-f", "hevc", raw.path) == 0)
        val nals = AvcUtils.splitAnnexB(raw.readBytes())
        val v = nals.first { HevcUtils.nalType(it) == HevcUtils.NAL_VPS }
        val s = nals.first { HevcUtils.nalType(it) == HevcUtils.NAL_SPS }
        val p = nals.first { HevcUtils.nalType(it) == HevcUtils.NAL_PPS }
        val out = ByteArrayOutputStream()
        out.write(FragmentedMp4.initSegment(VideoConfig(1280, 720, s, p, VideoCodec.HEVC, v), null))
        var seq = 1
        for ((i, au) in accessUnits(nals).withIndex()) {
            val sample = HevcUtils.toSample(au)
            out.write(FragmentedMp4.fragment(seq++, FragmentedMp4.VIDEO_TRACK_ID, i * 3000L, listOf(FragmentSample(sample, 3000, HevcUtils.containsKeyFrame(au)))))
        }
        val mp4 = File(dir, "out.mp4").apply { writeBytes(out.toByteArray()) }
        val probe = runOutput("ffprobe", "-v", "error", "-count_frames", "-show_entries",
            "stream=codec_name,codec_tag_string,profile,width,height,nb_read_frames", "-of", "default=nw=1", mp4.path)
        assertNotNull(probe)
        assertTrue(probe, probe!!.contains("codec_name=hevc"))
        assertTrue(probe, probe.contains("codec_tag_string=hvc1"))
        assertTrue(probe, probe.contains("profile=Main"))
        assertTrue(probe, probe.contains("width=1280") && probe.contains("height=720"))
        assertTrue(probe, probe.contains("nb_read_frames=45"))
        // Decoding the whole file must not produce a single error.
        val errors = runOutput("ffmpeg", "-v", "error", "-i", mp4.path, "-f", "null", "-")
        assertEquals("", errors?.trim())
    }

    /** Groups NAL units into access units (a new one starts with parameter sets, an AUD, or a first slice). */
    private fun accessUnits(nals: List<ByteArray>): List<List<ByteArray>> {
        val out = ArrayList<List<ByteArray>>()
        var current = ArrayList<ByteArray>()
        var hasSlice = false
        for (nal in nals) {
            val t = HevcUtils.nalType(nal)
            val firstSlice = t < 32 && nal.size > 2 && (nal[2].toInt() and 0x80) != 0
            if (hasSlice && (t in 32..35 || t == HevcUtils.NAL_SEI_PREFIX || firstSlice)) {
                out += current
                current = ArrayList()
                hasSlice = false
            }
            current += nal
            if (t < 32) hasSlice = true
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun run(vararg cmd: String): Int = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        if (p.waitFor(60, TimeUnit.SECONDS)) p.exitValue() else -1
    } catch (_: Exception) {
        -1
    }

    private fun runOutput(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor(60, TimeUnit.SECONDS)) text else null
    } catch (_: Exception) {
        null
    }

    private fun indexOf(data: ByteArray, fourcc: String): Int {
        val needle = fourcc.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - 4) {
            for (j in 0 until 4) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Inserts emulation prevention bytes. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits {
        private val bits = ArrayList<Int>()
        fun u(value: Long, n: Int) { for (i in n - 1 downTo 0) bits += ((value ushr i) and 1).toInt() }
        fun u(value: Int, n: Int) = u(value.toLong() and 0xFFFFFFFFL, n)
        fun ue(value: Int) {
            val v = value + 1
            val len = 32 - Integer.numberOfLeadingZeros(v)
            repeat(len - 1) { bits += 0 }
            u(v, len)
        }
        fun bytes(): ByteArray {
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or bits[i * 8 + k] }.toByte() }
        }
    }
}
