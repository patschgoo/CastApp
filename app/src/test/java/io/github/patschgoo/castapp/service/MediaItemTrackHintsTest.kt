package io.github.patschgoo.castapp.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MediaItem.audioTrack] and [MediaItem.subtitleTrack] in the JSON that the watch history, hand-over, join and the service share. */
class MediaItemTrackHintsTest {
    @Test
    fun theHintsTravelInTheJson() {
        val item = MediaItem(
            "Vela 01", "https://media.example/01.mkv", "video/x-matroska",
            audioTrack = "t=1&lang=ger&title=German%20Dub", subtitleTrack = "off",
        )
        val json = item.toJson()
        assertEquals("t=1&lang=ger&title=German%20Dub", json.getString("audioTrack"))
        assertEquals("off", json.getString("subTrack"))
        assertEquals(item, MediaItem.fromJson(JSONObject(json.toString())))
        assertEquals(listOf(item), MediaItem.listFromJson(MediaItem.listToJson(listOf(item))))
        assertTrue(item.startsWithoutSubtitles)
        assertFalse(item.copy(subtitleTrack = "lang=de").startsWithoutSubtitles)
    }

    @Test
    fun withoutHintsTheJsonIsAsBefore() {
        val item = MediaItem("Film.mkv", "smb://src1/Movies/Film.mkv", "video/x-matroska")
        assertFalse(item.toJson().has("audioTrack"))
        assertFalse(item.toJson().has("subTrack"))
        // As 4.1.0 wrote it.
        val old = """{"title":"Film","uri":"https://example.com/film.mp4","mime":"video/mp4","sub":"","headers":{},"live":false,""" +
            """"trustAll":false,"ref":"","orig":"","origMime":""}"""
        val back = MediaItem.fromJson(JSONObject(old))
        assertNull(back.audioTrack)
        assertNull(back.subtitleTrack)
        assertFalse(back.startsWithoutSubtitles)
    }
}
