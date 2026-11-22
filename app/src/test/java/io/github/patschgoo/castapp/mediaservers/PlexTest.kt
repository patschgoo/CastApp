package io.github.patschgoo.castapp.mediaservers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plex: plex.tv's list of servers, the media server's JSON, GDM answers and the addresses we build. */
class PlexTest {
    private val info = ClientInfo(deviceId = "dev-1", deviceName = "Pixel 8", version = "4.0.0")
    private var server: TestServer? = null

    @After
    fun stop() {
        server?.close()
    }

    private fun account(base: String = "http://192.168.1.5:32400") =
        ServerAccount("plex-mach1", ServerKind.PLEX, "Living room PC", base, token = "srvtok", serverId = "mach1")

    private fun urls(base: String = "http://192.168.1.5:32400") = PlexUrls(account(base), info, PlaybackCaps.TV)

    private val resourcesJson = """
        [{"name":"Living room PC","product":"Plex Media Server","productVersion":"1.40.2","provides":"server","clientIdentifier":"mach1","owned":true,"accessToken":"srvtok",
          "connections":[
            {"protocol":"https","address":"192.168.1.5","port":32400,"uri":"https://192-168-1-5.abcdef.plex.direct:32400","local":true,"relay":false,"IPv6":false},
            {"protocol":"http","address":"192.168.1.5","port":32400,"uri":"http://192.168.1.5:32400","local":true,"relay":false,"IPv6":false},
            {"protocol":"https","address":"84.1.2.3","port":32400,"uri":"https://84-1-2-3.abcdef.plex.direct:32400","local":false,"relay":false,"IPv6":false},
            {"protocol":"https","address":"172.1.1.1","port":8443,"uri":"https://172-1-1-1.abcdef.plex.direct:8443","local":false,"relay":true,"IPv6":false},
            {"protocol":"https","address":"fe80::1","port":32400,"uri":"https://[fe80--1].abcdef.plex.direct:32400","local":true,"relay":false,"IPv6":true}]},
         {"name":"Plex for Android","product":"Plex for Android","provides":"client,player","clientIdentifier":"phone1","owned":true,"connections":[]}]
    """.trimIndent()

    private val movieJson = """
        {"ratingKey":"101","key":"/library/metadata/101","type":"movie","title":"Big Buck Bunny","year":2008,"summary":"A rabbit.",
         "thumb":"/library/metadata/101/thumb/1700000000","art":"/library/metadata/101/art/1700000000","duration":6300000,"viewOffset":1380000,
         "Media":[{"id":501,"duration":6300000,"bitrate":4000,"width":1920,"height":1080,"audioChannels":2,"audioCodec":"aac","videoCodec":"h264","container":"mp4",
           "Part":[{"id":601,"key":"/library/parts/601/1700000000/file.mp4","duration":6300000,"container":"mp4","Stream":[
             {"id":701,"streamType":1,"codec":"h264","bitDepth":8,"width":1920,"height":1080},
             {"id":702,"streamType":2,"codec":"aac","channels":2,"languageCode":"eng","selected":true},
             {"id":703,"streamType":3,"codec":"srt","languageCode":"ger","key":"/library/streams/703","format":"srt"},
             {"id":704,"streamType":3,"codec":"pgs","languageCode":"eng"}]}]}]}
    """.trimIndent()

    private val episodeJson = """
        {"ratingKey":"201","type":"episode","title":"Pilot","grandparentTitle":"The Show","grandparentRatingKey":"200","parentRatingKey":"210",
         "index":5,"parentIndex":2,"thumb":"/library/metadata/201/thumb/1","duration":2700000,"viewOffset":600000,"viewCount":0,
         "Media":[{"id":511,"duration":2700000,"bitrate":9000,"width":1920,"height":1080,"audioChannels":6,"audioCodec":"eac3","videoCodec":"hevc","container":"mkv",
           "Part":[{"id":611,"key":"/library/parts/611/1/file.mkv","container":"mkv"}]}]}
    """.trimIndent()

    // ---- plex.tv ---------------------------------------------------------------------------------------------

    @Test
    fun readsTheServersOfAnAccountAndLeavesTheOtherDevicesOut() {
        val all = PlexTv.parseResources(JSONArray(resourcesJson))
        assertEquals(2, all.size)
        val servers = all.filter { it.isServer }
        assertEquals(1, servers.size)
        val s = servers.single()
        assertEquals("Living room PC", s.name)
        assertEquals("mach1", s.machineId)
        assertEquals("srvtok", s.accessToken)
        assertTrue(s.owned)
        assertEquals(5, s.connections.size)
    }

    @Test
    fun triesTheHomeNetworkFirstAndTheRelayLast() {
        val s = PlexTv.parseResources(JSONArray(resourcesJson)).first()
        assertEquals(
            listOf(
                "http://192.168.1.5:32400",
                "https://192-168-1-5.abcdef.plex.direct:32400",
                "https://84-1-2-3.abcdef.plex.direct:32400",
                "https://172-1-1-1.abcdef.plex.direct:8443",
            ),
            s.addressesToTry(), // no IPv6, relay last
        )
        // The address the Wi-Fi broadcast found goes in front.
        assertEquals("http://192.168.1.77:32400", s.addressesToTry(listOf("192.168.1.77:32400")).first())
    }

    @Test
    fun plexTvRequestsCarryTheClientIdentity() {
        server = TestServer { r ->
            when (r.path) {
                "/api/v2/resources" -> TestServer.Resp(body = resourcesJson)
                "/api/v2/user" -> TestServer.Resp(body = """{"id":1,"username":"anna","title":"anna"}""")
                else -> null
            }
        }
        val tv = PlexTv(info, server!!.url)
        assertEquals("anna", tv.username("acctok"))
        val servers = tv.resources("acctok")
        assertEquals(listOf("mach1"), servers.map { it.machineId })
        val req = server!!.requestsTo("/api/v2/resources").single()
        assertEquals("acctok", req.header("X-Plex-Token"))
        assertEquals("dev-1", req.header("X-Plex-Client-Identifier"))
        assertEquals("CastApp", req.header("X-Plex-Product"))
        assertEquals("application/json", req.header("Accept"))
        assertEquals("1", req.param("includeRelay"))
    }

    // ---- finding servers (GDM) ------------------------------------------------------------------------------------

    @Test
    fun readsTheAnswerOfTheGdmBroadcast() {
        val reply = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nResource-Identifier: mach1\r\nName: Living room PC\r\nPort: 32400\r\nUpdated-At: 1700000000\r\nVersion: 1.40.2.8395\r\n\r\n"
        val s = LanDiscovery.parseGdmReply(reply, "192.168.1.5")!!
        assertEquals(GdmServer("Living room PC", "mach1", "192.168.1.5", 32400), s)
        assertNull(LanDiscovery.parseGdmReply("HTTP/1.0 200 OK\r\nName: something else\r\n\r\n", "1.2.3.4"))
        assertEquals("M-SEARCH * HTTP/1.1\r\n\r\n", LanDiscovery.GDM_QUERY)
        assertEquals(32414, LanDiscovery.GDM_PORT)
    }

    // ---- the media server's JSON -------------------------------------------------------------------------------------

    @Test
    fun parsesAMovie() {
        val item = PlexParser.item(JSONObject(movieJson), urls())
        assertEquals("101", item.id)
        assertEquals(ItemKind.MOVIE, item.kind)
        assertEquals(2008, item.year)
        assertEquals(6_300_000L, item.runtimeMs)
        assertEquals(1_380_000L, item.resumeMs)
        assertEquals(
            "http://192.168.1.5:32400/photo/:/transcode?width=320&height=480&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F101%2Fthumb%2F1700000000&X-Plex-Token=srvtok",
            item.posterUrl,
        )
        val media = item.media!!
        assertEquals("501", media.sourceId)
        assertEquals("/library/parts/601/1700000000/file.mp4", media.partKey)
        assertEquals("mp4", media.container)
        assertEquals(4_000_000L, media.bitrate) // Plex says kilobits
        assertEquals("eng", media.audioLanguage)
        assertEquals(2, media.subtitles.size)
        assertEquals("/library/streams/703", media.subtitles[0].key)
    }

    @Test
    fun parsesAnEpisodeAndItsShow() {
        val e = PlexParser.item(JSONObject(episodeJson), urls())
        assertEquals(ItemKind.EPISODE, e.kind)
        assertEquals("The Show", e.seriesTitle)
        assertEquals("200", e.seriesId)
        assertEquals("S2 E5", e.episodeLabel)
        assertEquals(600_000L, e.resumeMs)
        assertEquals("The Show · S2 E5 · Pilot", displayTitle(e))
        assertTrue(e.wide)

        val show = PlexParser.item(JSONObject("""{"ratingKey":"200","type":"show","title":"The Show","year":2019,"leafCount":20,"viewedLeafCount":15,"childCount":3}"""), urls())
        assertEquals(ItemKind.SERIES, show.kind)
        assertEquals(5, show.unplayedCount)
        assertFalse(show.played)
        val season = PlexParser.item(JSONObject("""{"ratingKey":"210","type":"season","title":"Season 2","index":2,"leafCount":10,"viewedLeafCount":10}"""), urls())
        assertEquals(2, season.seasonNumber)
        assertTrue(season.played)
    }

    // ---- addresses -----------------------------------------------------------------------------------------------------

    @Test
    fun directPlayAddressCarriesTheToken() {
        assertEquals("http://192.168.1.5:32400/library/parts/601/1700000000/file.mp4?X-Plex-Token=srvtok", urls().direct("/library/parts/601/1700000000/file.mp4"))
    }

    @Test
    fun universalTranscoderAddress() {
        val url = urls().hls("201")
        assertTrue(url.startsWith("http://192.168.1.5:32400/video/:/transcode/universal/start.m3u8?"))
        val q = url.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("/library/metadata/201", q["path"])
        assertEquals("hls", q["protocol"])
        assertEquals("0", q["directPlay"])
        assertEquals("1", q["directStream"])
        assertEquals("15000", q["maxVideoBitrate"])
        assertEquals("1920x1080", q["videoResolution"])
        assertEquals("dev-1", q["X-Plex-Client-Identifier"])
        assertEquals("Chromecast", q["X-Plex-Platform"])
        assertEquals("srvtok", q["X-Plex-Token"])
        assertEquals(q["session"], urls().hls("201").substringAfter("session=").substringBefore('&'))
    }

    @Test
    fun entryUsesDirectPlayOrTheTranscoder() {
        val client = PlexClient(account(), info, deviceLanguage = "de")
        val direct = client.entryFor(PlexParser.item(JSONObject(movieJson), urls()))
        assertEquals("http://192.168.1.5:32400/library/parts/601/1700000000/file.mp4?X-Plex-Token=srvtok", direct.item.uri)
        assertEquals("video/mp4", direct.item.mimeType)
        assertEquals("http://192.168.1.5:32400/library/streams/703?X-Plex-Token=srvtok", direct.item.subtitleUri)
        assertEquals(1_380_000L, direct.resumeMs)
        val ref = ItemRef.decode(direct.item.serverRef)!!
        assertEquals(ServerKind.PLEX, ref.kind)
        assertEquals("101", ref.itemId)
        assertEquals(PlayMethod.DIRECT, ref.method)

        val hls = client.entryFor(PlexParser.item(JSONObject(episodeJson), urls()))
        assertTrue(hls.item.isHls)
        assertTrue(hls.item.uri.contains("/video/:/transcode/universal/start.m3u8"))
        assertEquals(PlayMethod.TRANSCODE, ItemRef.decode(hls.item.serverRef)!!.method)
    }

    // ---- talking to a server --------------------------------------------------------------------------------------------

    @Test
    fun readsLibrariesAndOnDeck() {
        server = TestServer { r ->
            when (r.path) {
                "/library/sections" -> TestServer.Resp(
                    body = """{"MediaContainer":{"size":2,"Directory":[{"key":"1","type":"movie","title":"Movies"},{"key":"2","type":"show","title":"TV Shows"}]}}""",
                )
                "/library/onDeck" -> TestServer.Resp(body = """{"MediaContainer":{"size":2,"Metadata":[$episodeJson,
                    {"ratingKey":"202","type":"episode","title":"Next one","grandparentTitle":"Other Show","grandparentRatingKey":"300","index":1,"parentIndex":1}]}}""")
                "/library/sections/1/all" -> TestServer.Resp(body = """{"MediaContainer":{"Metadata":[$movieJson]}}""")
                "/library/metadata/200/children" -> TestServer.Resp(body = """{"MediaContainer":{"Metadata":[{"ratingKey":"210","type":"season","title":"Season 2","index":2}]}}""")
                else -> null
            }
        }
        val client = PlexClient(account(server!!.url), info)
        val libs = client.libraries()
        assertEquals(listOf("Movies", "TV Shows"), libs.map { it.title })
        assertEquals("show", libs[1].collectionType)
        assertEquals("201", client.continueWatching().single().id) // has a position
        assertEquals("202", client.nextUp().single().id) // not started
        assertEquals("101", client.children(libs[0]).single().id)
        assertEquals("210", client.children(ServerItem("200", ItemKind.SERIES, "The Show")).single().id)
        val req = server!!.requestsTo("/library/sections").single()
        assertEquals("srvtok", req.header("X-Plex-Token"))
        assertEquals("application/json", req.header("Accept"))
    }

    @Test
    fun queuesTheFollowingEpisodes() {
        fun ep(id: String, n: Int) = """{"ratingKey":"$id","type":"episode","title":"Ep $n","grandparentTitle":"The Show","grandparentRatingKey":"200","index":$n,"parentIndex":1,
            "Media":[{"id":1$id,"container":"mp4","videoCodec":"h264","audioCodec":"aac","width":1280,"height":720,"Part":[{"key":"/library/parts/$id/1/f.mp4"}]}]}"""
        server = TestServer { r ->
            when (r.path) {
                "/library/metadata/301" -> TestServer.Resp(body = """{"MediaContainer":{"Metadata":[${ep("301", 1)}]}}""")
                "/library/metadata/200/allLeaves" -> TestServer.Resp(body = """{"MediaContainer":{"Metadata":[${ep("301", 1)},${ep("302", 2)},${ep("303", 3)}]}}""")
                else -> null
            }
        }
        val client = PlexClient(account(server!!.url), info)
        val plan = client.plan(ServerItem("301", ItemKind.EPISODE, "Ep 1", seriesId = "200"))
        assertEquals(listOf("The Show · S1 E1 · Ep 1", "The Show · S1 E2 · Ep 2", "The Show · S1 E3 · Ep 3"), plan.items.map { it.title })
        assertTrue(plan.items[2].uri.contains("/library/parts/303/1/f.mp4"))
    }

    @Test
    fun marksWatched() {
        server = TestServer { TestServer.Resp(body = "") }
        val client = PlexClient(account(server!!.url), info)
        client.setWatched(ServerItem("101", ItemKind.MOVIE, "x"), true)
        client.setWatched(ServerItem("101", ItemKind.MOVIE, "x"), false)
        assertEquals("101", server!!.requestsTo("/:/scrobble").single().param("key"))
        assertEquals("com.plexapp.plugins.library", server!!.requestsTo("/:/unscrobble").single().param("identifier"))
    }

    @Test
    fun checksWhetherAServerAnswers() {
        server = TestServer { r -> if (r.path == "/identity") TestServer.Resp(body = """{"MediaContainer":{"size":0,"machineIdentifier":"mach1","version":"1.40"}}""") else null }
        assertEquals("mach1", PlexClient.identify(server!!.url))
        assertNull(PlexClient.identify("http://127.0.0.1:1", timeoutMs = 500))
    }
}
