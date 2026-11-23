package io.github.patschgoo.castapp.mediaservers

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plex: subtitle tracks inside the file (anime MKVs with ASS/SRT in several languages) next to
 * external ones, the addresses they are read from, which one is shown, and queued episodes.
 */
class PlexSubtitlesTest {
    private val info = ClientInfo(deviceId = "dev-1", deviceName = "Pixel 8", version = "4.0.0")
    private var server: TestServer? = null

    @Before
    fun clean() = PlexClient.forgetStreams()

    @After
    fun stop() {
        server?.close()
        PlexClient.forgetStreams()
    }

    private fun account(base: String = "http://192.168.1.5:32400") =
        ServerAccount("plex-mach1", ServerKind.PLEX, "Shield", base, token = "srvtok", serverId = "mach1")

    private fun urls(base: String = "http://192.168.1.5:32400") = PlexUrls(account(base), info, PlaybackCaps.TV)

    private fun client(base: String = "http://192.168.1.5:32400", languages: List<String> = listOf("de", "en")) =
        PlexClient(account(base), info, subtitleLanguages = { languages })

    /** As PMS 1.43 answers /library/metadata/{id}: Japanese sound, two English ASS tracks, a German SRT one, PGS, an external file, captions. */
    private fun streams(id: String = "201") = """[
        {"id":${id}1,"streamType":1,"index":0,"codec":"hevc","bitDepth":10,"width":1920,"height":1080},
        {"id":${id}2,"streamType":2,"index":1,"codec":"aac","channels":2,"languageCode":"jpn","languageTag":"ja","selected":true},
        {"id":${id}3,"streamType":3,"index":2,"codec":"ass","language":"English","languageTag":"en","languageCode":"eng","title":"Signs & Songs","displayTitle":"English (ASS)","extendedDisplayTitle":"Signs & Songs (English ASS)"},
        {"id":${id}4,"streamType":3,"index":3,"codec":"ass","languageCode":"eng","title":"Full Subtitles","displayTitle":"English (ASS)","default":true},
        {"id":${id}5,"streamType":3,"index":4,"codec":"srt","languageCode":"ger","languageTag":"de","displayTitle":"Deutsch (SRT)","selected":true},
        {"id":${id}6,"streamType":3,"index":5,"codec":"pgs","languageCode":"ger","displayTitle":"Deutsch (PGS)"},
        {"id":${id}7,"streamType":3,"codec":"srt","languageCode":"fre","key":"/library/streams/${id}7","displayTitle":"Français (SRT External)","format":"srt"},
        {"id":${id}8,"streamType":3,"index":0,"codec":"eia_608","embeddedInVideo":"1","languageCode":"eng","displayTitle":"English (CC)"}]"""

    private fun episode(id: String, n: Int, withStreams: Boolean = true) = """
        {"ratingKey":"$id","type":"episode","title":"Ep $n","grandparentTitle":"Vela","grandparentRatingKey":"200","index":$n,"parentIndex":1,"duration":1440000,
         "Media":[{"id":5$id,"container":"mkv","videoCodec":"hevc","audioCodec":"aac","width":1920,"height":1080,
           "Part":[{"id":6$id,"key":"/library/parts/6$id/1700000000/file.mkv","container":"mkv"${if (withStreams) ""","Stream":${streams(id)}""" else ""}}]}]}
    """.trimIndent()

    private fun part(id: String) = "/library/parts/6$id/1700000000/file.mkv"

    // ---- reading the streams -----------------------------------------------------------------------------------

    @Test
    fun readsTheTracksInsideTheFile() {
        val media = PlexParser.item(JSONObject(episode("201", 1)), urls()).media!!
        assertTrue(media.streamDetails)
        assertEquals("jpn", media.audioLanguage)
        val subs = media.subtitles
        assertEquals(6, subs.size)
        // Their place among the file's subtitle tracks, the picture one included; external files and captions in the picture have none.
        assertEquals(listOf(0, 1, 2, 3, null, null), subs.map { it.fileOrdinal })
        val signs = subs[0]
        assertEquals("Signs & Songs", signs.name)
        assertEquals("eng", signs.language)
        assertTrue(signs.isPartial) // not flagged forced, but only signs and songs
        assertFalse(subs[1].isPartial)
        assertTrue(subs[1].isDefault)
        assertTrue(subs[2].selected)
        assertFalse(subs[3].isText) // PGS
        assertEquals("/library/streams/2017", subs[4].key)
        assertFalse(PlexParser.item(JSONObject(episode("202", 2, withStreams = false)), urls()).media!!.streamDetails)
    }

    // ---- where they are read from -------------------------------------------------------------------------------

    @Test
    fun addressesOfSubtitleFiles() {
        val media = PlexParser.item(JSONObject(episode("201", 1)), urls()).media!!
        val u = urls()
        // Inside the file: the file itself, which track behind "#" (never sent to the server).
        val german = u.subtitle(media.subtitles[2], media.partKey)!!
        assertEquals("http://192.168.1.5:32400${part("201")}?X-Plex-Token=srvtok#castapp-sub&t=2&lang=ger&codec=srt", german)
        val sub = ServerSubtitle.parse(german)!!
        assertTrue(sub.inFile)
        assertEquals(2, sub.track)
        assertEquals("http://192.168.1.5:32400${part("201")}?X-Plex-Token=srvtok", ServerSubtitle.address(german))
        assertEquals("Signs & Songs", ServerSubtitle.parse(u.subtitle(media.subtitles[0], media.partKey)!!)!!.title)
        // External: from /library/streams/{id}, in UTF-8 like Plex's own players ask for it.
        val french = u.subtitle(media.subtitles[4], media.partKey)!!
        assertEquals("http://192.168.1.5:32400/library/streams/2017?encoding=utf-8&X-Plex-Token=srvtok#castapp-sub&lang=fre&codec=srt", french)
        assertFalse(ServerSubtitle.parse(french)!!.inFile)
        val sami = media.subtitles[4].copy(codec = "smi")
        assertTrue(u.subtitle(sami, media.partKey)!!.contains("?encoding=utf-8&format=srt&X-Plex-Token=srvtok#"))
        // Pictures can't be sent; captions inside the picture aren't a track.
        assertNull(u.subtitle(media.subtitles[3], media.partKey))
        assertNull(u.subtitle(media.subtitles[5], media.partKey))
        assertNull(u.subtitle(media.subtitles[2], null))
    }

    @Test
    fun tracksInsideTheFileOnlyFromAServerAtHome() {
        assertTrue(PlexUrls.isLocal("http://192.168.1.5:32400"))
        assertTrue(PlexUrls.isLocal("https://192-168-1-5.0123abcd.plex.direct:32400"))
        assertTrue(PlexUrls.isLocal("http://shield:32400"))
        assertFalse(PlexUrls.isLocal("https://84-1-2-3.0123abcd.plex.direct:32400"))
        assertFalse(PlexUrls.isLocal("https://plex.example.com"))
        // Far away the whole file would have to come over the internet: only the external file is offered.
        val far = "https://84-1-2-3.0123abcd.plex.direct:32400"
        val entry = client(far, languages = listOf("fr")).entryFor(PlexParser.item(JSONObject(episode("201", 1)), urls(far)))
        assertEquals(listOf("fre"), entry.item.subtitleFiles.map { ServerSubtitle.parse(it)!!.language })
        assertNull(client(far).entryFor(PlexParser.item(JSONObject(episode("201", 1)), urls(far))).item.subtitleUri) // French only: not for a de/en viewer
    }

    // ---- which one is shown ---------------------------------------------------------------------------------------

    @Test
    fun theSubtitleLanguageGoesFirstAndEveryTextTrackIsOffered() {
        val item = PlexParser.item(JSONObject(episode("201", 1)), urls())
        val hls = client(languages = listOf("de", "en")).entryFor(item).item
        assertTrue(hls.isHls)
        val tracks = hls.subtitleFiles.map { ServerSubtitle.parse(it)!! }
        // German first (the setting), then English (full before signs), then French; no picture subtitles.
        assertEquals(listOf("ger", "eng", "eng", "fre"), tracks.map { it.language })
        assertEquals(listOf(2, 1, 0, null), tracks.map { it.track })
        assertEquals(hls.subtitleUri, hls.subtitleFiles.first())
        // CastApp sends them: Plex's transcoder must not burn the one picked in Plex into the picture as well.
        val q = query(hls.uri)
        assertEquals("none", q["subtitles"])
        assertEquals("1", q["skipSubtitles"])
        // The file itself is what CastApp TV gets first; it holds the same tracks.
        assertEquals("http://192.168.1.5:32400${part("201")}?X-Plex-Token=srvtok", hls.original)

        // English wanted: the full English track, not "Signs & Songs" (though both are English ASS).
        val english = client(languages = listOf("en")).entryFor(item).item
        assertEquals(1, ServerSubtitle.parse(english.subtitleUri!!)!!.track)
        // Neither: Plex's pick (the German one).
        val italian = client(languages = listOf("it")).entryFor(item).item
        assertEquals(2, ServerSubtitle.parse(italian.subtitleUri!!)!!.track)
    }

    @Test
    fun soundInTheWantedLanguageGetsNoFullSubtitles() {
        // A German dub with German subtitles only: nothing is shown (and so nothing offered: the picker would show the first).
        val dub = JSONObject(episode("201", 1)).apply {
            val streams = getJSONArray("Media").getJSONObject(0).getJSONArray("Part").getJSONObject(0).getJSONArray("Stream")
            streams.getJSONObject(1).put("languageCode", "ger")
            for (i in 2..3) streams.getJSONObject(i).put("languageCode", "fre").remove("default")
            streams.getJSONObject(4).remove("selected")
        }
        val item = client(languages = listOf("de")).entryFor(PlexParser.item(dub, urls())).item
        assertEquals(emptyList<String>(), item.subtitleFiles)
        // None sent by CastApp: the transcoder does as it always did (it may burn in a picture subtitle picked in Plex).
        assertNull(query(item.uri)["subtitles"])
        assertNull(query(item.uri)["skipSubtitles"])
    }

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    // ---- queued episodes ---------------------------------------------------------------------------------------------

    private fun showServer(batch: Boolean): TestServer = TestServer { r ->
        when {
            r.path == "/library/metadata/301" -> TestServer.Resp(body = """{"MediaContainer":{"Metadata":[${episode("301", 1)}]}}""")
            r.path == "/library/metadata/200/allLeaves" ->
                TestServer.Resp(body = """{"MediaContainer":{"Metadata":[${episode("301", 1, false)},${episode("302", 2, false)},${episode("303", 3, false)}]}}""")
            r.path == "/library/metadata/302,303" && batch ->
                TestServer.Resp(body = """{"MediaContainer":{"Metadata":[${episode("303", 3)},${episode("302", 2)}]}}""")
            r.path == "/library/metadata/302,303" -> TestServer.Resp(400, "Bad Request", "text/html")
            r.path == "/library/metadata/302" -> TestServer.Resp(body = """{"MediaContainer":{"Metadata":[${episode("302", 2)}]}}""")
            r.path == "/library/metadata/303" -> TestServer.Resp(body = """{"MediaContainer":{"Metadata":[${episode("303", 3)}]}}""")
            else -> null
        }
    }

    @Test
    fun queuedEpisodesGetTheirSubtitlesInOneRequest() {
        server = showServer(batch = true)
        val c = client(server!!.url)
        val plan = c.plan(ServerItem("301", ItemKind.EPISODE, "Ep 1", seriesId = "200"))
        assertEquals(3, plan.items.size)
        plan.items.forEach { item ->
            assertEquals("ger", ServerSubtitle.parse(item.subtitleUri!!)!!.language)
            assertEquals(4, item.subtitleFiles.size)
        }
        assertTrue(plan.items[2].subtitleUri!!.startsWith("${server!!.url}${part("303")}?"))
        assertEquals(1, server!!.requestsTo("/library/metadata/302,303").size)
        assertTrue(server!!.requestsTo("/library/metadata/302").isEmpty())

        // Played again: the streams of the queued ones are known (by the file's address).
        c.plan(ServerItem("301", ItemKind.EPISODE, "Ep 1", seriesId = "200"))
        assertEquals(1, server!!.requestsTo("/library/metadata/302,303").size)
        assertEquals(2, server!!.requestsTo("/library/metadata/301").size) // the first one is always asked for: its position
    }

    @Test
    fun queuedEpisodesOneByOneWhenTheServerWontTakeSeveral() {
        server = showServer(batch = false)
        val plan = client(server!!.url).plan(ServerItem("301", ItemKind.EPISODE, "Ep 1", seriesId = "200"))
        assertEquals(listOf(true, true, true), plan.items.map { it.subtitleUri != null })
        assertEquals(1, server!!.requestsTo("/library/metadata/302").size)
        assertEquals(1, server!!.requestsTo("/library/metadata/303").size)
    }

    @Test
    fun queuedEpisodesKeepTheirListPositions() {
        server = showServer(batch = true)
        val c = client(server!!.url)
        val listed = listOf(
            PlexParser.item(JSONObject(episode("302", 2, false)).put("viewOffset", 90_000), urls(server!!.url)),
        )
        val withStreams = c.withStreams(listed).single()
        assertEquals(90_000L, withStreams.resumeMs) // the list's position, the details' streams
        assertTrue(withStreams.media!!.streamDetails)
    }
}
