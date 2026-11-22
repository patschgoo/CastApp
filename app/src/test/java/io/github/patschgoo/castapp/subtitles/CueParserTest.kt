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
    fun readsAss() {
        val ass = """
            [Script Info]
            ; made up for the test
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.00,0:00:06.25,Default,,0,0,0,,Later, but listed first
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\i1}Hello, you,{\i0}\Nthere
            Comment: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,Not shown
            Dialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,{\an8\pos(320,50)\c&H00FFFF&}Top\hsign\nnext
            Dialogue: 0,0:00:20.00,0:00:21.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}
            Dialogue: 1,0:00:30.00,0:00:31.00,Default,,0,0,0,,{\bord3}Twice
            Dialogue: 0,0:00:30.00,0:00:31.00,Default,,0,0,0,,Twice
            Dialogue: 0,0:00:30.00,0:00:31.00,Sign,,0,0,0,,Shop
            Dialogue: 0,1:02:03.40,1:02:04.00,Default,,0,0,0,,
        """.trimIndent()
        assertEquals(
            listOf(
                Cue(1.5, 3.0, "Hello, you,\nthere"),
                Cue(5.0, 6.25, "Later, but listed first"),
                Cue(10.0, 12.0, "Top sign next"), // \n is a space without WrapStyle 2
                Cue(30.0, 31.0, "Twice\nShop"),
            ),
            CueParser.parse(ass),
        )
    }

    @Test
    fun readsAssWithoutFormatLineAndSsa() {
        // No Format line: the v4+ field order. Windows line ends and a byte order mark.
        val bare = "﻿[Events]\r\nDialogue: 0,0:01:00.5,0:01:02.00,Default,,0,0,0,,One, two, three\r\n"
        assertEquals(listOf(Cue(60.5, 62.0, "One, two, three")), CueParser.parse(bare))
        // Only Dialogue lines, no sections at all.
        assertEquals(listOf(Cue(1.0, 2.0, "Just this")), CueParser.parse("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Just this\n"))
        // SubStation Alpha v4 (.ssa): "Marked" instead of Layer, fields in another order here.
        val ssa = """
            [Script Info]
            ScriptType: v4.00

            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:04.10,0:00:05.20,Default,NTP,0000,0000,0000,!Effect,Old style
        """.trimIndent()
        assertEquals(listOf(Cue(4.1, 5.2, "Old style")), CueParser.parse(ssa))
        val reordered = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:07.00,0:00:08.00,Short, format\n"
        assertEquals(listOf(Cue(7.0, 8.0, "Short, format")), CueParser.parse(reordered))
    }

    @Test
    fun assSoftLineBreaksFollowWrapStyle() {
        fun script(wrapStyle: String?) = listOfNotNull(
            "[Script Info]",
            "ScriptType: v4.00+",
            wrapStyle?.let { "WrapStyle: $it" },
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            """Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Moss and \nfern\Nby the brook""",
            """Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Wide \h\h gap\n""",
        ).joinToString("\n")
        // libass / VSFilter: \n breaks the line only with WrapStyle 2, else it is a space; \N always breaks it.
        val soft = listOf(Cue(1.0, 2.0, "Moss and fern\nby the brook"), Cue(3.0, 4.0, "Wide gap"))
        assertEquals(soft, CueParser.parse(script(null)))
        assertEquals(soft, CueParser.parse(script("0")))
        assertEquals(soft, CueParser.parse(script("1")))
        assertEquals(soft, CueParser.parse(script("3")))
        assertEquals(listOf(Cue(1.0, 2.0, "Moss and\nfern\nby the brook"), Cue(3.0, 4.0, "Wide gap")), CueParser.parse(script("2")))
        assertEquals(listOf(Cue(1.0, 2.0, "Moss and\nfern\nby the brook"), Cue(3.0, 4.0, "Wide gap")), CueParser.parse(script(" 2").replace("WrapStyle", "wrapstyle")))
    }

    @Test
    fun srtMentioningDialogueStaysSrt() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nDialogue: is what they said\n"
        assertEquals(listOf(Cue(1.0, 2.0, "Dialogue: is what they said")), CueParser.parse(srt))
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
