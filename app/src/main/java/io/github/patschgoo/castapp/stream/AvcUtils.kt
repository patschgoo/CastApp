package io.github.patschgoo.castapp.stream

import java.io.ByteArrayOutputStream

/**
 * Helpers for H.264 bitstreams. Android's MediaCodec emits Annex-B (start-code
 * delimited) NAL units, while MP4 wants each NAL unit prefixed with its length.
 */
object AvcUtils {
    const val NAL_SLICE_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /** Splits an Annex-B buffer into NAL units (without start codes). */
    fun splitAnnexB(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val starts = ArrayList<Int>() // index of first byte after each start code
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts.add(i + 3)
                    i += 3
                    continue
                } else if (data[i + 2].toInt() == 0 && i + 3 < end && data[i + 3].toInt() == 1) {
                    starts.add(i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) {
            // Not Annex-B; treat the whole buffer as a single NAL unit.
            return if (length > 0) listOf(data.copyOfRange(offset, end)) else emptyList()
        }
        val nals = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val s = starts[k]
            var e = if (k + 1 < starts.size) startCodeBegin(data, starts[k + 1]) else end
            // Trailing zero bytes belong to the next start code / padding.
            while (e > s && data[e - 1].toInt() == 0) e--
            if (e > s) nals.add(data.copyOfRange(s, e))
        }
        return nals
    }

    private fun startCodeBegin(data: ByteArray, afterStartCode: Int): Int {
        // afterStartCode points right after "00 00 01" or "00 00 00 01".
        val threeByte = afterStartCode - 3
        return if (threeByte - 1 >= 0 && data[threeByte - 1].toInt() == 0) threeByte - 1 else threeByte
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /**
     * Converts the NAL units of one access unit into MP4 sample data (4-byte
     * big-endian length prefixes). Parameter sets and access unit delimiters are
     * dropped because they are carried in the avcC box instead.
     */
    fun toAvccSample(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        for (nal in nals) {
            when (nalType(nal)) {
                NAL_SPS, NAL_PPS, NAL_AUD -> continue
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

    fun containsIdr(nals: List<ByteArray>): Boolean = nals.any { nalType(it) == NAL_SLICE_IDR }

    /** RFC 6381 codec string such as "avc1.42C01F" for an SPS NAL unit. */
    fun codecString(sps: ByteArray): String {
        if (sps.size < 4) return "avc1.42E01F"
        return "avc1.%02X%02X%02X".format(sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)
    }
}
