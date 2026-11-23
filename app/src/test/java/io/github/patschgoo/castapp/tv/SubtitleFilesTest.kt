package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.tv.SubtitleFiles.FoundText
import io.github.patschgoo.castapp.tv.SubtitleFiles.TextPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitle files over DLNA: their format, several of them (`castapp:subfiles:`), and which one CastApp TV shows. */
class SubtitleFilesTest {
    private val ssa = "text/x-ssa"
    private val srt = "application/x-subrip"
    private val vtt = "text/vtt"

    @Test
    fun theFormatComesFromTheTypeElseTheAddress() {
        val table = listOf(
            // (type the sender gave, address) to Media3's type
            Triple("text/x-ssa", "http://pc/sub?id=1", ssa),
            Triple("text/x-ass", "http://pc/sub?id=1", ssa),
            Triple("application/x-ssa", "http://pc/sub?id=1", ssa),
            Triple("text/ssa", "http://pc/sub?id=1", ssa),
            Triple("TEXT/X-SSA; charset=utf-8", "http://pc/sub", ssa),
            Triple(null, "http://pc/Episode%2001.de.ass", ssa),
            Triple(null, "http://pc/Episode 01.SSA?token=a.srt", ssa),
            Triple("application/x-subrip", "http://pc/sub", srt),
            Triple("text/srt", "http://pc/sub.vtt", srt), // the sender's type wins over the name
            Triple(null, "http://pc/Episode.en.srt", srt),
            Triple("srt", "http://pc/sub", srt), // sec:type="srt"
            Triple("text/vtt", "http://pc/sub", vtt),
            Triple(null, "http://pc/sub.vtt#t=1", vtt),
            Triple("vtt", "http://pc/sub", vtt),
            // Nothing says: DLNA's usual subtitle file.
            Triple(null, "http://pc/sub?id=7", srt),
            Triple("text/plain", "http://pc/sub.ass", ssa),
            Triple("*", "http://pc/sub", srt),
        )
        for ((declared, url, expected) in table) assertEquals("$declared $url", expected, SubtitleFiles.mimeFor(declared, url))
        // Files it can't show are left out.
        assertNull(SubtitleFiles.mimeFor("smi/caption", "http://pc/sub.smi"))
        assertNull(SubtitleFiles.mimeFor(null, "http://pc/sub.sup"))
        assertNull(SubtitleFiles.mimeFor(null, "http://pc/movie.idx"))
    }

    @Test
    fun whatCastAppsPhoneSendsStaysSrt() {
        // The phone serves the subtitles it shows at .../st/<item>/<choice>/<start>/<offset>.srt (or .vtt).
        val srtUrl = "http://192.168.1.5:8090/tok/st/3/file1/0/-500.srt"
        val didl = Dlna.didl("Episode 1", "http://192.168.1.5:8090/tok/file/3/m/Episode.mkv", "video/x-matroska", false, srtUrl)
        val m = UpnpRenderer.parseMedia("http://192.168.1.5:8090/tok/file/3/m/Episode.mkv", didl)
        assertEquals(srtUrl, m.subtitleUri)
        assertEquals("text/srt", m.subtitleMime)
        assertEquals(srt, SubtitleFiles.mimeFor(m.subtitleMime, m.subtitleUri!!))
        assertEquals(SubtitleFiles.Plan(listOf(SubtitleFiles.Sidecar(SubtitleFiles.SIDECAR_ID, srtUrl, srt)), shown = 0), SubtitleFiles.plan(m))
        val vttUrl = "http://192.168.1.5:8090/tok/st/3/file/0/0.vtt"
        val v = UpnpRenderer.parseMedia("http://x/f.mp4", Dlna.didl("F", "http://x/f.mp4", "video/mp4", false, vttUrl))
        assertEquals(vtt, SubtitleFiles.mimeFor(v.subtitleMime, v.subtitleUri!!))
    }

    @Test
    fun oneAssFileTheDlnaWay() {
        // A desktop app: the file as a second <res>, Samsung's CaptionInfoEx and PacketVideo's attribute.
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/" xmlns:pv="http://www.pv.com/pvns/"><item id="1" parentID="0" restricted="1"><dc:title>Vela 01</dc:title><upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="http-get:*:video/x-matroska:*" pv:subtitleFileUri="http://10.0.0.7:43210/s/1?k=a&amp;f=ass" pv:subtitleFileType="ass">http://10.0.0.7:43210/v/1?k=a</res>""" +
            """<res protocolInfo="http-get:*:text/x-ssa:*">http://10.0.0.7:43210/s/1?k=a&amp;f=ass</res>""" +
            """<sec:CaptionInfoEx sec:type="ass">http://10.0.0.7:43210/s/1?k=a&amp;f=ass</sec:CaptionInfoEx></item></DIDL-Lite>"""
        val m = UpnpRenderer.parseMedia("http://10.0.0.7:43210/v/1?k=a", didl)
        assertEquals("http://10.0.0.7:43210/s/1?k=a&f=ass", m.subtitleUri)
        assertEquals("text/x-ssa", m.subtitleMime)
        assertEquals(ssa, SubtitleFiles.plan(m).files.single().mime)

        // Only the <res>, only CaptionInfoEx with its type, only the PacketVideo attribute: each is enough.
        fun only(body: String) = UpnpRenderer.parseMedia(
            "http://pc/v.mkv",
            """<DIDL-Lite><item id="1"><dc:title>V</dc:title><res protocolInfo="http-get:*:video/x-matroska:*">http://pc/v.mkv</res>$body</item></DIDL-Lite>""",
        )
        only("""<res protocolInfo="http-get:*:application/x-ssa:*">http://pc/s?id=1</res>""").let {
            assertEquals("http://pc/s?id=1", it.subtitleUri)
            assertEquals(ssa, SubtitleFiles.mimeFor(it.subtitleMime, it.subtitleUri!!))
        }
        only("""<sec:CaptionInfoEx sec:type="ssa">http://pc/s?id=2</sec:CaptionInfoEx>""").let {
            assertEquals("ssa", it.subtitleMime)
            assertEquals(ssa, SubtitleFiles.mimeFor(it.subtitleMime, it.subtitleUri!!))
        }
        val pv = UpnpRenderer.parseMedia(
            "http://pc/v.mkv",
            """<DIDL-Lite><item id="1"><res protocolInfo="http-get:*:video/x-matroska:*" pv:subtitleFileUri="http://pc/s?id=3" pv:subtitleFileType="srt">http://pc/v.mkv</res></item></DIDL-Lite>""",
        )
        assertEquals("http://pc/s?id=3", pv.subtitleUri)
        assertEquals(srt, SubtitleFiles.mimeFor(pv.subtitleMime, pv.subtitleUri!!))
        // No subtitles: none.
        assertNull(only("").subtitleUri)
        assertTrue(SubtitleFiles.plan(only("")).files.isEmpty())
    }

    private val files = listOf(
        SubtitleFile("http://pc/s/1.en.ass", "en", "Signs & Songs", "text/x-ssa"),
        SubtitleFile("http://pc/s/2.en.ass", "en", "Full"),
        SubtitleFile("http://pc/s/3?lang=de", "ger", "Full", "application/x-subrip"),
        SubtitleFile("http://pc/s/4.vtt"),
    )

    @Test
    fun severalFilesTravelAsJsonInTheMetadata() {
        val json = SubtitleFiles.encode(files)
        val didl = Dlna.didl("Vela 01", "http://pc/v.mkv", "video/x-matroska", false, subtitleFiles = json, subtitleTrack = "lang=de&prefer=en", subtitleStyle = "size=1.35")
        assertTrue(didl.contains("<desc id=\"castapp-subfiles\" nameSpace=\"urn:castapp\">castapp:subfiles:[{&quot;"))
        assertFalse(didl.contains("Signs & Songs")) // XML-escaped
        val m = UpnpRenderer.parseMedia("http://pc/v.mkv", didl)
        assertEquals(files, m.subtitleFiles)
        assertNull(m.subtitleUri)
        assertEquals("size=1.35", m.subtitleStyle) // the look goes with the files too
        assertEquals(json, Dlna.subtitleFilesIn(didl))
        assertTrue(UpnpRenderer.parseMedia("http://pc/v.mkv", Dlna.didl("V", "http://pc/v.mkv", "video/mp4", false)).subtitleFiles.isEmpty())
    }

    @Test
    fun aReceiverReadsTheListForgivingly() {
        val json = """[{"url": "http://pc/a.srt", "language": "de", "label": "  ", "mime": 7},
            {"url": "file:///etc/passwd"}, {"language": "en"}, "http://pc/plain.srt", {"url": "http://pc/a.srt"},
            {"url": " https://pc/b.ass "}]"""
        assertEquals(listOf(SubtitleFile("http://pc/a.srt", "de"), SubtitleFile("https://pc/b.ass")), SubtitleFiles.decode(json))
        assertTrue(SubtitleFiles.decode("not json").isEmpty())
        assertTrue(SubtitleFiles.decode("""{"url": "http://pc/a.srt"}""").isEmpty())
        assertTrue(SubtitleFiles.decode(null).isEmpty())
        val many = SubtitleFiles.encode((0 until 30).map { SubtitleFile("http://pc/$it.srt") })
        assertEquals(SubtitleFiles.MAX, SubtitleFiles.decode(many).size)
    }

    @Test
    fun theHintsLanguagesPickTheFileToShow() {
        // lang= first, then prefer=, each in the files' order; the name for lang=.
        assertEquals(2, SubtitleFiles.shown(files, TrackHint.decode("lang=de&prefer=en")))
        assertEquals(0, SubtitleFiles.shown(files, TrackHint.decode("prefer=fr,en")))
        assertEquals(1, SubtitleFiles.shown(files, TrackHint.decode("lang=eng&title=full")))
        assertEquals(2, SubtitleFiles.shown(files, TrackHint.decode("prefer=ger")))
        // Nothing in those languages, or no hint: the first file.
        assertEquals(0, SubtitleFiles.shown(files, TrackHint.decode("lang=ja")))
        assertEquals(0, SubtitleFiles.shown(files, null))
        assertNull(SubtitleFiles.shown(files, TrackHint.decode("off")))
        assertNull(SubtitleFiles.shown(emptyList(), null))
    }

    private fun media(
        subtitleUri: String? = null,
        subtitleTrack: String? = null,
        subtitleFiles: List<SubtitleFile> = emptyList(),
    ) = TvMedia(
        uri = "http://pc/v.mkv", metadata = "", title = "V", mimeType = "video/x-matroska", kind = TvMediaKind.VIDEO,
        subtitleUri = subtitleUri, live = false, lowLatency = false, subtitleTrack = subtitleTrack, subtitleFiles = subtitleFiles,
    )

    @Test
    fun whatTheTvLoadsAndShows() {
        // One plain file: shown, the hint doesn't count (as before).
        val plain = SubtitleFiles.plan(media(subtitleUri = "http://phone/st/1/file/0/0.srt", subtitleTrack = "off"))
        assertEquals(listOf(SubtitleFiles.Sidecar(SubtitleFiles.SIDECAR_ID, "http://phone/st/1/file/0/0.srt", srt)), plain.files)
        assertEquals(0, plain.shown)
        assertFalse(plain.off)
        assertNull(plain.languages)
        assertNull(plain.inFile)

        // Several files: all loaded with language and name, the hint picks among them.
        val several = SubtitleFiles.plan(media(subtitleTrack = "lang=de", subtitleFiles = files))
        assertEquals(
            listOf(
                SubtitleFiles.Sidecar("castapp-subfile-0", "http://pc/s/1.en.ass", ssa, "en", "Signs & Songs"),
                SubtitleFiles.Sidecar("castapp-subfile-1", "http://pc/s/2.en.ass", ssa, "en", "Full"),
                SubtitleFiles.Sidecar("castapp-subfile-2", "http://pc/s/3?lang=de", srt, "ger", "Full"),
                SubtitleFiles.Sidecar("castapp-subfile-3", "http://pc/s/4.vtt", vtt),
            ),
            several.files,
        )
        assertEquals(2, several.shown)
        assertEquals(listOf("de"), several.languages)
        // "off": loaded (the TV's menu offers them), none shown.
        SubtitleFiles.plan(media(subtitleTrack = "off", subtitleFiles = files)).let {
            assertEquals(4, it.files.size)
            assertNull(it.shown)
            assertTrue(it.off)
        }
        // A plain file next to the list joins it at the end, unless it's one of them; ones the TV can't show are left out.
        val both = SubtitleFiles.plan(media(subtitleUri = "http://pc/extra.srt", subtitleFiles = files.take(1) + SubtitleFile("http://pc/x.sup")))
        assertEquals(listOf("http://pc/s/1.en.ass", "http://pc/extra.srt"), both.files.map { it.url })
        assertEquals(0, both.shown)
        assertEquals(1, SubtitleFiles.plan(media(subtitleUri = "http://pc/s/1.en.ass", subtitleFiles = files.take(1))).files.size)

        // No files: the hint picks the track inside the file.
        SubtitleFiles.plan(media(subtitleTrack = "t=1&lang=jpn&prefer=de")).let {
            assertTrue(it.files.isEmpty())
            assertEquals(TrackHint(track = 1, language = "jpn", languages = listOf("de")), it.inFile)
            assertEquals(listOf("jpn", "de"), it.languages)
        }
        SubtitleFiles.plan(media(subtitleTrack = "off")).let {
            assertTrue(it.off)
            assertNull(it.inFile)
        }
        assertEquals(SubtitleFiles.Plan(), SubtitleFiles.plan(media()))
    }

    @Test
    fun theTrackToShowOnceThePlayerKnowsThem() {
        val inFile = listOf(FoundText("0:2", "en", "Signs"), FoundText("0:3", "en", "Full"))
        val plain = SubtitleFiles.plan(media(subtitleUri = "http://phone/s.srt"))
        // The file isn't prepared yet: ask again later.
        assertEquals(TextPick.NotYet, SubtitleFiles.pickText(plain, inFile))
        assertEquals(TextPick.Show(2), SubtitleFiles.pickText(plain, inFile + FoundText("1:castapp-sidecar", null, null)))
        val several = SubtitleFiles.plan(media(subtitleTrack = "lang=de", subtitleFiles = files))
        val loaded = inFile + (0..3).map { FoundText("${it + 1}:castapp-subfile-$it", null, null) }
        assertEquals(TextPick.Show(4), SubtitleFiles.pickText(several, loaded))
        assertEquals(TextPick.Player, SubtitleFiles.pickText(SubtitleFiles.plan(media(subtitleTrack = "off", subtitleFiles = files)), loaded))
        // Ten files and more: "castapp-subfile-1" is not "castapp-subfile-11".
        val eleven = (0..11).map { SubtitleFile("http://pc/$it.srt", if (it == 1) "fr" else null) }
        val found = (11 downTo 0).map { FoundText("${it + 1}:castapp-subfile-$it", null, null) }
        assertEquals(TextPick.Show(10), SubtitleFiles.pickText(SubtitleFiles.plan(media(subtitleTrack = "lang=fr", subtitleFiles = eleven)), found))
        // Inside the file, as castapp:subtrack: always did.
        assertEquals(TextPick.Show(1), SubtitleFiles.pickText(SubtitleFiles.plan(media(subtitleTrack = "t=1&lang=eng&title=Full")), inFile))
        assertEquals(TextPick.Player, SubtitleFiles.pickText(SubtitleFiles.plan(media(subtitleTrack = "prefer=en")), inFile))
        assertEquals(TextPick.Player, SubtitleFiles.pickText(SubtitleFiles.plan(media()), inFile))
    }
}
