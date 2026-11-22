package io.github.patschgoo.castapp.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueBuilderTest {
    @Test
    fun voskResultToCues() {
        val json = """{"result":[
            {"conf":1.0,"start":0.50,"end":0.80,"word":"hello"},
            {"conf":1.0,"start":0.85,"end":1.20,"word":"there"},
            {"conf":1.0,"start":3.00,"end":3.40,"word":"general"},
            {"conf":0.4,"start":3.45,"end":3.90,"word":"kenobi"}
        ],"text":"hello there general kenobi"}"""
        val words = CueBuilder.wordsFromVosk(json, offsetSeconds = 60.0)
        assertEquals(4, words.size)
        assertEquals(60.5, words[0].start, 0.001)
        val cues = CueBuilder.build(words)
        assertEquals(2, cues.size) // split at the 1.8 s pause
        assertEquals("hello there", cues[0].text)
        assertEquals(60.5, cues[0].start, 0.001)
        assertTrue(cues[0].end >= 61.7) // short cue kept on screen ≥ 1.2 s
        assertTrue(cues[0].end <= cues[1].start)
        val vtt = CueBuilder.toWebVtt(cues)
        assertTrue(vtt.startsWith("WEBVTT\n\n00:01:00.500 --> "))
        assertTrue(CueBuilder.toSrt(cues).startsWith("1\n00:01:00,500 --> "))
    }

    @Test
    fun longSpeechIsSplitAndWrapped() {
        val words = (0 until 40).map { Word("word$it", it * 0.3, it * 0.3 + 0.25) }
        val cues = CueBuilder.build(words)
        assertTrue(cues.size > 1)
        for (c in cues) {
            assertTrue(c.end - c.start <= 6.5)
            assertTrue(c.text.lines().size <= 2)
            assertTrue(c.text.lines().all { it.length <= 60 })
        }
        assertEquals(CueBuilder.wrap("short"), "short")
        assertEquals("00:00:01.234", CueBuilder.time(1.234, '.'))
    }
}
