package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.mediaservers.ServerSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which subtitle track inside the file a CastApp TV shows when it plays a Plex file itself. */
class TextTrackHintTest {
    private val found = listOf(
        TextTrackHint.Found("en", "Signs & Songs"),
        TextTrackHint.Found("en", "Full Subtitles"),
        TextTrackHint.Found("de", null),
        TextTrackHint.Found("de", "PGS"),
    )

    @Test
    fun travelsAsText() {
        val hint = TextTrackHint(track = 1, language = "eng", title = "Full & more", languages = listOf("de", "en"))
        assertEquals(hint, TextTrackHint.decode(hint.encode()))
        assertEquals(listOf("eng", "de", "en"), hint.preferred)
        assertEquals(TextTrackHint(off = true), TextTrackHint.decode("off"))
        assertNull(TextTrackHint.decode(null))
        assertNull(TextTrackHint.decode(""))
        assertNull(TextTrackHint.decode("nonsense"))
        // In the metadata for the TV, and back.
        val didl = Dlna.didl("Vela · S1 E1", "http://srv/file.mkv?X-Plex-Token=t", "video/x-matroska", false, subtitleTrack = hint.encode())
        assertTrue(didl.contains(Dlna.SUBTITLE_TRACK_PREFIX))
        val media = UpnpRenderer.parseMedia("http://srv/file.mkv?X-Plex-Token=t", didl)
        assertEquals(hint, TextTrackHint.decode(media.subtitleTrack))
        assertNull(UpnpRenderer.parseMedia("http://srv/file.mkv", Dlna.didl("Film", "http://srv/file.mkv", "video/mp4", false)).subtitleTrack)
        // The subtitle look goes along for a track inside the file too, not only for a file next to it.
        val styled = Dlna.didl("Vela", "http://srv/file.mkv", "video/x-matroska", false, subtitleStyle = "look", subtitleTrack = hint.encode())
        assertEquals("look", UpnpRenderer.parseMedia("http://srv/file.mkv", styled).subtitleStyle)
    }

    @Test
    fun picksTheTrackThePhoneShows() {
        // At its place, when the language agrees (Plex says "eng", Media3 "en").
        assertEquals(1, TextTrackHint(track = 1, language = "eng", title = "Full Subtitles").pick(found))
        assertEquals(2, TextTrackHint(track = 2, language = "ger").pick(found))
        // Places shifted: by language and name, else the first in that language.
        val shifted = listOf(TextTrackHint.Found("ja", null)) + found
        assertEquals(2, TextTrackHint(track = 0, language = "eng", title = "Full Subtitles").pick(shifted))
        assertEquals(1, TextTrackHint(track = 0, language = "eng", title = "Something else").pick(shifted))
        assertEquals(2, TextTrackHint(track = 7, language = "ger").pick(found))
        // Nothing like it: the player decides (in the preferred languages).
        assertNull(TextTrackHint(track = 9, language = "fre").pick(found))
        assertNull(TextTrackHint(off = true).pick(found))
        assertNull(TextTrackHint(languages = listOf("de")).pick(found))
    }

    @Test
    fun whatThePhoneTellsTheTv() {
        val inFile = ServerSubtitle("eng", "Full Subtitles", "ass", track = 1).attachTo("http://srv/library/parts/1/2/file.mkv?X-Plex-Token=t")
        assertEquals(TextTrackHint(track = 1, language = "eng", title = "Full Subtitles", languages = listOf("de", "en")), TextTrackHint.forTv(inFile, false, listOf("de", "en")))
        assertEquals(TextTrackHint(off = true), TextTrackHint.forTv(inFile, true, listOf("de")))
        // Another subtitle file goes next to the video as before; nothing picked: the TV's player picks.
        assertNull(TextTrackHint.forTv(ServerSubtitle("fre", null, "srt").attachTo("http://srv/library/streams/7?X-Plex-Token=t"), false, listOf("de")))
        assertNull(TextTrackHint.forTv("http://nas/Film.de.srt", false, listOf("de")))
        assertNull(TextTrackHint.forTv(null, false, listOf("de")))
    }
}
