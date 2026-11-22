package io.github.patschgoo.castapp.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SidecarsTest {
    private val english = listOf("en")

    private fun best(video: String, subtitles: List<String>, languages: List<String> = english) =
        Sidecars.ranked(video, subtitles, languages) { it }.first()

    @Test
    fun aSubtitleBelongsToTheVideoItIsNamedAfter() {
        assertTrue(Sidecars.matches("Trip 1", "Trip 1"))
        assertTrue(Sidecars.matches("trip 1.English", "Trip 1"))
        assertTrue(Sidecars.matches("Trip 1 .German", "Trip 1"))
        assertTrue(Sidecars.matches("Trip 1_English", "Trip 1"))
        assertTrue(Sidecars.matches("Trip 1-en", "Trip 1"))
        assertTrue(Sidecars.matches("Trip 1[eng]", "Trip 1"))
        assertFalse(Sidecars.matches("Trip 10", "Trip 1")) // a digit goes on: another episode
        assertFalse(Sidecars.matches("Trip 1b", "Trip 1"))
        assertFalse(Sidecars.matches("Trip", "Trip 1"))
        assertFalse(Sidecars.matches("Other 1", "Trip 1"))
    }

    @Test
    fun theLongestNameOwnsTheSubtitle() {
        val videos = listOf("Zap S2 Ep. 06.mkv", "Zap S2 Ep. 06-deleted.mkv", "Zap S2 Ep. 1.mkv", "Zap S2 Ep. 10.mkv", "Twin.mkv", "Twin.mp4")
        val subtitles = listOf(
            "Zap S2 Ep. 06.Englisch.srt", "Zap S2 Ep. 06-deleted.Englisch.srt", "Zap S2 Ep. 10.srt", "Zap S2 Ep. 1.en.srt",
            "Twin.srt", "Nobody.srt",
        )
        val owned = Sidecars.assign(videos, subtitles)
        assertEquals(listOf(0), owned[0])
        assertEquals(listOf(1), owned[1])
        assertEquals(listOf(3), owned[2]) // "Ep. 1" doesn't take "Ep. 10.srt"
        assertEquals(listOf(2), owned[3])
        assertEquals(listOf(4), owned[4]) // two videos of the same name share it
        assertEquals(listOf(4), owned[5])
    }

    @Test
    fun languagesFromFileNames() {
        assertEquals("en", Sidecars.language("English"))
        assertEquals("en", Sidecars.language("English2"))
        assertEquals("en", Sidecars.language("English10"))
        assertEquals("en", Sidecars.language("Englisch"))
        assertEquals("en", Sidecars.language("eng"))
        assertEquals("en", Sidecars.language("Eng"))
        assertEquals("en", Sidecars.language("en"))
        assertEquals("de", Sidecars.language("German"))
        assertEquals("de", Sidecars.language("Deutsch"))
        assertEquals("de", Sidecars.language("ger"))
        assertEquals("de", Sidecars.language("deu"))
        assertEquals("es", Sidecars.language("Español"))
        assertEquals("es", Sidecars.language("Espanol"))
        assertEquals("pt", Sidecars.language("Português"))
        assertEquals("pt", Sidecars.language("Portuguese2"))
        assertEquals("fr", Sidecars.language("fre"))
        assertEquals("ar", Sidecars.language("Arabic"))
        assertEquals("nb", Sidecars.language("Bokmal"))
        assertEquals("ga", Sidecars.language("ga")) // codes win over names (the Ga language)
        assertNull(Sidecars.language("forced"))
        assertNull(Sidecars.language("1080p"))
        assertNull(Sidecars.language("2"))
        assertEquals(listOf("English2"), Sidecars.tagWords("Trip 1.mp4", "Trip 1.English2.srt"))
        assertEquals(listOf("eng", "forced"), Sidecars.tagWords("Trip 1.mp4", "Trip 1 [eng] (forced).srt"))
        assertEquals(emptyList<String>(), Sidecars.tagWords("Trip 1.mp4", "Trip 1.srt"))
        assertEquals(listOf("German"), Sidecars.tagWords(null, "whatever.German.srt"))
    }

    /** One file per language, as a download tool leaves them: the first name in the alphabet used to win. */
    private val manyLanguages = listOf(
        "Arabic", "Bokmal", "Chinese", "Chinese2", "Czech", "Danish", "Dutch", "English", "Finnish", "French", "German", "Greek",
        "Hebrew", "Hungarian", "Indonesian", "Italian", "Japanese", "Korean", "Polish", "Portuguese", "Portuguese2", "Romanian",
        "Russian", "Spanish", "Spanish2", "Swedish", "Thai", "Turkish", "Vietnamese",
    ).map { "Gala 01.$it.srt" }

    @Test
    fun thePreferredLanguageWinsOverTheAlphabet() {
        assertEquals(29, manyLanguages.size)
        assertEquals("Gala 01.English.srt", best("Gala 01.mp4", manyLanguages))
        assertEquals("Gala 01.German.srt", best("Gala 01.mp4", manyLanguages, listOf("de", "en")))
        assertEquals("Gala 01.Spanish.srt", best("Gala 01.mp4", manyLanguages, listOf("es", "en")))
        // None of the preferred: the alphabet (natural order) decides, as before.
        assertEquals("Gala 01.Arabic.srt", best("Gala 01.mp4", manyLanguages, listOf("xx")))
        // The whole list: preferred first, the rest after it in order.
        val ranked = Sidecars.ranked("Gala 01.mp4", manyLanguages, listOf("de", "en")) { it }
        assertEquals(listOf("Gala 01.German.srt", "Gala 01.English.srt", "Gala 01.Arabic.srt", "Gala 01.Bokmal.srt"), ranked.take(4))
        assertEquals(29, ranked.size)
    }

    @Test
    fun numberedCopiesComeInNaturalOrder() {
        val subs = listOf("Mystery (2001).English10.srt", "Mystery (2001).English9.srt", "Mystery (2001).English2.srt", "Mystery (2001).Arabic.srt")
        assertEquals(
            listOf("Mystery (2001).English2.srt", "Mystery (2001).English9.srt", "Mystery (2001).English10.srt", "Mystery (2001).Arabic.srt"),
            Sidecars.ranked("Mystery (2001).mp4", subs, english) { it },
        )
        assertEquals("Mystery (2001).English.srt", best("Mystery (2001).mp4", subs + "Mystery (2001).English.srt"))
    }

    @Test
    fun otherSpellingsOfTheLanguage() {
        assertEquals("Vid S1 Ep. 1.Englisch.srt", best("Vid S1 Ep. 1.mkv", listOf("Vid S1 Ep. 1.Arabisch.srt", "Vid S1 Ep. 1.Englisch.srt")))
        assertEquals("Mov_eng.srt", best("Mov.mkv", listOf("Mov_ara.srt", "Mov_eng.srt")))
        assertEquals("Mov [Eng].srt", best("Mov.mkv", listOf("Mov [Ara].srt", "Mov [Eng].srt")))
        assertEquals("Mov .English.srt", best("Mov.mkv", listOf("Mov .Arabic.srt", "Mov .English.srt")))
        assertEquals("Mov-en.srt", best("Mov.mkv", listOf("Mov-ar.srt", "Mov-en.srt")))
        assertEquals("Mov.Español.srt", best("Mov.mkv", listOf("Mov.English.srt", "Mov.Español.srt"), listOf("es", "en")))
        assertEquals("Mov.Portugues.srt", best("Mov.mkv", listOf("Mov.English.srt", "Mov.Portugues.srt"), Sidecars.preferredLanguages("pt-br", Locale.ENGLISH)))
    }

    @Test
    fun untaggedFileComesAfterTheLanguagesBeforeTheRest() {
        val subs = listOf("Mov.Arabic.srt", "Mov.srt", "Mov.English.srt")
        assertEquals(listOf("Mov.English.srt", "Mov.srt", "Mov.Arabic.srt"), Sidecars.ranked("Mov.mkv", subs, english) { it })
        assertEquals(listOf("Mov.srt", "Mov.Arabic.srt", "Mov.English.srt"), Sidecars.ranked("Mov.mkv", subs, listOf("de")) { it })
    }

    @Test
    fun srtBeforeAssOfTheSameLanguage() {
        val subs = listOf("Cel 01.English.ass", "Cel 01.English.srt", "Cel 01.German.ssa")
        assertEquals(listOf("Cel 01.English.srt", "Cel 01.English.ass", "Cel 01.German.ssa"), Sidecars.ranked("Cel 01.mkv", subs, english) { it })
        // An ASS file in the preferred language still beats an SRT in another.
        assertEquals("Cel 01.German.ssa", best("Cel 01.mkv", subs, listOf("de")))
        assertEquals("Cel 01.English.ass", best("Cel 01.mkv", listOf("Cel 01.English.ass")))
    }

    @Test
    fun preferredLanguagesKeepTheSettingsOrder() {
        assertEquals(listOf("de", "en"), Sidecars.preferredLanguages("de,en", Locale.ENGLISH))
        assertEquals(listOf("fr", "de", "en"), Sidecars.preferredLanguages("fr; de", Locale.GERMAN))
        assertEquals(listOf("pt", "es", "en"), Sidecars.preferredLanguages("pt-BR es_ES", Locale.ENGLISH))
        assertEquals(listOf("de", "en"), Sidecars.preferredLanguages("ger", Locale.ENGLISH))
        // Nothing chosen: the phone's language, then English.
        assertEquals(listOf("de", "en"), Sidecars.preferredLanguages("", Locale.GERMANY))
        assertEquals(listOf("en"), Sidecars.preferredLanguages(" ", Locale.US))
        assertEquals(listOf("he", "en"), Sidecars.preferredLanguages("iw", Locale.ENGLISH))
    }

    @Test
    fun pickerLabels() {
        assertEquals("English", Sidecars.label("Gala 01.mp4", "Gala 01.English10.srt", Locale.ENGLISH))
        assertEquals("Englisch", Sidecars.label("Gala 01.mp4", "Gala 01.eng.srt", Locale.GERMAN))
        assertEquals("Deutsch", Sidecars.label("Gala 01.mp4", "Gala 01.German.srt", Locale.GERMAN))
        assertEquals("English (forced)", Sidecars.label("Gala 01.mp4", "Gala 01 [eng] (forced).srt", Locale.ENGLISH))
        assertEquals("Norwegian Bokmål", Sidecars.label("Gala 01.mp4", "Gala 01.Bokmal.srt", Locale.ENGLISH))
        assertEquals("Commentary", Sidecars.label("Gala 01.mp4", "Gala 01.Commentary.srt", Locale.ENGLISH))
        assertEquals("Filipino", Sidecars.label("Gala 01.mp4", "Gala 01.Filipino.srt", Locale.ENGLISH))
        assertNull(Sidecars.label("Gala 01.mp4", "Gala 01.srt", Locale.ENGLISH))
        assertNull(Sidecars.label("Some Film", "Stream.vtt", Locale.ENGLISH))
        assertNull(Sidecars.label("Some Film", "703", Locale.ENGLISH))
    }

    @Test
    fun fileNamesFromAddresses() {
        assertEquals("Gala 01.English.srt", Sidecars.fileName("smb://ab12cd34/Shows/Gala/Gala 01.English.srt"))
        assertEquals("100% Real.srt", Sidecars.fileName("smb://ab12cd34/Shows/100% Real.srt"))
        assertEquals("Gala 01+.English.srt", Sidecars.fileName("https://cloud.example/dav/Gala%2001+.English.srt"))
        assertEquals("Gala.en.vtt", Sidecars.fileName("http://nas.local/subs/Gala.en.vtt?token=x"))
        assertEquals("Gala.de.srt", Sidecars.fileName("content://com.android.externalstorage.documents/document/primary%3AMovies%2FGala.de.srt"))
    }
}
