package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.tv.DecoderReport.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderReportTest {
    /** Roughly a 2019 Shield: hardware H.264/HEVC Main 10/VP9/MPEG-2/MPEG-4, AV1 and VP8 only in software. */
    private val shieldLike = listOf(
        Decoder("video/avc", hardware = true, profiles = setOf(0x1, 0x2, 0x8)),
        Decoder("video/avc", hardware = false, profiles = setOf(0x1, 0x2, 0x8)),
        Decoder("video/hevc", hardware = true, profiles = setOf(0x1, 0x1000)),
        Decoder("video/x-vnd.on2.vp9", hardware = true),
        Decoder("video/mpeg2", hardware = true),
        Decoder("video/mp4v-es", hardware = true),
        Decoder("video/av01", hardware = false),
        Decoder("video/x-vnd.on2.vp8", hardware = false),
        Decoder("audio/mp4a-latm", hardware = false),
        Decoder("audio/mpeg", hardware = false),
        Decoder("audio/flac", hardware = false),
        Decoder("audio/opus", hardware = false),
        Decoder("audio/vorbis", hardware = false),
    )

    @Test
    fun shieldLikeDevice() {
        val r = DecoderReport.report(shieldLike, passthrough = setOf("ac3", "eac3", "dts", "truehd"))
        assertEquals(true, r["h264"])
        assertEquals(false, r["h264_sw"])
        // No decoder lists High 10: 10-bit H.264 (Hi10P) fansubs don't play here.
        assertEquals(false, r["h264_10bit"])
        assertEquals(false, r["h264_10bit_sw"])
        assertEquals(false, r["hi10p"])
        // HDR10 decoders are Main 10 decoders.
        assertEquals(true, r["hevc_10bit"])
        assertEquals(false, r["av1"])
        assertEquals(true, r["av1_sw"])
        assertEquals(true, r["vp9"])
        assertEquals(false, r["vp8"])
        assertEquals(true, r["vp8_sw"])
        assertEquals(true, r["mpeg2"])
        assertEquals(true, r["mpeg4"])
        // Passed through to the AV receiver even without a decoder.
        assertEquals(true, r["truehd"])
        assertEquals(true, r["truehd_passthrough"])
        assertEquals(true, r["ac3"])
        assertEquals(true, r["opus"])
        assertEquals(true, r["sub_ass"])
        assertEquals(false, r["sub_ass_full"])
        assertFalse("hevc is CastApp TV's own key", r.containsKey("hevc"))
    }

    @Test
    fun withoutAvReceiverTheSurroundFormatsNeedADecoder() {
        val r = DecoderReport.report(shieldLike + Decoder("audio/ac3", hardware = false), passthrough = emptySet())
        assertEquals(true, r["ac3"])
        assertEquals(false, r["ac3_passthrough"])
        assertEquals(false, r["eac3"])
        assertEquals(false, r["dts"])
        assertEquals(false, r["truehd"])
    }

    @Test
    fun hi10pDecoderAndSmallHardware() {
        val r = DecoderReport.report(
            listOf(
                Decoder("video/avc", hardware = true, profiles = setOf(0x8, 0x10)),
                // A hardware AV1 decoder that stops below 1080p counts as software-only for HD.
                Decoder("video/av01", hardware = true, fullHd = false),
                Decoder("VIDEO/HEVC", hardware = false, profiles = setOf(0x2)),
            ),
            passthrough = emptySet(),
        )
        assertEquals(true, r["h264_10bit"])
        assertEquals(true, r["hi10p"])
        assertEquals(false, r["av1"])
        assertEquals(true, r["av1_sw"])
        assertEquals(false, r["hevc_10bit"])
        assertEquals(true, r["hevc_10bit_sw"])
        assertTrue(r.values.all { it is Boolean })
    }
}
