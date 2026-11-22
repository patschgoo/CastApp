package io.github.patschgoo.castapp.subtitles

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/**
 * The accurate engine (whisper.cpp): understands about a hundred languages, detects which one
 * is spoken and can translate it into English. It works on pieces of a minute of sound.
 */
class WhisperEngine(
    modelFile: File,
    private val language: String,
    private val translate: Boolean,
    private val threads: Int = defaultThreads(),
) : SpeechEngine {
    private val handle: Long

    init {
        if (!modelFile.isFile) throw IOException(Txt.s(R.string.err_whisper_missing))
        WhisperNative.load()
        handle = WhisperNative.open(modelFile.absolutePath)
        if (handle == 0L) throw IOException(Txt.s(R.string.err_whisper_unreadable))
    }

    private val chunker = WhisperChunker { samples, count ->
        val raw = WhisperNative.transcribe(handle, samples, count, language.ifEmpty { "auto" }, translate, threads, null)
            ?: if (cancelled) throw CancellationException("Stopped") else throw IOException(Txt.s(R.string.err_whisper_nosound))
        WhisperSegments.parse(raw)
    }

    override fun accept(pcm: ShortArray, count: Int) = chunker.accept(pcm, count)

    override fun finish() = chunker.finish()

    override fun cues(): List<Cue> = chunker.cues()

    override val processedSeconds: Double get() = chunker.processedSeconds

    private val lock = Any()
    private var closed = false
    @Volatile private var cancelled = false

    // cancel() may come from another thread at any time, also after close().
    override fun cancel() = synchronized(lock) {
        cancelled = true
        if (!closed) WhisperNative.abort(handle)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            WhisperNative.close(handle)
        }
    }

    companion object {
        /** Big cores do the work; more threads than four rarely help on phones. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/** One piece of text Whisper heard, with times in seconds relative to the audio it was given. */
data class WhisperSegment(val start: Double, val end: Double, val noSpeech: Float, val text: String)

object WhisperSegments {
    /** Reads the native result: one line per segment, "startMs\tendMs\tnoSpeechProbability\ttext". */
    fun parse(raw: String): List<WhisperSegment> = raw.lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 4)
        if (parts.size < 4) return@mapNotNull null
        val start = parts[0].toLongOrNull() ?: return@mapNotNull null
        val end = parts[1].toLongOrNull() ?: return@mapNotNull null
        WhisperSegment(start / 1000.0, end / 1000.0, parts[2].toFloatOrNull() ?: 0f, parts[3])
    }.toList()

    private val NOISE = Regex("""^\s*[\[(♪*].*[\])♪*]\s*$""")
    private const val NO_SPEECH = 0.8f

    /** Turns segments into readable subtitles, moved [offsetSeconds] later. Long segments are split. */
    fun toCues(segments: List<WhisperSegment>, offsetSeconds: Double): List<Cue> {
        val cues = ArrayList<Cue>()
        for (s in segments) {
            val text = s.text.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty() || s.noSpeech >= NO_SPEECH || NOISE.matches(text)) continue
            if (text.all { !it.isLetterOrDigit() }) continue
            val start = s.start + offsetSeconds
            val end = maxOf(s.end + offsetSeconds, start + 0.5)
            cues += split(text, start, end)
        }
        // Readable for long enough, but never over the next one.
        return cues.mapIndexed { i, c ->
            val next = cues.getOrNull(i + 1)?.start ?: Double.MAX_VALUE
            c.copy(end = minOf(maxOf(c.end, c.start + 1.2), next).coerceAtLeast(c.start))
        }
    }

    /** At most two lines per subtitle; the time is shared out by length. */
    private fun split(text: String, start: Double, end: Double): List<Cue> {
        val max = CueBuilder.LINE * 2
        if (text.length <= max) return listOf(Cue(start, end, CueBuilder.wrap(text)))
        // Pieces of about the same length, none longer than two lines.
        val target = text.length / ((text.length + max - 1) / max)
        val pieces = ArrayList<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            if (current.isNotEmpty() && (current.length >= target || current.length + 1 + word.length > max)) {
                pieces += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        val total = pieces.sumOf { it.length }.toDouble()
        var t = start
        return pieces.map { p ->
            val d = (end - start) * p.length / total
            Cue(t, t + d, CueBuilder.wrap(p)).also { t += d }
        }
    }
}

/**
 * Collects sound into pieces of [chunkSeconds] and hands each to [transcribe]. The last
 * sentence of a piece is often cut off, so it is not kept: its sound is carried over and
 * starts the next piece instead. Silent pieces are skipped.
 */
class WhisperChunker(
    private val chunkSeconds: Int = 60,
    private val maxCarrySeconds: Int = 20,
    private val transcribe: (FloatArray, Int) -> List<WhisperSegment>,
) {
    private val rate = SpeechEngine.SAMPLE_RATE
    private val chunk = chunkSeconds * rate
    private val maxCarry = maxCarrySeconds * rate
    private val buffer = FloatArray(chunk + maxCarry)
    private var length = 0
    /** Where buffer[0] is in the whole sound, in samples. */
    private var bufferStart = 0L
    private val done = ArrayList<Cue>()

    init {
        require(maxCarrySeconds < chunkSeconds)
    }

    val processedSeconds: Double get() = bufferStart.toDouble() / rate

    fun cues(): List<Cue> = done.toList()

    fun accept(pcm: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val n = minOf(count - i, chunk - length)
            for (k in 0 until n) buffer[length + k] = pcm[i + k] / 32768f
            length += n
            i += n
            if (length >= chunk) process(last = false)
        }
    }

    fun finish() = process(last = true)

    private fun process(last: Boolean) {
        if (length < rate / 2) {
            // Less than half a second left: nothing worth listening to.
            bufferStart += length
            length = 0
            return
        }
        val segments = if (isSilent()) emptyList() else transcribe(buffer, length)
        var used = segments
        var keepFrom = length
        if (!last && segments.size >= 2) {
            val from = (segments.last().start * rate).toInt()
            if (from in 1 until length && length - from <= maxCarry) {
                used = segments.dropLast(1)
                keepFrom = from
            }
        }
        val offset = bufferStart.toDouble() / rate
        val limit = offset + keepFrom.toDouble() / rate
        val cues = WhisperSegments.toCues(used.map { it.copy(end = minOf(it.end, keepFrom.toDouble() / rate)) }, offset)
        val previousEnd = done.lastOrNull()?.end ?: 0.0
        done += cues.filter { it.start >= previousEnd - 0.01 && it.start < limit }
        val carry = length - keepFrom
        System.arraycopy(buffer, keepFrom, buffer, 0, carry)
        bufferStart += keepFrom
        length = carry
    }

    private fun isSilent(): Boolean {
        var sum = 0.0
        for (k in 0 until length) sum += buffer[k] * buffer[k]
        return sqrt(sum / length) < 0.0015
    }
}
