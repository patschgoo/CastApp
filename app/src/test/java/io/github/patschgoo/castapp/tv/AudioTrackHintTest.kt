package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.mediaservers.ChosenAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `castapp:audiotrack:`: which sound track inside the file a CastApp TV plays (dual audio: Japanese + German dub). */
class AudioTrackHintTest {
    /** A dual-audio anime episode as Media3 lists its sound tracks. */
    private val dual = listOf(
        TrackHint.Found("ja", "Japanese"),
        TrackHint.Found("de", "German Dub"),
        TrackHint.Found("de", "Commentary"),
    )

    @Test
    fun hasTheSameFieldsAsTheSubtitleTrack() {
        val hint = TrackHint(track = 1, language = "ger", title = "German Dub", languages = listOf("de", "ja"))
        assertEquals("t=1&lang=ger&title=German%20Dub&prefer=de%2Cja", hint.encode())
        assertEquals(hint, TrackHint.decode(hint.encode()))
        // The subtitle hint writes and reads exactly the same text.
        val text = TextTrackHint(track = 1, language = "ger", title = "German Dub", languages = listOf("de", "ja"))
        assertEquals(text.encode(), hint.encode())
        assertEquals(text, TextTrackHint.decode(hint.encode()))
        assertEquals(hint, text.asTrackHint)
        assertEquals(TrackHint(languages = listOf("ja")), TrackHint.decode("prefer=ja"))
        assertNull(TrackHint.decode("title=only%20a%20name"))
        assertNull(TrackHint.decode("  "))
        assertEquals("", TrackHint().encode())
    }

    @Test
    fun picksTheWantedSoundTrack() {
        // At its place when the language agrees (Plex says "ger", Media3 "de").
        assertEquals(1, TrackHint(track = 1, language = "ger").pick(dual))
        assertEquals(2, TrackHint(track = 2, language = "ger", title = "Commentary").pick(dual))
        // The place doesn't fit: by language and name, else the first in that language.
        assertEquals(1, TrackHint(track = 0, language = "deu", title = "German Dub").pick(dual))
        assertEquals(1, TrackHint(track = 0, language = "de").pick(dual))
        assertEquals(0, TrackHint(language = "jpn").pick(dual))
        // Only languages to prefer: the TV's player chooses among the tracks in them.
        assertNull(TrackHint(languages = listOf("de")).pick(dual))
        // No such language: the player decides.
        assertNull(TrackHint(language = "fre").pick(dual))
    }

    @Test
    fun travelsInTheMetadataOnlyWhenGiven() {
        val hint = TrackHint(track = 1, language = "ger", title = "German Dub").encode()
        val didl = Dlna.didl("Vela · S1 E1", "http://srv/file.mkv?X-Plex-Token=t", "video/x-matroska", false, audioTrack = hint)
        assertTrue(didl.contains("<desc id=\"castapp-audiotrack\" nameSpace=\"urn:castapp\">castapp:audiotrack:t=1&amp;lang=ger&amp;title=German%20Dub</desc>"))
        val media = UpnpRenderer.parseMedia("http://srv/file.mkv?X-Plex-Token=t", didl)
        assertEquals(hint, media.audioTrack)
        assertNull(media.subtitleTrack) // the two markers don't mix up
        assertEquals(hint, Dlna.audioTrackIn(didl))
        // Without one, the metadata is as before.
        val plain = Dlna.didl("Film", "http://srv/file.mkv", "video/x-matroska", false)
        assertFalse(plain.contains("castapp"))
        assertNull(UpnpRenderer.parseMedia("http://srv/file.mkv", plain).audioTrack)
        // Next to a subtitle track hint.
        val both = Dlna.didl("Film", "http://srv/file.mkv", "video/x-matroska", false, subtitleTrack = "lang=en", audioTrack = "prefer=ja")
        val m = UpnpRenderer.parseMedia("http://srv/file.mkv", both)
        assertEquals("lang=en", m.subtitleTrack)
        assertEquals("prefer=ja", m.audioTrack)
    }

    @Test
    fun theServersPickBecomesTheHint() {
        assertEquals("t=1&lang=ger&title=German%20Dub", ChosenAudio("ger", "German Dub", 1).hint())
        assertEquals("lang=jpn", ChosenAudio("jpn", null, null).hint())
        assertEquals("t=0", ChosenAudio(null, null, 0).hint())
        assertNull(ChosenAudio(null, "Main", null).hint())
    }
}
