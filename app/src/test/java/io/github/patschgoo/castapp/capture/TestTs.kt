package io.github.patschgoo.castapp.capture

import io.github.patschgoo.castapp.stream.AvcUtils
import java.io.ByteArrayOutputStream

/**
 * A tiny MPEG-TS reader for the tests, handing out what Android's MediaExtractor gives the converter
 * on a phone: H.264 access units (Annex-B, in decoding order) with their presentation times, raw AAC
 * frames with the AudioSpecificConfig, times from the stream's first PTS. (Media3's TS reader needs
 * android.util.SparseArray, which does nothing on the JVM.)
 */
internal class TestTs(private val data: ByteArray) {
    class Sample(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    val video = ArrayList<Sample>()
    val audio = ArrayList<Sample>()
    var videoStreamType = -1
        private set
    var audioStreamType = -1
        private set
    var asc: ByteArray? = null
        private set
    var sampleRate = 0
        private set
    var channels = 0
        private set

    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    private val pes = HashMap<Int, ByteArrayOutputStream>()
    private var firstPts = -1L

    init {
        var i = 0
        while (i + 188 <= data.size) {
            if (data[i] == 0x47.toByte()) packet(i)
            i += 188
        }
        pes.keys.toList().forEach { flush(it) }
    }

    private fun u8(at: Int) = data[at].toInt() and 0xFF

    private fun packet(at: Int) {
        val pid = ((u8(at + 1) and 0x1F) shl 8) or u8(at + 2)
        val start = u8(at + 1) and 0x40 != 0
        val adaptation = (u8(at + 3) ushr 4) and 3
        var p = at + 4
        if (adaptation == 2 || adaptation == 3) p += 1 + u8(p)
        if (adaptation == 1 || adaptation == 3) {
            val end = at + 188
            when {
                pid == 0 && start -> pat(p + 1 + u8(p))
                pid == pmtPid && start -> pmt(p + 1 + u8(p))
                pid == videoPid || pid == audioPid -> {
                    if (start) flush(pid)
                    pes.getOrPut(pid) { ByteArrayOutputStream() }.write(data, p, end - p)
                }
            }
        }
    }

    private fun pat(p: Int) {
        val length = ((u8(p + 1) and 0x0F) shl 8) or u8(p + 2)
        var q = p + 8
        while (q + 4 <= p + 3 + length - 4) {
            val program = (u8(q) shl 8) or u8(q + 1)
            if (program != 0) pmtPid = ((u8(q + 2) and 0x1F) shl 8) or u8(q + 3)
            q += 4
        }
    }

    private fun pmt(p: Int) {
        val length = ((u8(p + 1) and 0x0F) shl 8) or u8(p + 2)
        val infoLength = ((u8(p + 10) and 0x0F) shl 8) or u8(p + 11)
        var q = p + 12 + infoLength
        while (q + 5 <= p + 3 + length - 4) {
            val type = u8(q)
            val pid = ((u8(q + 1) and 0x1F) shl 8) or u8(q + 2)
            val esInfo = ((u8(q + 3) and 0x0F) shl 8) or u8(q + 4)
            when (type) {
                0x1B, 0x02 -> if (videoPid < 0) { videoPid = pid; videoStreamType = type }
                0x0F, 0x03, 0x04 -> if (audioPid < 0) { audioPid = pid; audioStreamType = type }
            }
            q += 5 + esInfo
        }
    }

    private fun flush(pid: Int) {
        val bytes = pes.remove(pid)?.toByteArray() ?: return
        if (bytes.size < 9 || bytes[0].toInt() != 0 || bytes[1].toInt() != 0 || bytes[2].toInt() != 1) return
        val flags = bytes[7].toInt() and 0xFF
        val headerLength = bytes[8].toInt() and 0xFF
        val pts90k = if (flags and 0x80 != 0) {
            fun b(i: Int) = (bytes[9 + i].toLong() and 0xFF)
            ((b(0) and 0x0E) shl 29) or (b(1) shl 22) or ((b(2) and 0xFE) shl 14) or (b(3) shl 7) or (b(4) ushr 1)
        } else {
            return
        }
        if (firstPts < 0) firstPts = pts90k
        val ptsUs = (pts90k - firstPts) * 100 / 9
        val payload = bytes.copyOfRange(9 + headerLength, bytes.size)
        if (pid == videoPid) {
            video += Sample(payload, ptsUs, AvcUtils.containsIdr(AvcUtils.splitAnnexB(payload)))
        } else if (audioStreamType == 0x0F) {
            adts(payload, ptsUs)
        } else {
            audio += Sample(payload, ptsUs, true)
        }
    }

    private fun adts(payload: ByteArray, ptsUs: Long) {
        var p = 0
        var t = ptsUs
        while (p + 7 <= payload.size) {
            fun b(i: Int) = payload[p + i].toInt() and 0xFF
            if (b(0) != 0xFF || b(1) and 0xF0 != 0xF0) return
            val header = if (b(1) and 1 == 1) 7 else 9
            val length = ((b(3) and 3) shl 11) or (b(4) shl 3) or (b(5) ushr 5)
            if (asc == null) {
                val objectType = (b(2) ushr 6) + 1
                val rateIndex = (b(2) ushr 2) and 0x0F
                val channelConfig = ((b(2) and 1) shl 2) or (b(3) ushr 6)
                asc = byteArrayOf(((objectType shl 3) or (rateIndex ushr 1)).toByte(), (((rateIndex and 1) shl 7) or (channelConfig shl 3)).toByte())
                sampleRate = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)[rateIndex]
                channels = channelConfig
            }
            audio += Sample(payload.copyOfRange(p + header, p + length), t, true)
            t += 1024 * 1_000_000L / sampleRate
            p += length
        }
    }
}
