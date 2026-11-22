package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.WatchHistory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitle choice, the reference carried in a play list item, and how server positions reach the resume logic. */
class MediaServerPlanningTest {
    private fun sub(index: Int, lang: String?, codec: String = "subrip", default: Boolean = false, forced: Boolean = false) =
        SubtitleTrack(index, codec, lang, null, default, forced, isExternal = true)

    @Test
    fun languagesMatchInAllSpellings() {
        assertTrue(PlaybackPlanner.languageMatches("ger", "de"))
        assertTrue(PlaybackPlanner.languageMatches("deu", "de"))
        assertTrue(PlaybackPlanner.languageMatches("de", "de"))
        assertTrue(PlaybackPlanner.languageMatches("de-AT", "de"))
        assertTrue(PlaybackPlanner.languageMatches("eng", "en"))
        assertTrue(PlaybackPlanner.languageMatches("fre", "fr"))
        assertFalse(PlaybackPlanner.languageMatches("eng", "de"))
        assertFalse(PlaybackPlanner.languageMatches(null, "de"))
    }

    @Test
    fun picksTheFilesDefaultSubtitle() {
        val tracks = listOf(sub(2, "eng"), sub(3, "ger", default = true))
        assertEquals(3, PlaybackPlanner.chooseSubtitle(tracks, "eng", "en")!!.index)
    }

    @Test
    fun foreignSoundGetsSubtitlesInThePhonesLanguage() {
        val tracks = listOf(sub(2, "eng"), sub(3, "ger", forced = true), sub(4, "ger"), sub(5, "ger", codec = "pgssub"))
        assertEquals(4, PlaybackPlanner.chooseSubtitle(tracks, "eng", "de")!!.index) // full one, not the forced one, never a picture
    }

    @Test
    fun soundInThePhonesLanguageNeedsOnlyForcedSubtitles() {
        val tracks = listOf(sub(2, "ger"), sub(3, "ger", forced = true))
        assertEquals(3, PlaybackPlanner.chooseSubtitle(tracks, "ger", "de")!!.index)
        assertNull(PlaybackPlanner.chooseSubtitle(listOf(sub(2, "ger")), "ger", "de"))
        assertNull(PlaybackPlanner.chooseSubtitle(listOf(sub(2, "eng")), "eng", "en"))
    }

    @Test
    fun noTextSubtitleNoSubtitle() {
        assertNull(PlaybackPlanner.chooseSubtitle(listOf(sub(2, "ger", codec = "pgssub", default = true)), "eng", "de"))
        assertNull(PlaybackPlanner.chooseSubtitle(emptyList(), "eng", "de"))
    }

    @Test
    fun containersAndMimeTypes() {
        fun info(container: String, video: String = "h264", audio: String = "aac") = MediaInfo("1", container, video, audio, 1280, 720, 8, 2, null)
        assertEquals("video/mp4", PlaybackPlanner.mimeForVideo(info("mov,mp4,m4a,3gp,3g2,mj2")))
        assertEquals("mp4", PlaybackPlanner.videoExtension(info("mov,mp4,m4a,3gp,3g2,mj2")))
        assertEquals("video/x-matroska", PlaybackPlanner.mimeForVideo(info("matroska,webm")))
        assertEquals("video/webm", PlaybackPlanner.mimeForVideo(info("matroska,webm", "vp9", "opus")))
        assertEquals("webm", PlaybackPlanner.videoExtension(info("matroska,webm", "vp9", "opus")))
        assertEquals("video/mp4", PlaybackPlanner.mimeForVideo(null))
        assertEquals("audio/mpeg", PlaybackPlanner.mimeForAudio("mp3"))
        assertEquals("audio/flac", PlaybackPlanner.mimeForAudio("flac"))
        assertEquals("audio/mp4", PlaybackPlanner.mimeForAudio("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("flac", PlaybackPlanner.audioExtension("flac"))
    }

    @Test
    fun theReferenceSurvivesTheTripThroughTheCastCode() {
        val ref = ItemRef(ServerKind.JELLYFIN, "acc|1", "item/with space", "src=1&x", "sess1", PlayMethod.TRANSCODE)
        val text = ref.encode()
        assertEquals(7, text.split('|').size) // odd characters can't break the format
        assertEquals(ref, ItemRef.decode(text))
        assertNull(ItemRef.decode(null))
        assertNull(ItemRef.decode("something else"))
        assertNull(ItemRef.decode("ms1|JELLYFIN|only|three"))
        assertNull(ItemRef.decode("ms1|NOPE|a|b|c|d|DIRECT"))

        // Saved with the watch history and read back.
        val item = MediaItem("Film", "http://x/y", "video/mp4", serverRef = text)
        assertEquals(text, MediaItem.fromJson(JSONObject(item.toJson().toString())).serverRef)
        assertNull(MediaItem.fromJson(JSONObject(MediaItem("Film", "http://x/y", "video/mp4").toJson().toString())).serverRef)
    }

    // ---- the server's position wins over what the phone remembers ----------------------------------------------------

    private class MemoryStore : WatchHistory.Store {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private fun entry(n: Int, resumeMs: Long, durationMs: Long = 3_600_000) =
        PlanEntry(MediaItem("Ep $n", "http://srv/ep$n", "video/mp4"), resumeMs, durationMs)

    @Test
    fun serverPositionsBecomeResumePositions() {
        val history = WatchHistory(MemoryStore(), clock = { 5_000_000L })
        // This phone remembers the second episode at 5 min, but the server says it was watched.
        history.record(entry(2, 0).item, 300_000, 3_600_000, force = true)
        val plan = PlaybackPlan(listOf(entry(1, 1_380_000), entry(2, 0), entry(3, 120_000)))
        MediaServers.syncHistory(history, plan, resumeFirst = true)
        assertEquals(1_380_000L - 3000, history.resumePosition(plan.items[0]))
        assertNull(history.resumePosition(plan.items[1])) // the server wins: not resumed
        assertEquals(120_000L - 3000, history.resumePosition(plan.items[2]))
    }

    @Test
    fun startOverForgetsTheFirstItemsPosition() {
        val history = WatchHistory(MemoryStore(), clock = { 5_000_000L })
        val plan = PlaybackPlan(listOf(entry(1, 1_380_000), entry(2, 600_000)))
        MediaServers.syncHistory(history, plan, resumeFirst = false)
        assertNull(history.resumePosition(plan.items[0]))
        assertNotNull(history.resumePosition(plan.items[1])) // the queued ones keep theirs
        assertEquals(1_380_000L, plan.resumeMs)
    }

    @Test
    fun tinyPositionsAreNotResumed() {
        val history = WatchHistory(MemoryStore(), clock = { 5_000_000L })
        val plan = PlaybackPlan(listOf(entry(1, 10_000)))
        MediaServers.syncHistory(history, plan, resumeFirst = true)
        assertNull(history.resumePosition(plan.items[0]))
    }
}
