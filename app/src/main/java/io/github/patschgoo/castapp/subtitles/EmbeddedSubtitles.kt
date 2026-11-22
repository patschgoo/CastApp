package io.github.patschgoo.castapp.subtitles

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import io.github.patschgoo.castapp.service.TextTrackInfo
import io.github.patschgoo.castapp.stream.MediaSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** One subtitle sample read out of a file: [durationUs] < 0 means "until the next one". */
data class TimedText(val startUs: Long, val durationUs: Long, val text: String)

/**
 * Subtitle tracks inside MKV and MP4 files (SRT, SSA/ASS, WebVTT, tx3g, TTML).
 * Android's MediaExtractor doesn't hand these out, so Media3's extractors read
 * the file and its subtitle parsers turn each sample into text. Picture-based
 * subtitles (PGS, VobSub, DVB) are listed but can't be read as text.
 */
@OptIn(UnstableApi::class)
object EmbeddedSubtitles {
    /** Lists the file's subtitle tracks (reads only the header of the file). */
    fun list(source: MediaSource): List<TextTrackInfo> {
        val tracks = ArrayList<TextTrackInfo>()
        var done = false
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                if (type == C.TRACK_TYPE_TEXT) object : TrackOutput by DiscardingTrackOutput() {
                    private var listed = false
                    override fun format(format: Format) {
                        if (listed) return
                        listed = true
                        tracks += TextTrackInfo(
                            ordinal = tracks.size,
                            language = format.language,
                            mimeType = originalMime(format),
                            label = format.label,
                            forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        )
                    }
                } else DiscardingTrackOutput()

            override fun endTracks() {
                done = true
            }

            override fun seekMap(seekMap: SeekMap) {}
        }
        run(source, output, stop = { pos -> done || pos > LIST_READ_LIMIT })
        return tracks
    }

    /**
     * Reads every subtitle of text track [ordinal] (its place among the file's
     * subtitle tracks). Reads through the whole file; [onProgress] gets 0..1.
     */
    fun extract(source: MediaSource, ordinal: Int, cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): List<Cue> {
        val samples = ArrayList<TimedText>()
        var textSeen = 0
        val decoder = CueDecoder()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (type != C.TRACK_TYPE_TEXT) return DiscardingTrackOutput()
                val mine = textSeen++ == ordinal
                return if (mine) Collector { timeUs, bytes ->
                    val cues = try { decoder.decode(timeUs, bytes, 0, bytes.size) } catch (_: Exception) { null } ?: return@Collector
                    val text = cues.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    samples += TimedText(cues.startTimeUs, if (cues.durationUs == C.TIME_UNSET) -1 else cues.durationUs, text)
                } else DiscardingTrackOutput()
            }

            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val length = source.length
        run(source, output, stop = { pos ->
            if (length > 0) onProgress((pos.toFloat() / length).coerceIn(0f, 1f))
            cancelled()
        })
        return assemble(samples)
    }

    /**
     * Turns samples into cues: open-ended ones (tx3g, "replace" style) last until
     * the next sample; an empty sample only ends the one before.
     */
    fun assemble(samples: List<TimedText>): List<Cue> {
        val sorted = samples.sortedBy { it.startUs }
        val cues = ArrayList<Cue>()
        for ((i, s) in sorted.withIndex()) {
            if (s.text.isBlank()) continue
            val endUs = when {
                s.durationUs > 0 -> s.startUs + s.durationUs
                i + 1 < sorted.size -> sorted[i + 1].startUs
                else -> s.startUs + LAST_CUE_US
            }
            val text = s.text.replace("\r", "").lines().map { CueParser.clean(it) }.filter { it.isNotEmpty() }.joinToString("\n")
            if (endUs <= s.startUs || text.isEmpty()) continue
            cues += Cue(s.startUs / 1e6, endUs / 1e6, text)
        }
        // Several identical samples in a row (some muxers repeat them): merge.
        val merged = ArrayList<Cue>()
        for (c in cues) {
            val last = merged.lastOrNull()
            if (last != null && last.text == c.text && c.start - last.end < 0.05) merged[merged.size - 1] = last.copy(end = maxOf(last.end, c.end))
            else merged += c
        }
        return merged
    }

    private fun originalMime(format: Format): String =
        if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs ?: "text/unknown" else format.sampleMimeType ?: "text/unknown"

    private fun extractors(): Array<Extractor> = DefaultExtractorsFactory()
        .setTextTrackTranscodingEnabled(true)
        .setSubtitleParserFactory(DefaultSubtitleParserFactory())
        .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        .createExtractors()

    /** Runs an extractor over [source], opening it again wherever the extractor wants to jump. */
    private fun run(source: MediaSource, output: ExtractorOutput, stop: (Long) -> Boolean) {
        val length = source.length
        var position = 0L
        var extractor: Extractor? = null
        val holder = PositionHolder()
        var reopen = 0
        try {
            while (true) {
                val stream = source.open(position)
                val input = DefaultExtractorInput(StreamReader(stream), position, if (length > 0) length else C.LENGTH_UNSET.toLong())
                try {
                    if (extractor == null) {
                        extractor = sniff(input) ?: return
                        extractor.init(output)
                    }
                    var result = Extractor.RESULT_CONTINUE
                    while (result == Extractor.RESULT_CONTINUE) {
                        if (stop(input.position)) return
                        result = extractor.read(input, holder)
                    }
                    if (result != Extractor.RESULT_SEEK || ++reopen > MAX_REOPEN) return
                    position = holder.position
                } finally {
                    try { stream.close() } catch (_: Exception) {}
                }
            }
        } catch (_: EOFException) {
        } finally {
            extractor?.release()
        }
    }

    private fun sniff(input: ExtractorInput): Extractor? {
        for (e in extractors()) {
            val ok = try { e.sniff(input) } catch (_: EOFException) { false } catch (_: IOException) { false } finally { input.resetPeekPosition() }
            if (ok) return e
            e.release()
        }
        return null
    }

    private const val LIST_READ_LIMIT = 64L * 1024 * 1024
    private const val MAX_REOPEN = 100_000
    private const val LAST_CUE_US = 4_000_000L

    /** Collects the bytes of each sample of one track. */
    private class Collector(private val onSample: (Long, ByteArray) -> Unit) : TrackOutput {
        private var buffer = ByteArray(16 * 1024)
        private var size = 0

        override fun format(format: Format) {}

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            ensure(length)
            val n = input.read(buffer, size, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            size += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensure(length)
            data.readBytes(buffer, size, length)
            size += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val end = this.size - offset
            val start = end - size
            if (start >= 0 && size > 0) onSample(timeUs, buffer.copyOfRange(start, end))
            // Keep what belongs to the next sample.
            val rest = this.size - end
            if (rest > 0) System.arraycopy(buffer, end, buffer, 0, rest)
            this.size = rest.coerceAtLeast(0)
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
}
