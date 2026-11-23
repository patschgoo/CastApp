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

    private fun track(index: Int, lang: String?, name: String? = null, codec: String = "ass", default: Boolean = false, forced: Boolean = false, selected: Boolean = false, sdh: Boolean = false) =
        SubtitleTrack(index, codec, lang, null, default, forced, isExternal = false, name = name, selected = selected, hearingImpaired = sdh, fileOrdinal = index)

    @Test
    fun theLanguagesOfTheSettingInTheirOrder() {
        val tracks = listOf(track(0, "eng"), track(1, "ger"), track(2, "fre", default = true))
        assertEquals(1, PlaybackPlanner.chooseSubtitle(tracks, "jpn", listOf("de", "en"))!!.index)
        assertEquals(0, PlaybackPlanner.chooseSubtitle(tracks, "jpn", listOf("en", "de"))!!.index)
        // None of them: the file's default.
        assertEquals(2, PlaybackPlanner.chooseSubtitle(tracks, "jpn", listOf("it"))!!.index)
        // A full track in the second language beats only signs in the first.
        val signsOnly = listOf(track(0, "ger", "Signs & Songs"), track(1, "eng", "Full"))
        assertEquals(1, PlaybackPlanner.chooseSubtitle(signsOnly, "jpn", listOf("de", "en"))!!.index)
        // ...but signs in the first language beat nothing at all.
        assertEquals(0, PlaybackPlanner.chooseSubtitle(listOf(track(0, "ger", "Signs/Songs")), "jpn", listOf("de", "en"))!!.index)
    }

    @Test
    fun animeReleasesSignsAndSongsAreNotTheFullTrack() {
        val tracks = listOf(track(0, "eng", "Signs & Songs", default = true), track(1, "eng", "Full Subtitles"), track(2, "eng", "Dialogue + Signs"))
        assertEquals(1, PlaybackPlanner.chooseSubtitle(tracks, "jpn", listOf("en"))!!.index)
        assertTrue(tracks[0].isPartial)
        assertFalse(tracks[2].isPartial) // dialogue and signs is the full one
        assertTrue(track(3, "eng", "[Forced]").isPartial)
        // SDH only when there is nothing else in that language.
        val sdh = listOf(track(0, "eng", "SDH"), track(1, "eng", sdh = true), track(2, "eng"))
        assertEquals(2, PlaybackPlanner.chooseSubtitle(sdh, "jpn", listOf("en"))!!.index)
        assertEquals(0, PlaybackPlanner.chooseSubtitle(sdh.take(2), "jpn", listOf("en"))!!.index)
    }

    @Test
    fun plexsPickDecidesAmongEquals() {
        val tracks = listOf(track(0, "eng", "Group A"), track(1, "eng", "Group B", selected = true), track(2, "ger"))
        assertEquals(1, PlaybackPlanner.chooseSubtitle(tracks, "jpn", listOf("en"))!!.index)
        // The setting's language still comes first.
        assertEquals(2, PlaybackPlanner.chooseSubtitle(tracks, "jpn", listOf("de"))!!.index)
        // Sound in the wanted language: what was picked in Plex rather than nothing.
        assertEquals(1, PlaybackPlanner.chooseSubtitle(tracks, "eng", listOf("en"))!!.index)
    }

    @Test
    fun soundInTheFirstLanguageGetsSignsOrForcedOnly() {
        val tracks = listOf(track(0, "ger"), track(1, "ger", "Schilder & Songs"), track(2, "eng"))
        assertEquals(1, PlaybackPlanner.chooseSubtitle(tracks, "ger", listOf("de", "en"))!!.index)
        assertNull(PlaybackPlanner.chooseSubtitle(listOf(track(0, "ger"), track(2, "eng")), "ger", listOf("de", "en")))
        // Foreign sound and no wanted language: a forced track for that sound.
        assertEquals(1, PlaybackPlanner.chooseSubtitle(listOf(track(0, "fre"), track(1, "spa", forced = true)), "spa", listOf("de"))!!.index)
        assertNull(PlaybackPlanner.chooseSubtitle(listOf(track(0, "fre", forced = true)), "spa", listOf("de")))
    }

    @Test
    fun thePickerListsTheWantedLanguagesFirst() {
        val tracks = listOf(track(0, "fre"), track(1, "eng", "Signs"), track(2, "ger"), track(3, "eng"))
        assertEquals(listOf(2, 3, 1, 0), PlaybackPlanner.rankSubtitles(tracks, listOf("de", "en")).map { it.index })
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
