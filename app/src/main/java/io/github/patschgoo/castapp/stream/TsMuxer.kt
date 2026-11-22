package io.github.patschgoo.castapp.stream

import java.io.ByteArrayOutputStream

/**
 * Live MPEG transport stream (H.264 + AAC) for DLNA TVs, which accept an endless
 * ".ts" stream far more reliably than fragmented MP4.
 */
class TsMuxer(private val video: VideoConfig?, private val audio: AudioConfig?) {
    private val continuity = IntArray(0x2000)
    private var started = false
    private var baseUs = 0L
    private var lastPsiUs = Long.MIN_VALUE

    /** Muxes one sample; returns whole 188-byte packets, or null if the sample is skipped. */
    fun mux(sample: EncodedSample): ByteArray? {
        val isVideo = sample.trackId == FragmentedMp4.VIDEO_TRACK_ID
        if (!started) {
            val canStart = if (video != null) isVideo && sample.isKey else !isVideo
            if (!canStart) return null
            started = true
            baseUs = sample.ptsUs
        }
        val relUs = sample.ptsUs - baseUs
        if (relUs < 0) return null
        val pts = relUs * 9 / 100 + PTS_OFFSET
        val out = ByteArrayOutputStream(sample.data.size + 512)
        // Program tables before every key frame (and at least every half second) so a TV can join any time.
        if ((isVideo && sample.isKey) || sample.ptsUs - lastPsiUs > 500_000) {
            out.write(pat())
            out.write(pmt())
            lastPsiUs = sample.ptsUs
        }
        if (isVideo) {
            val v = video ?: return null
            out.write(packetize(VIDEO_PID, pes(0xE0, pts, annexB(sample.data, sample.isKey, v), bounded = false), pcr = pts - PCR_LEAD))
        } else {
            val a = audio ?: return null
            out.write(packetize(AUDIO_PID, pes(0xC0, pts, Adts.frame(a, sample.data), bounded = true), pcr = if (video == null) pts - PCR_LEAD else null))
        }
        return out.toByteArray()
    }

    private fun annexB(avcc: ByteArray, key: Boolean, v: VideoConfig): ByteArray {
        val out = ByteArrayOutputStream(avcc.size + 64)
        out.write(START_CODE); out.write(byteArrayOf(0x09, 0xF0.toByte())) // access unit delimiter
        if (key) {
            out.write(START_CODE); out.write(v.sps)
            out.write(START_CODE); out.write(v.pps)
        }
        var p = 0
        while (p + 4 <= avcc.size) {
            val n = ((avcc[p].toInt() and 0xFF) shl 24) or ((avcc[p + 1].toInt() and 0xFF) shl 16) or
                ((avcc[p + 2].toInt() and 0xFF) shl 8) or (avcc[p + 3].toInt() and 0xFF)
            p += 4
            if (n <= 0 || p + n > avcc.size) break
            out.write(START_CODE)
            out.write(avcc, p, n)
            p += n
        }
        return out.toByteArray()
    }

    private fun pes(streamId: Int, pts: Long, payload: ByteArray, bounded: Boolean): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0); out.write(0); out.write(1); out.write(streamId)
        val length = 3 + 5 + payload.size
        val field = if (bounded && length <= 0xFFFF) length else 0
        out.write(field ushr 8); out.write(field and 0xFF)
        out.write(0x80) // marker bits
        out.write(0x80) // PTS only
        out.write(5)
        out.write((0x20 or ((pts ushr 29).toInt() and 0x0E) or 1))
        out.write(((pts ushr 22) and 0xFF).toInt())
        out.write((((pts ushr 14) and 0xFE) or 1).toInt())
        out.write(((pts ushr 7) and 0xFF).toInt())
        out.write((((pts shl 1) and 0xFE) or 1).toInt())
        out.write(payload)
        return out.toByteArray()
    }

    private fun packetize(pid: Int, data: ByteArray, pcr: Long?): ByteArray {
        val out = ByteArrayOutputStream((data.size / 184 + 2) * PACKET)
        var offset = 0
        var first = true
        while (offset < data.size) {
            val remaining = data.size - offset
            val withPcr = first && pcr != null
            val adaptation = ByteArrayOutputStream()
            var payloadSize: Int
            if (withPcr) {
                val maxPayload = 184 - 8
                payloadSize = minOf(remaining, maxPayload)
                val stuffing = maxPayload - payloadSize
                adaptation.write(7 + stuffing)
                adaptation.write(0x10) // PCR flag
                adaptation.write(pcrBytes(pcr))
                repeat(stuffing) { adaptation.write(0xFF) }
            } else if (remaining < 184) {
                payloadSize = remaining
                val afTotal = 184 - remaining
                adaptation.write(afTotal - 1)
                if (afTotal > 1) {
                    adaptation.write(0x00)
                    repeat(afTotal - 2) { adaptation.write(0xFF) }
                }
            } else {
                payloadSize = 184
            }
            val af = adaptation.toByteArray()
            out.write(0x47)
            out.write((if (first) 0x40 else 0) or ((pid ushr 8) and 0x1F))
            out.write(pid and 0xFF)
            out.write((if (af.isNotEmpty()) 0x30 else 0x10) or nextCc(pid))
            out.write(af)
            out.write(data, offset, payloadSize)
            offset += payloadSize
            first = false
        }
        return out.toByteArray()
    }

    private fun nextCc(pid: Int): Int {
        val c = continuity[pid]
        continuity[pid] = (c + 1) and 0x0F
        return c
    }

    private fun pcrBytes(pcr90k: Long): ByteArray {
        val base = pcr90k.coerceAtLeast(0)
        return byteArrayOf(
            (base ushr 25).toByte(),
            (base ushr 17).toByte(),
            (base ushr 9).toByte(),
            (base ushr 1).toByte(),
            (((base and 1) shl 7) or 0x7E).toByte(),
            0,
        )
    }

    private fun pat(): ByteArray = psiPacket(
        PAT_PID,
        section(0x00, 1, byteArrayOf(0x00, 0x01, (0xE0 or (PMT_PID ushr 8)).toByte(), PMT_PID.toByte())),
    )

    private fun pmt(): ByteArray {
        val body = ByteArrayOutputStream()
        val pcrPid = if (video != null) VIDEO_PID else AUDIO_PID
        body.write(0xE0 or (pcrPid ushr 8)); body.write(pcrPid and 0xFF)
        body.write(0xF0); body.write(0x00) // no program descriptors
        if (video != null) body.write(byteArrayOf(0x1B, (0xE0 or (VIDEO_PID ushr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0x00))
        if (audio != null) body.write(byteArrayOf(0x0F, (0xE0 or (AUDIO_PID ushr 8)).toByte(), AUDIO_PID.toByte(), 0xF0.toByte(), 0x00))
        return psiPacket(PMT_PID, section(0x02, 1, body.toByteArray()))
    }

    /** A PSI section: header, [body], CRC. [idExt] is the transport stream id / program number. */
    private fun section(tableId: Int, idExt: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val s = ByteArrayOutputStream()
        s.write(tableId)
        s.write(0xB0 or ((length ushr 8) and 0x0F))
        s.write(length and 0xFF)
        s.write(idExt ushr 8); s.write(idExt and 0xFF)
        s.write(0xC1) // version 0, current
        s.write(0); s.write(0)
        s.write(body)
        val crc = crc32(s.toByteArray())
        s.write(crc ushr 24); s.write(crc ushr 16); s.write(crc ushr 8); s.write(crc)
        return s.toByteArray()
    }

    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        val p = ByteArray(PACKET) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte()
        p[2] = pid.toByte()
        p[3] = (0x10 or nextCc(pid)).toByte()
        p[4] = 0 // pointer field
        System.arraycopy(section, 0, p, 5, section.size)
        return p
    }

    companion object {
        const val PACKET = 188
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0100
        const val AUDIO_PID = 0x0101
        private const val PTS_OFFSET = 90_000L // start at 1 s so the PCR (a bit earlier) is never negative
        private const val PCR_LEAD = 45_000L
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** CRC-32/MPEG-2 as used in PSI tables. */
        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}
