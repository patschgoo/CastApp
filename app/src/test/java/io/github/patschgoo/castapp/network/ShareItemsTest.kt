package io.github.patschgoo.castapp.network

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.stream.Subtitles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The play list made from a share or cloud folder: which files play, their types and their subtitle files. */
class ShareItemsTest {
    private class Prefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class Ctx : ContextWrapper(null) {
        val prefs = Prefs()
        override fun getApplicationContext() = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private val network = NetworkSources(Ctx())
    private val smb = NetworkSource("ab12cd34", SourceKind.SMB, "Test", "127.0.0.1", "Share")
    private val dav = NetworkSource("dav1", SourceKind.WEBDAV, "Cloud", "https://cloud.example/dav/", user = "u", password = "p")

    private fun folder(vararg names: String) = names.map { RemoteEntry(it, "D/$it", false, size = 1) }.sortedWith(NaturalOrder.entries)

    private fun items(vararg names: String, languages: List<String> = listOf("en")) = network.itemsFor(smb, folder(*names), languages)

    @Test
    fun theSubtitleInTheWantedLanguagePlays() {
        val langs = listOf("Arabic", "Chinese", "English", "German", "Spanish")
        val list = items("Gala 01.mp4", *langs.map { "Gala 01.$it.srt" }.toTypedArray())
        val item = list.single()
        assertEquals("smb://ab12cd34/D/Gala 01.English.srt", item.subtitleUri)
        // All of them stay on offer, in order.
        assertEquals(
            listOf("Arabic", "Chinese", "German", "Spanish").map { "smb://ab12cd34/D/Gala 01.$it.srt" },
            item.moreSubtitles,
        )
        assertEquals(5, item.subtitleFiles.size)
        val german = network.itemsFor(smb, folder("Gala 01.mp4", *langs.map { "Gala 01.$it.srt" }.toTypedArray()), listOf("de", "en")).single()
        assertEquals("smb://ab12cd34/D/Gala 01.German.srt", german.subtitleUri)
    }

    @Test
    fun aDeletedSceneKeepsItsOwnSubtitle() {
        val list = items("Zap S2 Ep. 06.mkv", "Zap S2 Ep. 06-deleted.mkv", "Zap S2 Ep. 06.Englisch.srt", "Zap S2 Ep. 06-deleted.Englisch.srt")
        assertEquals("smb://ab12cd34/D/Zap S2 Ep. 06.Englisch.srt", list.first { it.title == "Zap S2 Ep. 06.mkv" }.subtitleUri)
        assertEquals(emptyList<String>(), list.first { it.title == "Zap S2 Ep. 06.mkv" }.moreSubtitles)
        assertEquals("smb://ab12cd34/D/Zap S2 Ep. 06-deleted.Englisch.srt", list.first { it.title == "Zap S2 Ep. 06-deleted.mkv" }.subtitleUri)
        // Episode 1 doesn't take episode 10's subtitle.
        val eps = items("Ep 1.mkv", "Ep 10.mkv", "Ep 10.srt")
        assertNull(eps.first { it.title == "Ep 1.mkv" }.subtitleUri)
        assertEquals("smb://ab12cd34/D/Ep 10.srt", eps.first { it.title == "Ep 10.mkv" }.subtitleUri)
    }

    @Test
    fun assSubtitlesAreFoundButSrtComesFirst() {
        assertTrue(Subtitles.isSubtitleFile("Cel 01.English.ass"))
        assertTrue(Subtitles.isSubtitleFile("Cel 01.English2.SSA"))
        assertEquals("smb://ab12cd34/D/Cel 01.English.ass", items("Cel 01.mkv", "Cel 01.English.ass").single().subtitleUri)
        val both = items("Cel 01.mkv", "Cel 01.English.ass", "Cel 01.English.srt").single()
        assertEquals("smb://ab12cd34/D/Cel 01.English.srt", both.subtitleUri)
        assertEquals(listOf("smb://ab12cd34/D/Cel 01.English.ass"), both.moreSubtitles)
        // Subtitle files aren't items of their own.
        assertEquals(listOf("Cel 01.mkv"), items("Cel 01.mkv", "Cel 01.ass", "Cel 01.ssa").map { it.title })
    }

    @Test
    fun divxFilesPlay() {
        assertTrue(NetworkSources.isMediaName("Fight 07.divx"))
        assertTrue(NetworkSources.isMediaName("Fight 08.DivX"))
        val list = items("Fight 06.mkv", "Fight 07.divx", "Fight 07.srt", "Fight 08.avi")
        assertEquals(listOf("Fight 06.mkv", "Fight 07.divx", "Fight 08.avi"), list.map { it.title })
        assertEquals("video/x-msvideo", list[1].mimeType)
        assertEquals("video/x-msvideo", list[2].mimeType)
        assertEquals("smb://ab12cd34/D/Fight 07.srt", list[1].subtitleUri)
        assertEquals("video/x-msvideo", MediaItem.mimeForName("Fight 07.divx"))
        assertEquals("video/x-msvideo", MediaItem.mimeForUrl("http://nas.local/Fight%2007.divx?x=1"))
    }

    @Test
    fun hashAndQuestionMarkAreKeptInNames() {
        val item = items("Doodle - S01E04 Brew #9 [720p].mkv", "Doodle - S01E04 Brew #9 [720p].eng.srt").single()
        assertEquals("video/x-matroska", item.mimeType)
        assertEquals("smb://ab12cd34/D/Doodle - S01E04 Brew #9 [720p].eng.srt", item.subtitleUri)
        assertEquals("video/x-matroska", items("What?.mkv").single().mimeType)
        assertEquals("audio/flac", items("Song #2.flac").single().mimeType)
        assertEquals("video/x-matroska", MediaItem.mimeForName("Doodle #9.mkv"))
        // Addresses keep URL rules: the fragment and the query are not part of the file.
        assertEquals("video/x-matroska", MediaItem.mimeForUrl("https://example.com/v/clip.mkv#t=10"))
        assertEquals("video/mp4", MediaItem.mimeForUrl("https://example.com/v/clip#9.mkv"))
        assertEquals("application/x-mpegurl", MediaItem.mimeForUrl("https://example.com/live.m3u8?token=a.mkv"))
    }

    @Test
    fun webDavItemsUseTheSameRules() {
        val entries = listOf("Gala 02.mkv", "Gala 02.Arabic.srt", "Gala 02.English.srt", "Show #3.divx").map {
            RemoteEntry(it, "https://cloud.example/dav/" + it.replace(" ", "%20").replace("#", "%23"), false)
        } + RemoteEntry("Served.bin", "https://cloud.example/dav/Served.bin", false, mimeType = "video/mp4")
        val list = network.itemsFor(dav, entries.sortedWith(NaturalOrder.entries), listOf("en"))
        assertEquals(listOf("Gala 02.mkv", "Served.bin", "Show #3.divx"), list.map { it.title })
        assertEquals("https://cloud.example/dav/Gala%2002.English.srt", list[0].subtitleUri)
        assertEquals(listOf("https://cloud.example/dav/Gala%2002.Arabic.srt"), list[0].moreSubtitles)
        assertEquals("video/mp4", list[1].mimeType) // the server's type wins
        assertEquals("video/x-msvideo", list[2].mimeType)
        assertFalse(list[0].headers.isEmpty())
    }

    @Test
    fun pickedFilesUseTheSameRules() {
        val named = listOf(
            Triple("content://p/1", "Gala 03.mkv", "video/x-matroska"),
            Triple("content://p/2", "Gala 03.Arabic.srt", ""),
            Triple("content://p/3", "Gala 03.Englisch.srt", "application/x-subrip"),
            Triple("content://p/4", "Gala 03-deleted.mkv", "video/x-matroska"),
            Triple("content://p/5", "Gala 03-deleted.English.ass", ""),
            Triple("content://p/6", "Cover.jpg", "image/jpeg"),
        )
        val list = MediaItem.fromNamed(named, listOf("en"))
        assertEquals(listOf("Gala 03.mkv", "Gala 03-deleted.mkv", "Cover.jpg"), list.map { it.title })
        assertEquals("content://p/3", list[0].subtitleUri)
        assertEquals(listOf("content://p/2"), list[0].moreSubtitles)
        assertEquals("content://p/5", list[1].subtitleUri)
        assertNull(list[2].subtitleUri)
        // One video: every subtitle picked with it is its, whatever their names.
        val one = MediaItem.fromNamed(
            listOf(Triple("content://p/1", "VID_0001.mp4", "video/mp4"), Triple("content://p/2", "Film.German.srt", ""), Triple("content://p/3", "Film.English.srt", "")),
            listOf("en"),
        ).single()
        assertEquals("content://p/3", one.subtitleUri)
        assertEquals(listOf("content://p/2"), one.moreSubtitles)
    }

    @Test
    fun moreSubtitlesTravelAsJson() {
        val item = items("Gala 01.mp4", "Gala 01.English.srt", "Gala 01.German.srt", "Gala 01.Arabic.ass").single()
        val back = MediaItem.fromJson(JSONObject(item.toJson().toString()))
        assertEquals(item, back)
        assertEquals(2, back.moreSubtitles.size)
        // Written before there were more: still readable, and without them nothing new is written.
        val old = MediaItem.fromJson(JSONObject("""{"title":"Old","uri":"smb://ab12cd34/D/Old.mkv","mime":"video/x-matroska","sub":"smb://ab12cd34/D/Old.srt"}"""))
        assertEquals("smb://ab12cd34/D/Old.srt", old.subtitleUri)
        assertEquals(emptyList<String>(), old.moreSubtitles)
        assertFalse(old.toJson().has("subs"))
        assertEquals(emptyList<String>(), MediaItem("No subs", "smb://x/y.mkv", "video/x-matroska").subtitleFiles)
    }
}
