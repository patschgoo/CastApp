package io.github.patschgoo.castapp.capture

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import io.github.patschgoo.castapp.stream.AvcUtils
import java.io.ByteArrayOutputStream
import java.util.PriorityQueue
import java.util.TreeMap

/**
 * Copying a file's H.264 picture into the converted stream as it is, instead of decoding and encoding
 * it again ("remuxing"): German TV recordings (MPEG-TS) and MKV files whose picture a Chromecast plays
 * but whose container or sound it doesn't. Only the sound is converted then (to AAC), if it isn't AAC.
 */
object Remux {
    /** The H.264 picture as its SPS describes it. */
    data class AvcFacts(
        val profileIdc: Int,
        val levelIdc: Int,
        /** constraint_set3_flag: with level_idc 11 it means level 1b. */
        val constraintSet3: Boolean,
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val chromaFormat: Int,
        /** frame_mbs_only_flag: false for interlaced (1080i) pictures. */
        val progressive: Boolean,
    )

    /** What becomes of the picture track. */
    sealed class VideoRoute {
        /** Copied as it is. */
        data object Copy : VideoRoute()

        /** Decoded and encoded again; [why] for the log and the tests. */
        data class Convert(val why: String) : VideoRoute()
    }

    /**
     * What decides about copying a picture track: its type, its SPS (H.264 only), its rotation and
     * whether the reader knows only the decoding order of its frames (AVI).
     */
    data class VideoFacts(val mime: String?, val sps: AvcFacts?, val rotation: Int = 0, val decodeOrderTimes: Boolean = false)

    /** [videoRoute] for a track's [facts]. */
    fun videoRoute(facts: VideoFacts?, maxHeight: Int, allowed: Boolean = true): VideoRoute =
        if (facts == null) {
            VideoRoute.Convert("no picture track")
        } else {
            videoRoute(facts.mime, facts.sps, facts.rotation, maxHeight, facts.decodeOrderTimes, allowed)
        }

    /** Highest H.264 level copied: 4.1, what every Chromecast decodes (1080p30; 1080p50/60 is 4.2). */
    const val MAX_LEVEL = 41

    /** Profiles every Cast device and TV decodes, all 8-bit 4:2:0: Baseline, Main and High. */
    private val PROFILES = setOf(66, 77, 100)

    /**
     * Whether the picture of a track can be copied. [mime]/[sps]: the track; [rotation]: its rotation
     * (copies keep none); [maxHeight]: the quality asked for (1080, 720, 480), which a copy can't
     * scale down to; [decodeOrderTimes]: the reader only knows the decoding order of the frames
     * (AVI), not when each is shown; [allowed]: copying wasn't refused by this TV before.
     */
    fun videoRoute(
        mime: String?,
        sps: AvcFacts?,
        rotation: Int = 0,
        maxHeight: Int = 1080,
        decodeOrderTimes: Boolean = false,
        allowed: Boolean = true,
    ): VideoRoute = when {
        !allowed -> VideoRoute.Convert("copying didn't play on this TV")
        mime?.lowercase() != "video/avc" -> VideoRoute.Convert("not H.264")
        decodeOrderTimes -> VideoRoute.Convert("no presentation times (AVI)")
        sps == null -> VideoRoute.Convert("no SPS")
        sps.profileIdc !in PROFILES -> VideoRoute.Convert("profile ${sps.profileIdc}")
        sps.bitDepth != 8 || sps.chromaFormat != 1 -> VideoRoute.Convert("${sps.bitDepth}-bit / chroma ${sps.chromaFormat}")
        level(sps) > MAX_LEVEL -> VideoRoute.Convert("level ${sps.levelIdc}")
        !sps.progressive -> VideoRoute.Convert("interlaced")
        rotation % 360 != 0 -> VideoRoute.Convert("rotated")
        !fits(sps.width, sps.height, maxHeight) -> VideoRoute.Convert("${sps.width}x${sps.height} is more than ${maxHeight}p")
        else -> VideoRoute.Copy
    }

    /** level_idc, with level 1b (11 + constraint_set3 in Baseline/Main) counted as 1b, not 1.1. */
    private fun level(sps: AvcFacts): Int =
        if (sps.levelIdc == 11 && sps.constraintSet3 && sps.profileIdc != 100) 10 else sps.levelIdc

    /** The same limit the converter scales to: short side at most [maxHeight] (≤ 1080), long side 16:9 of that. */
    fun fits(width: Int, height: Int, maxHeight: Int): Boolean {
        val maxShort = minOf(1080, maxHeight)
        val maxLong = maxShort * 16 / 9
        return width > 0 && height > 0 && minOf(width, height) <= maxShort && maxOf(width, height) <= maxLong
    }

    /** The facts of an SPS NAL unit (with or without start code), null if it can't be read. */
    @OptIn(UnstableApi::class)
    fun parseSps(sps: ByteArray): AvcFacts? {
        val nal = AvcUtils.splitAnnexB(sps).firstOrNull { AvcUtils.nalType(it) == AvcUtils.NAL_SPS } ?: return null
        return try {
            val d = NalUnitUtil.parseSpsNalUnit(nal, 0, nal.size)
            val chroma = if (d.profileIdc in HIGH_PROFILES) chromaFormatOf(nal) else 1
            AvcFacts(
                profileIdc = d.profileIdc,
                levelIdc = d.levelIdc,
                constraintSet3 = d.constraintsFlagsAndReservedZero2Bits and 0x10 != 0,
                width = d.width,
                height = d.height,
                bitDepth = 8 + maxOf(d.bitDepthLumaMinus8, d.bitDepthChromaMinus8),
                chromaFormat = chroma,
                progressive = d.frameMbsOnlyFlag,
            )
        } catch (_: Exception) {
            null
        }
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** chroma_format_idc of a high-profile SPS (Media3 keeps it to itself): the first ue(v) after seq_parameter_set_id. */
    private fun chromaFormatOf(nal: ByteArray): Int {
        val rbsp = unescape(nal)
        val bits = BitReader(rbsp, 4 * 8) // after NAL header, profile, constraints, level
        bits.ue() // seq_parameter_set_id
        return bits.ue()
    }

    private fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray, private var pos: Int) {
        fun bit(): Int {
            if (pos >= data.size * 8) return 0
            val b = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0 && zeros < 31) zeros++
            var v = 0
            repeat(zeros) { v = (v shl 1) or bit() }
            return (1 shl zeros) - 1 + v
        }
    }

    // ---- sound ------------------------------------------------------------------------------------------

    /** An AAC AudioSpecificConfig: object type (2 = AAC-LC), sample rate, channel configuration. */
    data class AacConfig(val objectType: Int, val sampleRate: Int, val channelConfig: Int)

    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun parseAsc(asc: ByteArray?): AacConfig? {
        if (asc == null || asc.size < 2) return null
        var bitPos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = bitPos ushr 3
                val b = if (byte < asc.size) (asc[byte].toInt() ushr (7 - (bitPos and 7))) and 1 else 0
                v = (v shl 1) or b
                bitPos++
            }
            return v
        }
        var type = bits(5)
        if (type == 31) type = 32 + bits(6)
        val index = bits(4)
        val rate = if (index == 15) bits(24) else AAC_RATES.getOrNull(index) ?: return null
        val channels = bits(4)
        return AacConfig(type, rate, channels)
    }

    /**
     * Whether an AAC sound track can be copied as it is: plain AAC-LC (HE-AAC's frames stand for twice the
     * samples the stream's rate says), at most stereo, at most 48 kHz, the rate the extractor reports.
     * Anything else is decoded and encoded to stereo AAC like any other sound.
     */
    fun canCopyAudio(mime: String?, asc: ByteArray?, channels: Int, sampleRate: Int): Boolean {
        if (mime?.lowercase() != "audio/mp4a-latm") return false
        val config = parseAsc(asc) ?: return false
        return config.objectType == 2 && channels in 1..2 && config.channelConfig in 1..2 &&
            config.sampleRate == sampleRate && sampleRate in 8000..48000
    }
}

/**
 * Turns the file's H.264 access units (Annex-B with start codes, as MediaExtractor hands them out, or
 * length-prefixed) into MP4 samples. Access unit delimiters and filler go; parameter sets that are the
 * ones in the stream's configuration go too (they're in the init segment), others stay in the sample
 * (a second PPS, a changed SPS), which players take in-band.
 */
class AvcSamples(private val sps: ByteArray, private val pps: ByteArray) {
    class Converted(val data: ByteArray, val isIdr: Boolean)

    fun convert(accessUnit: ByteArray): Converted {
        val nals = nalUnits(accessUnit)
        val out = ByteArrayOutputStream(accessUnit.size + 16)
        var idr = false
        for (nal in nals) {
            val type = AvcUtils.nalType(nal)
            when (type) {
                AvcUtils.NAL_AUD, NAL_FILLER -> continue
                AvcUtils.NAL_SPS -> if (nal.contentEquals(sps)) continue
                AvcUtils.NAL_PPS -> if (nal.contentEquals(pps)) continue
                AvcUtils.NAL_SLICE_IDR -> idr = true
            }
            val n = nal.size
            out.write(n ushr 24 and 0xFF)
            out.write(n ushr 16 and 0xFF)
            out.write(n ushr 8 and 0xFF)
            out.write(n and 0xFF)
            out.write(nal)
        }
        return Converted(out.toByteArray(), idr)
    }

    companion object {
        private const val NAL_FILLER = 12

        /** The NAL units of an access unit: split at start codes, or read by 4-byte lengths if it has none. */
        fun nalUnits(data: ByteArray): List<ByteArray> {
            if (startsWithStartCode(data)) return AvcUtils.splitAnnexB(data)
            val out = ArrayList<ByteArray>()
            var pos = 0
            while (pos + 4 <= data.size) {
                val n = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                if (n <= 0 || pos + 4 + n > data.size) return AvcUtils.splitAnnexB(data)
                out += data.copyOfRange(pos + 4, pos + 4 + n)
                pos += 4 + n
            }
            return out
        }

        private fun startsWithStartCode(d: ByteArray): Boolean =
            d.size >= 3 && d[0].toInt() == 0 && d[1].toInt() == 0 && (d[2].toInt() == 1 || (d.size >= 4 && d[2].toInt() == 0 && d[3].toInt() == 1))

        /** The first SPS and PPS among [nals] (parameter sets the file only has in-band). */
        fun parameterSets(nals: List<ByteArray>): Pair<ByteArray?, ByteArray?> =
            nals.firstOrNull { AvcUtils.nalType(it) == AvcUtils.NAL_SPS } to nals.firstOrNull { AvcUtils.nalType(it) == AvcUtils.NAL_PPS }
    }
}

/**
 * The picture copy of [Transcoder], without Android in the way: takes the file's H.264 samples in
 * decoding order (with their presentation times, as MediaExtractor hands them out) and gives out MP4
 * samples with decoding times and composition offsets ([onSample]), from the first key frame on.
 *
 * [sps]/[pps]: the track's configuration (csd-0 / csd-1, with or without start codes); null if the
 * file has them only in-band, where they're taken from. [onStart] gets the picture's configuration
 * before the first sample. The copy doesn't start ([refused] says why, and the picture is converted
 * instead) if no key frame with parameter sets comes within [lookahead] samples, or the stream's own
 * SPS says it isn't copied after all ([Remux.videoRoute] at [maxHeight]).
 */
class PictureCopier(
    sps: ByteArray?,
    pps: ByteArray?,
    private val maxHeight: Int = 1080,
    private val lookahead: Int = 300,
    private val onStart: (facts: Remux.AvcFacts, sps: ByteArray, pps: ByteArray) -> Unit,
    private val onSample: (data: ByteArray, dtsUs: Long, key: Boolean, offsetUs: Long) -> Unit,
) {
    private class Copied(val data: ByteArray, val key: Boolean)

    private var sps: ByteArray? = sps?.let { AvcSamples.parameterSets(AvcSamples.nalUnits(it)).first }
    private var pps: ByteArray? = pps?.let { AvcSamples.parameterSets(AvcSamples.nalUnits(it)).second }
    private var samples: AvcSamples? = null
    private var firstKeyUs = Long.MIN_VALUE
    private var looked = 0
    private val times = DecodeTimes<Copied>()

    /** The first key frame went out: from here on the copy can't be swapped for a conversion. */
    val started: Boolean get() = samples != null

    /** Why the copy can't start; null while it can. */
    var refused: String? = null
        private set

    /** The next sample in decoding order; false: the copy can't start (only before [started]). */
    fun add(accessUnit: ByteArray, ptsUs: Long, sync: Boolean): Boolean {
        if (refused != null) return false
        var copy = samples
        if (copy == null) {
            // Not started: wait for a key frame, and the parameter sets if the file has them only in-band.
            val nals = AvcSamples.nalUnits(accessUnit)
            val (inSps, inPps) = AvcSamples.parameterSets(nals)
            if (sps == null) sps = inSps
            if (pps == null) pps = inPps
            val s = sps
            val p = pps
            if (!(sync || AvcUtils.containsIdr(nals)) || s == null || p == null) {
                if (++looked > lookahead) refused = "no key frame with SPS and PPS in $looked frames"
                return refused == null
            }
            val facts = Remux.parseSps(s)
            val route = Remux.videoRoute("video/avc", facts, maxHeight = maxHeight)
            if (facts == null || route != Remux.VideoRoute.Copy) {
                refused = "the stream's SPS: $route"
                return false
            }
            copy = AvcSamples(s, p)
            samples = copy
            firstKeyUs = ptsUs
            onStart(facts, s, p)
        } else if (ptsUs < firstKeyUs) {
            // Pictures of an open GOP shown before the first key frame: they'd need the frames before it.
            return true
        }
        val unit = copy.convert(accessUnit)
        if (unit.data.isEmpty()) return true
        for (t in times.add(Copied(unit.data, sync || unit.isIdr), ptsUs)) onSample(t.item.data, t.dtsUs, t.item.key, t.offsetUs)
        return true
    }

    /** The end of the file: the frames still held back to sort out their decoding times. */
    fun finish() {
        if (started) for (t in times.flush()) onSample(t.item.data, t.dtsUs, t.item.key, t.offsetUs)
    }
}

/**
 * Decoding times for pictures that come in decoding order with only their presentation times
 * (MediaExtractor hands out no DTS). With B-frames a frame is shown after frames decoded later; the
 * decoding times are then the presentation times in sorted order, moved earlier by the reordering
 * delay so that no frame is shown before it's decoded. [window] frames are held back to sort them,
 * more than any H.264 stream reorders.
 */
class DecodeTimes<T>(private val window: Int = 16) {
    class Timed<T>(val item: T, val dtsUs: Long, val ptsUs: Long) {
        /** How much later than decoded the frame is shown; never negative. */
        val offsetUs: Long get() = (ptsUs - dtsUs).coerceAtLeast(0)
    }

    private val waiting = ArrayDeque<Pair<T, Long>>()
    private val sorted = PriorityQueue<Long>()
    private var delayUs = -1L
    private var lastDts = Long.MIN_VALUE

    /** Adds the next frame in decoding order; returns the frames whose decoding time is now known. */
    fun add(item: T, ptsUs: Long): List<Timed<T>> {
        waiting.addLast(item to ptsUs)
        sorted.add(ptsUs)
        return if (waiting.size > window) listOf(next()) else emptyList()
    }

    /** The end: every frame still held back. */
    fun flush(): List<Timed<T>> {
        val out = ArrayList<Timed<T>>(waiting.size)
        while (waiting.isNotEmpty()) out += next()
        return out
    }

    private fun next(): Timed<T> {
        if (delayUs < 0) {
            // How far the frames seen so far are shown after their place in decoding order.
            val pts = waiting.map { it.second }
            val inOrder = pts.sorted()
            delayUs = pts.indices.maxOf { inOrder[it] - pts[it] }.coerceAtLeast(0)
        }
        val (item, pts) = waiting.removeFirst()
        var dts = sorted.poll()!! - delayUs
        // Strictly increasing; a frame reordered further than the first ones were is shown on time anyway
        // where it can be (its offset is clamped at 0).
        if (dts <= lastDts) dts = lastDts + 1
        lastDts = dts
        return Timed(item, dts, pts)
    }
}

/**
 * Presentation times for decoded pictures of a file whose samples only carry their place in decoding
 * order (AVI with B-frames): the decoder hands the pictures back in display order, each with the time
 * of the sample it came from; display order means each gets the earliest of the samples' times still
 * unused. A sample the decoder never answers (an empty "drop" frame) gives up its time once [window]
 * samples after it have come back, so later pictures don't fall behind.
 */
class DisplayOrderTimes(private val window: Int = 8) {
    private class Queued(val timeUs: Long) {
        var answered = false
        /** Pictures of later samples that came back while this one's didn't. */
        var passed = 0
    }

    private val unused = TreeMap<Long, Int>()
    private val waiting = ArrayDeque<Queued>()

    /** A sample went into the decoder (in file order). */
    fun queued(timeUs: Long) {
        unused.merge(timeUs, 1, Int::plus)
        waiting.addLast(Queued(timeUs))
    }

    /** The decoder gave back a picture with the time of the sample [decoderTimeUs]: the time to show it at. */
    fun shown(decoderTimeUs: Long): Long {
        for (q in waiting) {
            if (q.answered) continue
            if (q.timeUs == decoderTimeUs) {
                q.answered = true
                break
            }
            q.passed++
        }
        while (waiting.isNotEmpty()) {
            val oldest = waiting.first()
            when {
                oldest.answered -> waiting.removeFirst()
                // Never coming back: its time goes, or the earliest one left if a picture already has it.
                oldest.passed >= window -> {
                    waiting.removeFirst()
                    if (!take(oldest.timeUs)) unused.firstEntry()?.let { take(it.key) }
                }
                else -> break
            }
        }
        val first = unused.firstEntry() ?: return decoderTimeUs
        take(first.key)
        return first.key
    }

    private fun take(time: Long): Boolean {
        val n = unused[time] ?: return false
        if (n <= 1) unused.remove(time) else unused[time] = n - 1
        return true
    }

    fun clear() {
        unused.clear()
        waiting.clear()
    }
}
