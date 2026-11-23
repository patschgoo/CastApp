package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.service.TextTrackInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** A server's subtitle track as a subtitle file of the play list: its address, the picker's names, finding it in the file. */
class ServerSubtitleTest {
    private var locale: Locale = Locale.getDefault()

    @Before
    fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restore() = Locale.setDefault(locale)

    private val file = "http://192.168.1.5:32400/library/parts/611/1/file.mkv?X-Plex-Token=a%2Bb"

    @Test
    fun travelsBehindTheAddress() {
        val sub = ServerSubtitle("eng", "Signs & Songs #2 = 100%", "ass", forced = true, hearingImpaired = true, track = 3)
        val uri = sub.attachTo(file)
        assertTrue(uri.startsWith("$file#castapp-sub&"))
        assertEquals(sub, ServerSubtitle.parse(uri))
        assertEquals(file, ServerSubtitle.address(uri))
        assertTrue(ServerSubtitle.isInFile(uri))
        // Again on an address that has one already: replaced, not added.
        assertEquals(uri, sub.attachTo(uri))
        val external = ServerSubtitle("fre", null, "srt").attachTo("http://srv/library/streams/7?encoding=utf-8&X-Plex-Token=t")
        assertFalse(ServerSubtitle.isInFile(external))
        assertEquals(ServerSubtitle("fre", null, "srt"), ServerSubtitle.parse(external))
        // Any other subtitle file isn't one.
        assertNull(ServerSubtitle.parse("http://nas/Film.srt"))
        assertNull(ServerSubtitle.parse("http://nas/Film.srt#castapp-subtitles"))
        assertNull(ServerSubtitle.parse("content://docs/document/msf%3A41"))
    }

    @Test
    fun namesInThePicker() {
        assertEquals("English (Signs & Songs)", ServerSubtitle("eng", "Signs & Songs", "ass", track = 0).label())
        assertEquals("German", ServerSubtitle("ger", null, "srt", track = 2).label())
        assertEquals("German", ServerSubtitle("ger", "German", "srt").label()) // the name only repeats the language
        assertEquals("Commentary", ServerSubtitle(null, "Commentary", "srt").label())
        assertEquals("Track 3", ServerSubtitle(null, null, "srt", track = 2).label())
        assertEquals("Inside the video file · SSA/ASS · forced", ServerSubtitle("eng", null, "ass", forced = true, track = 0).detail())
        assertEquals("SRT · SDH", ServerSubtitle("eng", null, "srt", hearingImpaired = true).detail())
    }

    private fun listed(ordinal: Int, language: String?, label: String? = null, mime: String = "text/x-ssa") = TextTrackInfo(ordinal, language, mime, label)

    @Test
    fun findsTheTrackAmongThoseMedia3Reads() {
        val file = listOf(listed(0, "en", "Signs & Songs"), listed(1, "en", "Full"), listed(2, "de", mime = "application/x-subrip"), listed(3, "de", mime = "application/pgs"))
        // At the server's place when the language agrees (Media3 says "en", Plex "eng").
        assertEquals(1, ServerSubtitle.ordinalIn(file, ServerSubtitle("eng", "Full", "ass", track = 1)))
        assertEquals(2, ServerSubtitle.ordinalIn(file, ServerSubtitle("ger", null, "srt", track = 2)))
        // Places shifted (a track Media3 doesn't read): by language and name.
        val shifted = listOf(listed(0, "ja"), listed(1, "en", "Full"), listed(2, "en", "Signs & Songs"))
        assertEquals(2, ServerSubtitle.ordinalIn(shifted, ServerSubtitle("eng", "Signs & Songs", "ass", track = 0)))
        assertEquals(1, ServerSubtitle.ordinalIn(shifted, ServerSubtitle("eng", "Something else", "ass", track = 0)))
        assertEquals(2, ServerSubtitle.ordinalIn(file, ServerSubtitle("ger", null, "srt", track = 5)))
        // Not a picture track, not another language.
        assertNull(ServerSubtitle.ordinalIn(file, ServerSubtitle("ger", null, "pgs", track = 3).copy(language = "fre")))
        assertNull(ServerSubtitle.ordinalIn(emptyList(), ServerSubtitle("eng", null, "ass", track = 0)))
    }
}
