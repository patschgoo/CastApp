package io.github.patschgoo.castapp.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueParserTest {
    @Test
    fun readsSrt() {
        val srt = "\uFEFF1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:02,000 --> 00:01:04,250\r\nSecond\r\n"
        val cues = CueParser.parse(srt)
        assertEquals(listOf(Cue(1.5, 3.0, "Hello\nthere"), Cue(62.0, 64.25, "Second")), cues)
    }

    @Test
    fun readsWebVttWithSettingsAndShortTimes() {
        val vtt = "WEBVTT\n\nNOTE comment\n\nintro\n00:05.000 --> 00:07.000 line:90% align:center\n{\\an8}Top &amp; more\n\n01:00:00.000 --> 01:00:01.000\nLate\n"
        val cues = CueParser.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals(Cue(5.0, 7.0, "Top & more"), cues[0])
        assertEquals(3600.0, cues[1].start, 1e-9)
    }

    @Test
    fun skipsBrokenAndEmptyCues() {
        val text = "1\n00:00:05,000 --> 00:00:04,000\nBackwards\n\n2\n00:00:06,000 --> 00:00:07,000\n<b></b>\n\n3\nno time here\n"
        assertTrue(CueParser.parse(text).isEmpty())
    }

    @Test
    fun delayMovesLaterOrEarlier() {
        val cues = listOf(Cue(1.0, 2.0, "a"), Cue(5.0, 6.0, "b"))
        assertEquals(listOf(Cue(1.5, 2.5, "a"), Cue(5.5, 6.5, "b")), CueParser.delay(cues, 500))
        assertEquals(listOf(Cue(0.0, 0.5, "a"), Cue(3.5, 4.5, "b")), CueParser.delay(cues, -1500))
        assertEquals(cues, CueParser.delay(cues, 0))
    }

    @Test
    fun assemblesEmbeddedSamples() {
        val samples = listOf(
            TimedText(5_000_000, -1, "Open ended (tx3g)"),
            TimedText(1_000_000, 1_500_000, "With a duration"),
            TimedText(7_000_000, -1, ""), // clears the one before
            TimedText(9_000_000, 1_000_000, "Repeat"),
            TimedText(10_000_000, 1_000_000, "Repeat"),
            TimedText(20_000_000, -1, "Last"),
        )
        val cues = EmbeddedSubtitles.assemble(samples)
        assertEquals(
            listOf(
                Cue(1.0, 2.5, "With a duration"),
                Cue(5.0, 7.0, "Open ended (tx3g)"),
                Cue(9.0, 11.0, "Repeat"),
                Cue(20.0, 24.0, "Last"),
            ),
            cues,
        )
    }
}
