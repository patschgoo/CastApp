package io.github.patschgoo.castapp.stream

import io.github.patschgoo.castapp.subtitles.Cue
import io.github.patschgoo.castapp.subtitles.CueBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleShiftTest {
    private val vtt = """
        WEBVTT

        1
        00:00:05.000 --> 00:00:07.500
        Too early

        2
        00:01:00.000 --> 00:01:03.250 align:center
        Hello there

        00:59:59.900 --> 01:00:01.100
        Hour boundary
    """.trimIndent()

    @Test
    fun shiftsWebVttAndDropsPastCues() {
        val out = Subtitles.shift(vtt, 30_000)
        assertTrue(out.startsWith("WEBVTT"))
        assertFalse(out.contains("Too early"))
        assertTrue(out.contains("00:00:30.000 --> 00:00:33.250 align:center\nHello there"))
        assertTrue(out.contains("00:59:29.900 --> 00:59:31.100\nHour boundary"))
    }

    @Test
    fun cueStartingBeforeOffsetIsClampedToZero() {
        val out = Subtitles.shift(vtt, 61_000)
        assertTrue(out.contains("00:00:00.000 --> 00:00:02.250 align:center"))
    }

    @Test
    fun shiftsSrtKeepingCommas() {
        val srt = "1\r\n00:00:10,000 --> 00:00:12,000\r\nHallo Welt\r\n\r\n2\r\n00:00:20,500 --> 00:00:21,000\r\nZwei\r\n"
        val out = Subtitles.shift(srt, 10_000)
        assertTrue(out.contains("00:00:00,000 --> 00:00:02,000\nHallo Welt"))
        assertTrue(out.contains("00:00:10,500 --> 00:00:11,000\nZwei"))
    }

    @Test
    fun zeroOffsetLeavesTextAlone() {
        assertEquals(vtt, Subtitles.shift(vtt, 0))
    }

    @Test
    fun parsesShortAndLongTimes() {
        assertEquals(65_250L, Subtitles.parseTime("01:05.250"))
        assertEquals(3_723_004L, Subtitles.parseTime("1:02:03,004"))
    }

    @Test
    fun generatedCuesShiftAndRoundTrip() {
        val cues = listOf(Cue(1.0, 2.0, "eins"), Cue(10.0, 12.5, "zwei\ndrei"))
        val shifted = CueBuilder.shift(cues, 5.0)
        assertEquals(listOf(Cue(5.0, 7.5, "zwei\ndrei")), shifted)
        assertEquals(cues, CueBuilder.fromJson(CueBuilder.toJson(cues)))
    }
}
