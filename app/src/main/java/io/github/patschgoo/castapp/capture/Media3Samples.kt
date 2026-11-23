package io.github.patschgoo.castapp.capture

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.patschgoo.castapp.stream.MediaSource
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads a file with one of Media3's extractors (AVI) the way the converter reads Android's
 * MediaExtractor: the tracks and their formats first, then the samples of the selected tracks in file
 * order, seeking to the key frame before a time by the extractor's seek map. Without Android in the
 * way (unit tests); [Media3SampleReader] puts Android's MediaFormat on top.
 *
 * MP3 / MP2 and AC-3 sound comes out as whole frames timed by their sample count: AVI chunks cut them
 * anywhere, and Android's decoders want whole frames.
 */
@OptIn(UnstableApi::class)
class Media3Samples(
    private val source: MediaSource,
    private val newExtractor: () -> Extractor = { AviExtractor(AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED) },
) : Closeable {
    class Track(val type: Int) {
        @Volatile var format: Format? = null
            internal set
        val mime: String? get() = format?.sampleMimeType
    }

    class Sample(val track: Int, val data: ByteArray, val timeUs: Long, val isKey: Boolean)

    private var extractor: Extractor = newExtractor()
    private val trackList = ArrayList<Track>()
    private val outputs = ArrayList<Output>()
    private var outputsRegistered = 0
    private var tracksEnded = false
    private val selected = HashSet<Int>()
    private val ready = ArrayDeque<Sample>()
    private var stream: InputStream? = null
    private var input: ExtractorInput? = null
    private var nextPosition = 0L
    private var ended = false
    private var needsSeek = false
    private val holder = PositionHolder()
    private val length: Long = source.length

    val tracks: List<Track> get() = trackList

    @Volatile var seekMap: SeekMap? = null
        private set

    /** Length of the file in microseconds as the seek map has it, 0 if unknown. */
    val durationUs: Long get() = seekMap?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    /** The file's first picture sample, read while preparing: an MPEG-4 (XviD) picture's set-up is in it. */
    @Volatile var firstVideoSample: ByteArray? = null
        private set

    private val extractorOutput = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            // After a restart the extractor names the same tracks again, in the same order.
            val index = outputsRegistered++
            if (index < outputs.size) return outputs[index]
            val track = Track(type)
            trackList += track
            return Output(index, track).also { outputs += it }
        }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this@Media3Samples.seekMap = seekMap
        }
    }

    /**
     * Reads the file's header: its tracks, their formats and the seek map (AVI: its index at the end),
     * and the first picture sample. Throws [IOException] if the extractor doesn't take the file.
     */
    fun prepare(maxBytes: Long = PREPARE_LIMIT) {
        val first = openAt(0)
        val ok = try {
            extractor.sniff(first)
        } catch (_: EOFException) {
            false
        } finally {
            first.resetPeekPosition()
        }
        if (!ok) throw IOException("Not a file this reader takes")
        extractor.init(extractorOutput)
        val start = System.nanoTime()
        while (!prepared()) {
            if (!readOnce()) break
            val pos = input?.position ?: 0
            if (pos > maxBytes && tracksEnded) break
            if (System.nanoTime() - start > PREPARE_TIMEOUT_NS && tracksEnded) break
        }
        if (!tracksEnded) throw IOException("The file's tracks couldn't be read")
        ready.clear()
        needsSeek = true
    }

    private fun prepared(): Boolean =
        tracksEnded && seekMap != null && trackList.all { it.format != null } &&
            (firstVideoSample != null || trackList.none { it.type == C.TRACK_TYPE_VIDEO })

    fun select(index: Int) {
        selected += index
        needsSeek = true
    }

    /** Goes to the key frame at or before [timeUs] (the file's start if it can't seek). */
    fun seekTo(timeUs: Long) {
        ready.clear()
        outputs.forEach { it.reset() }
        ended = false
        needsSeek = false
        val map = seekMap
        if (map != null && map.isSeekable) {
            val point = map.getSeekPoints(timeUs.coerceAtLeast(0)).first
            extractor.seek(point.position, timeUs.coerceAtLeast(0))
            reopenAt(point.position)
        } else {
            restart()
        }
    }

    /** The next sample of a selected track, null at the end of the file. */
    fun current(): Sample? {
        if (needsSeek) seekTo(0)
        while (ready.isEmpty() && !ended) {
            if (!readOnce()) {
                ended = true
                outputs.forEach { it.flush() }
            }
        }
        return ready.firstOrNull()
    }

    fun advance(): Boolean {
        if (ready.isNotEmpty()) ready.removeFirst()
        return current() != null
    }

    override fun close() {
        closeStream()
        try { extractor.release() } catch (_: Exception) {}
    }

    // ---- reading -----------------------------------------------------------------------------------

    /** One step of the extractor; false at the end of the file. */
    private fun readOnce(): Boolean {
        val inp = input ?: openAt(nextPosition)
        return try {
            when (extractor.read(inp, holder)) {
                Extractor.RESULT_CONTINUE -> true
                Extractor.RESULT_SEEK -> {
                    reopenAt(holder.position)
                    true
                }
                else -> false
            }
        } catch (_: EOFException) {
            false
        }
    }

    /** Starts the extractor over from the file's beginning (no seek map). */
    private fun restart() {
        try { extractor.release() } catch (_: Exception) {}
        extractor = newExtractor()
        outputsRegistered = 0
        tracksEnded = false
        val first = openAt(0)
        try {
            extractor.sniff(first)
        } finally {
            first.resetPeekPosition()
        }
        extractor.init(extractorOutput)
    }

    private fun openAt(position: Long): ExtractorInput {
        closeStream()
        val s = source.open(position)
        stream = s
        nextPosition = position
        return DefaultExtractorInput(StreamReader(s), position, if (length > 0) length else C.LENGTH_UNSET.toLong()).also { input = it }
    }

    private fun reopenAt(position: Long) {
        closeStream()
        nextPosition = position
    }

    private fun closeStream() {
        try { stream?.close() } catch (_: Exception) {}
        stream = null
        input = null
    }

    private fun emit(sample: Sample) {
        ready.addLast(sample)
    }

    /** Collects one track's samples (if selected, or its first picture while preparing). */
    private inner class Output(private val index: Int, private val track: Track) : TrackOutput {
        private var buffer = ByteArray(64 * 1024)
        private var size = 0
        private val scratch = ByteArray(16 * 1024)
        private var framer: AudioFramer? = null

        private val collecting: Boolean
            get() = index in selected || (track.type == C.TRACK_TYPE_VIDEO && firstVideoSample == null)

        override fun format(format: Format) {
            track.format = format
            framer = AudioFramer.forFormat(format)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = if (collecting) {
                ensure(length)
                input.read(buffer, size, length)
            } else {
                input.read(scratch, 0, minOf(length, scratch.size))
            }
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            if (collecting) size += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (collecting) {
                ensure(length)
                data.readBytes(buffer, size, length)
                size += length
            } else {
                data.skipBytes(length)
            }
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val end = this.size - offset
            val start = end - size
            if (start >= 0 && size > 0 && end <= this.size) {
                val bytes = buffer.copyOfRange(start, end)
                if (track.type == C.TRACK_TYPE_VIDEO && firstVideoSample == null) firstVideoSample = bytes
                if (index in selected) {
                    val f = framer
                    if (f != null) {
                        f.add(bytes, timeUs) { data, t -> emit(Sample(index, data, t, true)) }
                    } else {
                        emit(Sample(index, bytes, timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0))
                    }
                }
            }
            // Keep what belongs to the next sample.
            val rest = (this.size - end).coerceAtLeast(0)
            if (rest > 0 && end >= 0) System.arraycopy(buffer, end, buffer, 0, rest)
            this.size = rest
        }

        fun reset() {
            size = 0
            framer?.reset()
        }

        fun flush() {
            if (index in selected) framer?.flush()
        }

        private fun ensure(extra: Int) {
            if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }
    }

    private class StreamReader(private val stream: InputStream) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val n = stream.read(buffer, offset, length)
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
    }

    companion object {
        /** How far into the file the header, the index and the first picture are looked for. */
        const val PREPARE_LIMIT = 32L * 1024 * 1024
        private const val PREPARE_TIMEOUT_NS = 20_000_000_000L

        /** Opens and prepares [source] with Media3's AVI reader; throws [IOException] if it isn't an AVI it reads. */
        fun avi(source: MediaSource): Media3Samples {
            val samples = Media3Samples(source)
            try {
                samples.prepare()
            } catch (e: Exception) {
                samples.close()
                throw e
            }
            return samples
        }
    }
}

/**
 * Cuts MPEG audio (MP3, MP2) or AC-3 into whole frames, timed by the samples before them from the
 * first chunk's time: AVI chunks hold any number of bytes, often a frame and a half.
 */
@OptIn(UnstableApi::class)
class AudioFramer private constructor(private val kind: Kind, private val sampleRate: Int) {
    private enum class Kind { MPEG, AC3 }

    private var pending = ByteArray(16 * 1024)
    private var size = 0
    private var anchorUs = -1L
    private var samplesOut = 0L
    private val header = MpegAudioUtil.Header()

    /** Adds one chunk of [timeUs]; [out] gets each whole frame with its time. */
    fun add(chunk: ByteArray, timeUs: Long, out: (ByteArray, Long) -> Unit) {
        if (anchorUs < 0) {
            anchorUs = timeUs
            samplesOut = 0
        }
        if (size + chunk.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, size + chunk.size))
        System.arraycopy(chunk, 0, pending, size, chunk.size)
        size += chunk.size
        var pos = 0
        while (true) {
            val sync = findSync(pos)
            if (sync < 0) {
                // The last byte may be the first half of the next sync word.
                pos = maxOf(pos, size - 1)
                break
            }
            if (sync + headerBytes > size) {
                pos = sync
                break
            }
            val frame = frameAt(sync)
            if (frame == null) {
                pos = sync + 1
                continue
            }
            if (sync + frame.size > size) {
                pos = sync
                break
            }
            val rate = (if (frame.rate > 0) frame.rate else sampleRate).coerceAtLeast(1)
            out(pending.copyOfRange(sync, sync + frame.size), anchorUs + samplesOut * 1_000_000L / rate)
            samplesOut += frame.samples
            pos = sync + frame.size
        }
        val rest = size - pos
        if (rest > 0 && pos > 0) System.arraycopy(pending, pos, pending, 0, rest)
        size = rest.coerceAtLeast(0)
    }

    /** The end of the file: a partial frame left over can't be decoded. */
    fun flush() {
        size = 0
    }

    /** After a seek the next chunk's time is the new anchor. */
    fun reset() {
        size = 0
        anchorUs = -1
        samplesOut = 0
    }

    private val headerBytes: Int get() = if (kind == Kind.MPEG) 4 else 6

    private class Frame(val size: Int, val samples: Int, val rate: Int)

    private fun findSync(from: Int): Int {
        var i = from
        while (i + 1 < size) {
            val b0 = pending[i].toInt() and 0xFF
            val b1 = pending[i + 1].toInt() and 0xFF
            val found = when (kind) {
                Kind.MPEG -> b0 == 0xFF && b1 and 0xE0 == 0xE0
                Kind.AC3 -> b0 == 0x0B && b1 == 0x77
            }
            if (found) return i
            i++
        }
        return -1
    }

    /** The frame starting at [at] (its header is there), null if it's no frame start after all. */
    private fun frameAt(at: Int): Frame? = when (kind) {
        Kind.MPEG -> {
            val h = ((pending[at].toInt() and 0xFF) shl 24) or ((pending[at + 1].toInt() and 0xFF) shl 16) or
                ((pending[at + 2].toInt() and 0xFF) shl 8) or (pending[at + 3].toInt() and 0xFF)
            // A sync word inside the data whose rate doesn't match the track's isn't a frame.
            if (header.setForHeaderData(h) && header.frameSize > 0 && (sampleRate <= 0 || header.sampleRate == sampleRate)) {
                Frame(header.frameSize, header.samplesPerFrame, header.sampleRate)
            } else {
                null
            }
        }
        Kind.AC3 -> {
            val frameSize = Ac3Util.parseAc3SyncframeSize(pending.copyOfRange(at, at + 6))
            if (frameSize > 0) Frame(frameSize, AC3_SAMPLES, 0) else null
        }
    }

    companion object {
        private const val AC3_SAMPLES = 1536

        /** A framer for MPEG audio and AC-3 tracks, null for anything else (PCM, AAC: taken as they come). */
        fun forFormat(format: Format): AudioFramer? = when (format.sampleMimeType) {
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_MPEG_L1 -> AudioFramer(Kind.MPEG, format.sampleRate)
            MimeTypes.AUDIO_AC3 -> AudioFramer(Kind.AC3, format.sampleRate)
            else -> null
        }
    }
}

/** The set-up of an MPEG-4 part 2 (XviD, DivX 4/5) picture, which AVI files carry in their first frame. */
object Mpeg4Video {
    /**
     * The headers before the first picture (VOS / VO / VOL, up to the first VOP start code) of [frame],
     * null if it has no VOL header. A decoder needs them before the first frame; key frames later in the
     * file (where a seek starts) don't repeat them.
     */
    fun config(frame: ByteArray): ByteArray? {
        var volSeen = false
        var i = 0
        while (i + 3 < frame.size) {
            if (frame[i].toInt() == 0 && frame[i + 1].toInt() == 0 && frame[i + 2].toInt() == 1) {
                val code = frame[i + 3].toInt() and 0xFF
                if (code in 0x20..0x2F) volSeen = true
                if (code == 0xB6) return if (volSeen && i > 0) frame.copyOfRange(0, i) else null
                i += 3
                continue
            }
            i++
        }
        return null
    }

    /**
     * Leaves out the B-pictures right after the key frame a conversion starts at (FFmpeg and XviD write
     * open GOPs): they're shown before it and need the picture before it, which the decoder never got.
     */
    class LeadingPictures {
        private var started = false
        private var leading = true

        /** True: leave out this sample ([head]: its first bytes, which hold the VOP header). */
        fun skip(head: ByteArray, isKey: Boolean): Boolean {
            if (!started) {
                started = isKey
                return false
            }
            if (!leading) return false
            if (vopType(head) == 2) return true
            leading = false
            return false
        }
    }

    /** The VOP's coding type in [sample]: 0 I, 1 P, 2 B, 3 S; -1 if there is no VOP header. */
    fun vopType(sample: ByteArray): Int {
        var i = 0
        while (i + 4 < sample.size) {
            if (sample[i].toInt() == 0 && sample[i + 1].toInt() == 0 && sample[i + 2].toInt() == 1 && (sample[i + 3].toInt() and 0xFF) == 0xB6) {
                return (sample[i + 4].toInt() and 0xFF) ushr 6
            }
            i++
        }
        return -1
    }
}
