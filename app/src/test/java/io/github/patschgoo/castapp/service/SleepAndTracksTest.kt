package io.github.patschgoo.castapp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepAndTracksTest {
    @Test
    fun sleepPlanCountsDown() {
        val plan = SleepPlan.after(30, nowElapsed = 1_000)
        assertEquals(30 * 60_000L, plan.remainingMs(1_000))
        assertEquals(60_000L, plan.remainingMs(1_000 + 29 * 60_000L))
        assertFalse(plan.isDue(1_000 + 30 * 60_000L - 1))
        assertTrue(plan.isDue(1_000 + 30 * 60_000L))
        assertEquals(0L, plan.remainingMs(1_000 + 31 * 60_000L))
        assertEquals(listOf(15, 30, 45, 60, 90), SleepPlan.CHOICES)
    }

    @Test
    fun sleepAtItemEndNeverFiresByTime() {
        val plan = SleepPlan.atEndOfItem()
        assertFalse(plan.isDue(Long.MAX_VALUE))
        assertEquals(-1L, plan.remainingMs(0))
    }

    @Test
    fun sleepFormat() {
        assertEquals("30:00", SleepPlan.format(30 * 60_000L))
        assertEquals("0:01", SleepPlan.format(1)) // rounds up: never shows 0:00 while running
        assertEquals("1:30:00", SleepPlan.format(90 * 60_000L))
    }

    @Test
    fun describesAudioTracks() {
        assertEquals("English · AC-3 · 5.1", AudioTrackInfo(0, "eng", "audio/ac3", 6).describe())
        assertEquals("German · AAC · stereo", AudioTrackInfo(1, "ger", "audio/mp4a-latm", 2).describe())
        assertEquals("French · E-AC-3 · 7.1", AudioTrackInfo(1, "fr", "audio/eac3", 8).describe())
        assertEquals("Director's comments · Opus · mono", AudioTrackInfo(2, "en", "audio/opus", 1, label = "Director's comments").describe())
        assertEquals("DTS", AudioTrackInfo(0, "und", "audio/vnd.dts", 0).describe())
    }

    @Test
    fun describesSubtitleTracks() {
        val srt = TextTrackInfo(0, "spa", "application/x-subrip")
        assertTrue(srt.supported)
        assertEquals("Spanish · SRT", srt.describe())
        assertEquals("Track 2 · SSA/ASS · forced", TextTrackInfo(1, null, "text/x-ssa", forced = true).describe())
        assertFalse(TextTrackInfo(2, "eng", "application/pgs").supported)
        assertTrue(TextTrackInfo(3, "eng", "application/x-quicktime-tx3g").supported)
        assertTrue(TextTrackInfo(3, "eng", "text/vtt").supported)
        val list = TrackList(text = listOf(srt, TextTrackInfo(1, "eng", "application/pgs"), TextTrackInfo(2, "eng", "application/vobsub")))
        assertEquals(listOf(srt), list.textSupported)
        assertEquals(2, list.pictureSubtitles)
    }

    @Test
    fun languageNames() {
        assertEquals("English", TrackNames.language("en"))
        assertEquals("English", TrackNames.language("eng"))
        assertEquals("German", TrackNames.language("deu"))
        assertEquals("German", TrackNames.language("de-AT"))
        assertEquals(null, TrackNames.language("und"))
        assertEquals(null, TrackNames.language(""))
        assertEquals("5.1", TrackNames.channels(6))
        assertEquals("3 channels", TrackNames.channels(3))
        assertEquals(null, TrackNames.channels(0))
    }
}
