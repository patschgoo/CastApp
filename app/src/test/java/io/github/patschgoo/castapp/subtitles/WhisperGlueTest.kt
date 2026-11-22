package io.github.patschgoo.castapp.subtitles

import io.github.patschgoo.castapp.settings.SpeechEngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JVM-side parts of the Whisper engine: reading native results, cues, and the piece-by-piece feeding. */
class WhisperGlueTest {
    private val rate = SpeechEngine.SAMPLE_RATE

    @Test
    fun parsesNativeLines() {
        val raw = "0\t2500\t0.010\t And so, my fellow Americans,\n2500\t10500\t0.020\t ask not what your country can do for you.\n\nbroken line\n"
        assertEquals(
            listOf(
                WhisperSegment(0.0, 2.5, 0.01f, " And so, my fellow Americans,"),
                WhisperSegment(2.5, 10.5, 0.02f, " ask not what your country can do for you."),
            ),
            WhisperSegments.parse(raw),
        )
    }

    @Test
    fun cuesDropNoiseAndSilenceAndSplitLongText() {
        val long = (1..30).joinToString(" ") { "word$it" } // ~200 characters
        val cues = WhisperSegments.toCues(
            listOf(
                WhisperSegment(0.0, 1.0, 0.1f, " [MUSIC]"),
                WhisperSegment(1.0, 2.0, 0.95f, " Thank you for watching."),
                WhisperSegment(2.0, 2.3, 0.1f, " Hi!"),
                WhisperSegment(3.0, 13.0, 0.1f, long),
                WhisperSegment(13.0, 14.0, 0.1f, " ..."),
            ),
            offsetSeconds = 100.0,
        )
        assertEquals("Hi!", cues[0].text)
        assertEquals(102.0, cues[0].start, 1e-9)
        // Short ones stay up long enough to read, but not into the next one.
        assertEquals(103.0, cues[0].end, 1e-9)
        val parts = cues.drop(1)
        assertTrue(parts.size >= 3)
        assertEquals(long, parts.joinToString(" ") { it.text.replace('\n', ' ') })
        assertTrue(parts.all { it.text.lines().size <= 2 && it.text.length <= CueBuilder.LINE * 2 + 1 })
        assertEquals(103.0, parts.first().start, 1e-9)
        assertEquals(113.0, parts.last().end, 1e-6)
        for (i in 1 until cues.size) assertTrue(cues[i - 1].end <= cues[i].start + 1e-9)
    }

    private fun tone(seconds: Double): ShortArray = ShortArray((seconds * rate).toInt()) { i -> (8000 * kotlin.math.sin(i * 0.05)).toInt().toShort() }

    @Test
    fun chunksCarryTheCutOffSentenceIntoTheNextPiece() {
        val calls = ArrayList<Pair<Double, Int>>() // (seconds given, call number)
        var call = 0
        val chunker = WhisperChunker(chunkSeconds = 10, maxCarrySeconds = 4) { samples, count ->
            call++
            calls += count.toDouble() / rate to call
            assertTrue(samples.size >= count)
            when (call) {
                // First 10 s: two sentences, the second one cut off at the end.
                1 -> listOf(WhisperSegment(0.5, 4.0, 0.1f, "First sentence."), WhisperSegment(7.0, 10.0, 0.1f, "Second sen"))
                // Starts at 7 s with the carried 3 s: the whole second sentence.
                2 -> listOf(WhisperSegment(0.0, 4.0, 0.1f, "Second sentence complete."), WhisperSegment(5.0, 9.0, 0.1f, "Third."))
                else -> listOf(WhisperSegment(0.0, 1.0, 0.1f, "Last."))
            }
        }
        val sound = tone(22.0)
        // Fed in odd-sized pieces, like a decoder does.
        var i = 0
        while (i < sound.size) {
            val n = minOf(1234, sound.size - i)
            chunker.accept(sound.copyOfRange(i, i + n), n)
            i += n
        }
        chunker.finish()
        val cues = chunker.cues()
        assertEquals(listOf("First sentence.", "Second sentence complete.", "Third.", "Last."), cues.map { it.text })
        assertEquals(0.5, cues[0].start, 1e-9)
        assertEquals(7.0, cues[1].start, 1e-9) // 7 s into the file, not 0
        assertEquals(12.0, cues[2].start, 1e-9)
        // Piece 1: 10 s; piece 2: 3 s carried + 7 s new. Its last sentence starts 5 s before its end, more than
        // the 4 s that may be carried, so it is kept and the last 5 s of the file are given on their own.
        assertEquals(listOf(10.0, 10.0, 5.0), calls.map { it.first })
        assertEquals(22.0, chunker.processedSeconds, 1e-9)
    }

    @Test
    fun silenceIsNotTranscribed() {
        var calls = 0
        val chunker = WhisperChunker(chunkSeconds = 5, maxCarrySeconds = 2) { _, _ -> calls++; emptyList() }
        val quiet = ShortArray(rate * 12)
        chunker.accept(quiet, quiet.size)
        chunker.finish()
        assertEquals(0, calls)
        assertTrue(chunker.cues().isEmpty())
        assertEquals(12.0, chunker.processedSeconds, 1e-9)
    }

    @Test
    fun cacheKeysDifferPerEngineSetup() {
        val base = WhisperModels.byId("base")!!
        assertEquals("de", SpeechSetup.Vosk(VoskModels.byCode("de")!!).cacheKey)
        assertEquals("whisper-base-auto", SpeechSetup.Whisper(base, "", false).cacheKey)
        assertEquals("whisper-base-fr-to-en", SpeechSetup.Whisper(base, "fr", true).cacheKey)
        assertEquals(setOf("tiny", "base", "small"), WhisperModels.ALL.map { it.id }.toSet())
        assertTrue(WhisperModels.ALL.all { it.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/") && it.sha256.length == 64 })
        assertEquals(2, SpeechEngineKind.entries.size)
    }
}
