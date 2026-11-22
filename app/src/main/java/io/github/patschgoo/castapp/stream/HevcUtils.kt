package io.github.patschgoo.castapp.stream

import java.io.ByteArrayOutputStream

/** The video codec of the mirroring stream. */
enum class VideoCodec(val mime: String, val label: String) {
    H264("video/avc", "H.264"),
    HEVC("video/hevc", "HEVC"),
}

/**
 * Helpers for H.265 / HEVC bitstreams: NAL unit types, the parts of the SPS the
 * MP4 `hvcC` box and the RFC 6381 codec string need, and the `hvcC` box itself
 * (ISO/IEC 14496-15, 8.3.3).
 */
object HevcUtils {
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34
    const val NAL_AUD = 35
    const val NAL_SEI_PREFIX = 39

    /** HEVC NAL headers are two bytes; the type is bits 1..6 of the first. */
    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3F

    /** Random access points (BLA, IDR, CRA): a player can start decoding here. */
    fun isKeyType(type: Int): Boolean = type in 16..21

    fun containsKeyFrame(nals: List<ByteArray>): Boolean = nals.any { isKeyType(nalType(it)) }

    /**
     * MP4 sample data (4-byte length prefixes) for one access unit. Parameter sets
     * and access unit delimiters are left out: with the `hvc1` sample entry they
     * live only in the `hvcC` box.
     */
    fun toSample(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        for (nal in nals) {
            when (nalType(nal)) {
                NAL_VPS, NAL_SPS, NAL_PPS, NAL_AUD, -1 -> continue
            }
            val n = nal.size
            out.write(n ushr 24 and 0xFF)
            out.write(n ushr 16 and 0xFF)
            out.write(n ushr 8 and 0xFF)
            out.write(n and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** What the SPS says about the stream (only the fields we need). */
    data class SpsInfo(
        val profileSpace: Int,
        val tierFlag: Int,
        val profileIdc: Int,
        /** general_profile_compatibility_flag[0..31], flag[0] in the most significant bit. */
        val compatibilityFlags: Int,
        /** The 48 general constraint indicator bits. */
        val constraintFlags: Long,
        val levelIdc: Int,
        val maxSubLayers: Int,
        val temporalIdNesting: Boolean,
        val chromaFormatIdc: Int,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val width: Int,
        val height: Int,
    )

    /** Parses an SPS NAL unit (with its 2-byte header, without start code). Null if it is cut short. */
    fun parseSps(sps: ByteArray): SpsInfo? = try {
        val r = BitReader(unescape(sps, 2))
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.int(3)
        val nesting = r.int(1) == 1
        val profileSpace = r.int(2)
        val tier = r.int(1)
        val profile = r.int(5)
        val compat = r.long(32).toInt()
        val constraint = (r.long(32) shl 16) or r.long(16)
        val level = r.int(8)
        val subProfile = BooleanArray(maxSubLayersMinus1)
        val subLevel = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfile[i] = r.int(1) == 1
            subLevel[i] = r.int(1) == 1
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i]) r.skip(88)
            if (subLevel[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1) // separate_colour_plane_flag
        var width = r.ue()
        var height = r.ue()
        if (r.int(1) == 1) { // conformance window (in chroma units)
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            width -= subW * (left + right)
            height -= subH * (top + bottom)
        }
        val depthLuma = r.ue() + 8
        val depthChroma = r.ue() + 8
        SpsInfo(
            profileSpace, tier, profile, compat, constraint, level, maxSubLayersMinus1 + 1, nesting,
            chroma, depthLuma, depthChroma, width, height,
        )
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /**
     * RFC 6381 / ISO 14496-15 Annex E codec string, e.g. "hvc1.1.6.L93.90" for
     * Main profile, level 3.1. Browsers (MSE) and HLS players read this.
     */
    fun codecString(sps: ByteArray): String {
        val info = parseSps(sps) ?: return DEFAULT_CODEC_STRING
        return codecString(info)
    }

    fun codecString(info: SpsInfo): String = buildString {
        append("hvc1.")
        if (info.profileSpace in 1..3) append('A' + info.profileSpace - 1)
        append(info.profileIdc)
        append('.')
        append(Integer.toHexString(Integer.reverse(info.compatibilityFlags)).uppercase())
        append('.')
        append(if (info.tierFlag == 1) 'H' else 'L')
        append(info.levelIdc)
        // Constraint bytes, trailing zero bytes left out.
        val bytes = (0 until 6).map { ((info.constraintFlags ushr (40 - 8 * it)) and 0xFF).toInt() }
        val last = bytes.indexOfLast { it != 0 }
        for (i in 0..last) append('.').append("%02X".format(bytes[i]))
    }

    /** Payload of the `hvcC` box (HEVCDecoderConfigurationRecord) for these parameter sets. */
    fun decoderConfigurationRecord(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val info = parseSps(sps) ?: throw IllegalArgumentException("Unreadable HEVC SPS")
        val w = BoxWriter(64 + vps.size + sps.size + pps.size)
        w.u8(1) // configurationVersion
        w.u8((info.profileSpace shl 6) or (info.tierFlag shl 5) or info.profileIdc)
        w.u32(info.compatibilityFlags)
        w.u16((info.constraintFlags ushr 32).toInt())
        w.u32(info.constraintFlags.toInt())
        w.u8(info.levelIdc)
        w.u16(0xF000) // reserved + min_spatial_segmentation_idc = 0
        w.u8(0xFC) // reserved + parallelismType = 0 (unknown)
        w.u8(0xFC or info.chromaFormatIdc)
        w.u8(0xF8 or (info.bitDepthLuma - 8))
        w.u8(0xF8 or (info.bitDepthChroma - 8))
        w.u16(0) // avgFrameRate: unspecified
        // constantFrameRate = 0, numTemporalLayers, temporalIdNested, lengthSizeMinusOne = 3
        w.u8(((info.maxSubLayers and 7) shl 3) or ((if (info.temporalIdNesting) 1 else 0) shl 2) or 3)
        w.u8(3) // numOfArrays
        for ((type, nal) in listOf(NAL_VPS to vps, NAL_SPS to sps, NAL_PPS to pps)) {
            w.u8(0x80 or type) // array_completeness = 1 (hvc1: parameter sets only here)
            w.u16(1)
            w.u16(nal.size)
            w.bytes(nal)
        }
        return w.toByteArray()
    }

    /** Removes emulation prevention bytes (00 00 03 -> 00 00) after [skip] header bytes. */
    fun unescape(nal: ByteArray, skip: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (i in skip until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            out.write(b)
        }
        return out.toByteArray()
    }

    const val DEFAULT_CODEC_STRING = "hvc1.1.6.L93.B0"

    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun int(n: Int): Int = long(n).toInt()

        fun long(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = data[pos ushr 3].toInt()
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1).toLong()
                pos++
            }
            return v
        }

        fun skip(n: Int) {
            pos += n
            if ((pos - 1) ushr 3 >= data.size) throw IndexOutOfBoundsException()
        }

        /** Exp-Golomb unsigned value. */
        fun ue(): Int {
            var zeros = 0
            while (long(1) == 0L) {
                zeros++
                if (zeros > 31) throw IndexOutOfBoundsException()
            }
            return ((1L shl zeros) - 1 + long(zeros)).toInt()
        }
    }
}
