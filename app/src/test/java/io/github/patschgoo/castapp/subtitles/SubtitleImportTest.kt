package io.github.patschgoo.castapp.subtitles

import io.github.patschgoo.castapp.stream.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleImportTest {
    @Test
    fun srtWithBomWindowsLineEndsAndTags() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Wake up,</i>\r\nNeo.\r\n\r\n" +
            "2\r\n00:01:02,25 --> 00:01:04,000\r\n{\\an8}The Matrix has you.\r\n\r\n" +
            "3\r\n01:00:00,000 --> 01:00:01,000\r\n\r\n"
        val cues = SubtitleImport.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(Cue(1.0, 3.5, "<i>Wake up,</i>\nNeo."), cues[0])
        assertEquals(62.25, cues[1].start, 1e-9)
        assertEquals("The Matrix has you.", cues[1].text)
    }

    @Test
    fun webVttWithHeaderNotesIdsAndSettings() {
        val vtt = """
            WEBVTT - made by hand

            NOTE this is a comment
            spanning lines

            STYLE
            ::cue { color: yellow }

            intro
            00:05.000 --> 00:07.250 align:start position:10%
            Short time format

            01:02:03.004 --> 01:02:04.000
            Long one
        """.trimIndent()
        val cues = SubtitleImport.parse(vtt)
        assertEquals(listOf(Cue(5.0, 7.25, "Short time format"), Cue(3723.004, 3724.0, "Long one")), cues)
    }

    @Test
    fun roundTripThroughTheWrittenSubtitlePath() {
        val cues = listOf(Cue(1.0, 2.0, "One"), Cue(65.5, 70.0, "Two\nlines"))
        assertEquals(cues, SubtitleImport.parse(CueBuilder.toSrt(cues)))
        assertEquals(cues, SubtitleImport.parse(CueBuilder.toWebVtt(cues)))
        // The same as the existing SRT → WebVTT conversion produces.
        assertEquals(cues, SubtitleImport.parse(Subtitles.toWebVtt(CueBuilder.toSrt(cues))))
        // And shifted like written subtitles for a converted video.
        assertEquals(listOf(Cue(0.5, 5.0, "Two\nlines")), CueBuilder.shift(SubtitleImport.parse(CueBuilder.toSrt(cues)), 65.0))
    }

    @Test
    fun garbageHasNoCues() {
        assertTrue(SubtitleImport.parse("<html>Too many requests</html>").isEmpty())
        assertTrue(SubtitleImport.parse("").isEmpty())
    }
}
