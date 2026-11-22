package io.github.patschgoo.castapp.mediaservers

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Jellyfin: JSON as the server sends it, the addresses and headers we build, sign-in and the play list. */
class JellyfinTest {
    private val info = ClientInfo(deviceId = "dev-1", deviceName = "Pixel 8", version = "4.0.0")
    private var server: TestServer? = null

    @After
    fun stop() {
        server?.close()
    }

    private fun account(base: String = "http://jf.local:8096", kind: ServerKind = ServerKind.JELLYFIN) =
        ServerAccount("acc1", kind, "Home server", base, token = "tok123", userId = "user1", userName = "anna", serverId = "srvid")

    private fun urls(a: ServerAccount = account()) = JellyfinUrls(a, info, PlaybackCaps.TV)

    // ---- parsing ---------------------------------------------------------------------------------------

    private val movieJson = """
        {"Name":"Big Buck Bunny","ServerId":"srvid","Id":"m1","Type":"Movie","ProductionYear":2008,
         "Overview":"A giant rabbit.","RunTimeTicks":63000000000,
         "ImageTags":{"Primary":"imgtag1"},"BackdropImageTags":["bdtag1"],
         "UserData":{"PlaybackPositionTicks":13800000000,"PlayCount":0,"Played":false},
         "MediaSources":[{"Id":"src1","Container":"mov,mp4,m4a,3gp,3g2,mj2","Bitrate":4000000,"MediaStreams":[
            {"Type":"Video","Codec":"h264","Width":1920,"Height":1080,"BitDepth":8,"Index":0,"IsDefault":true},
            {"Type":"Audio","Codec":"aac","Channels":2,"Language":"eng","Index":1,"IsDefault":true},
            {"Type":"Subtitle","Codec":"subrip","Language":"ger","Index":2,"IsExternal":true,"IsDefault":false,"IsForced":false,"DisplayTitle":"German"},
            {"Type":"Subtitle","Codec":"pgssub","Language":"eng","Index":3,"IsExternal":false,"IsDefault":false,"IsForced":false}
         ]}]}
    """.trimIndent()

    private val mkvJson = """
        {"Name":"Sintel","Id":"m2","Type":"Movie","ProductionYear":2010,"RunTimeTicks":36000000000,
         "UserData":{"PlaybackPositionTicks":0,"Played":true},
         "MediaSources":[{"Id":"src2","Container":"matroska,webm","Bitrate":18000000,"MediaStreams":[
            {"Type":"Video","Codec":"hevc","Width":3840,"Height":2160,"BitDepth":10,"Index":0,"IsDefault":true},
            {"Type":"Audio","Codec":"dts","Channels":6,"Language":"eng","Index":1,"IsDefault":true}]}]}
    """.trimIndent()

    @Test
    fun parsesAMovieWithStreamsAndResumePosition() {
        val item = JellyfinParser.item(JSONObject(movieJson), urls())
        assertEquals(ItemKind.MOVIE, item.kind)
        assertEquals("Big Buck Bunny", item.title)
        assertEquals(2008, item.year)
        assertEquals(6_300_000L, item.runtimeMs)
        assertEquals(1_380_000L, item.resumeMs) // 23:00
        assertFalse(item.played)
        assertEquals("http://jf.local:8096/Items/m1/Images/Primary?maxHeight=480&quality=90&tag=imgtag1&api_key=tok123", item.posterUrl)
        assertTrue(item.backdropUrl!!.contains("/Items/m1/Images/Backdrop/0?"))
        val media = item.media!!
        assertEquals("src1", media.sourceId)
        assertEquals("h264", media.videoCodec)
        assertEquals("aac", media.audioCodec)
        assertEquals(2, media.audioChannels)
        assertEquals(2, media.subtitles.size)
        assertTrue(media.subtitles[0].isText)
        assertFalse(media.subtitles[1].isText) // picture subtitles can't be sent
    }

    @Test
    fun parsesEpisodesSeasonsAndSeries() {
        val episode = JellyfinParser.item(
            JSONObject(
                """{"Name":"Pilot","Id":"e1","Type":"Episode","SeriesName":"The Show","SeriesId":"s1","SeasonId":"se1","ParentId":"se1",
                    "ParentIndexNumber":2,"IndexNumber":5,"RunTimeTicks":27000000000,"SeriesPrimaryImageTag":"sertag",
                    "UserData":{"PlaybackPositionTicks":0,"Played":true}}""",
            ),
            urls(),
        )
        assertEquals(ItemKind.EPISODE, episode.kind)
        assertEquals(2, episode.seasonNumber)
        assertEquals(5, episode.episodeNumber)
        assertEquals("S2 E5", episode.episodeLabel)
        assertEquals("s1", episode.seriesId)
        assertTrue(episode.played)
        assertTrue(episode.wide)
        // No picture of its own: the show's poster.
        assertTrue(episode.posterUrl!!.contains("/Items/s1/Images/Primary"))
        assertEquals("The Show · S2 E5 · Pilot", displayTitle(episode))

        val season = JellyfinParser.item(JSONObject("""{"Name":"Season 3","Id":"se3","Type":"Season","IndexNumber":3,"ChildCount":8,"UserData":{"UnplayedItemCount":4}}"""), urls())
        assertEquals(ItemKind.SEASON, season.kind)
        assertEquals(3, season.seasonNumber)
        assertEquals(4, season.unplayedCount)
        assertEquals(8, season.childCount)

        val series = JellyfinParser.item(JSONObject("""{"Name":"The Show","Id":"s1","Type":"Series","ProductionYear":2019}"""), urls())
        assertEquals(ItemKind.SERIES, series.kind)
        assertEquals("2019", series.subtitle)
        assertFalse(series.isPlayable)
    }

    // ---- addresses and headers -------------------------------------------------------------------------------

    @Test
    fun buildsTheAuthorizationHeaders() {
        val value = JellyfinAuth.authorizationValue(info, "tok123")
        assertEquals("""MediaBrowser Client="CastApp", Device="Pixel 8", DeviceId="dev-1", Version="4.0.0", Token="tok123"""", value)
        // Without a token (the sign-in itself) there is no Token field.
        assertFalse(JellyfinAuth.authorizationValue(info, null).contains("Token"))
        val jf = JellyfinAuth.headers(ServerKind.JELLYFIN, info, "tok123")
        assertEquals(value, jf["X-Emby-Authorization"])
        assertEquals(value, jf["Authorization"])
        val emby = JellyfinAuth.headers(ServerKind.EMBY, info, "tok123")
        assertEquals("tok123", emby["X-Emby-Token"])
        assertNull(emby["Authorization"])
        // Quotes in a device name can't break the header.
        assertTrue(JellyfinAuth.authorizationValue(info.copy(deviceName = "My \"phone\""), null).contains("""Device="My phone""""))
    }

    @Test
    fun directStreamAddress() {
        val u = urls()
        assertEquals(
            "http://jf.local:8096/Videos/m1/stream.mp4?Static=true&MediaSourceId=src1&DeviceId=dev-1&api_key=tok123",
            u.directVideo("m1", "src1", MediaInfo("src1", "mov,mp4,m4a,3gp,3g2,mj2", "h264", "aac", 1920, 1080, 8, 2, null)),
        )
        // The same address every time: the watch history finds the file again.
        assertEquals(u.directVideo("m1", "src1", null), u.directVideo("m1", "src1", null))
    }

    @Test
    fun hlsAddressHasLimitsForChromecast() {
        val url = urls().hls("m2", "src2")
        assertTrue(url.startsWith("http://jf.local:8096/Videos/m2/master.m3u8?"))
        val q = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("src2", q["MediaSourceId"])
        assertEquals("dev-1", q["DeviceId"])
        assertEquals("h264", q["VideoCodec"])
        assertEquals("aac,mp3".replace(",", "%2C"), q["AudioCodec"])
        assertEquals("15000000", q["VideoBitrate"])
        assertEquals("2", q["MaxAudioChannels"])
        assertEquals("1920", q["MaxWidth"])
        assertEquals("ts", q["SegmentContainer"])
        assertEquals("tok123", q["api_key"])
        // The session id is stable (so the stop report reaches the same transcode).
        assertEquals(q["PlaySessionId"], urls().hls("m2", "src2").substringAfter("PlaySessionId=").substringBefore('&'))
        assertEquals(32, q["PlaySessionId"]!!.length)
    }

    @Test
    fun subtitleAddressAsWebVtt() {
        assertEquals("http://jf.local:8096/Videos/m1/src1/Subtitles/2/0/Stream.vtt?api_key=tok123", urls().subtitle("m1", "src1", 2))
    }

    @Test
    fun normalizesWhatTheUserTypes() {
        assertEquals("http://192.168.1.10:8096", JellyfinAuth.normalizeAddress("192.168.1.10"))
        assertEquals("http://192.168.1.10:8096", JellyfinAuth.normalizeAddress("  192.168.1.10/ "))
        assertEquals("http://192.168.1.10:8920", JellyfinAuth.normalizeAddress("192.168.1.10:8920"))
        assertEquals("http://nas:8096", JellyfinAuth.normalizeAddress("nas"))
        assertEquals("https://jellyfin.example.com", JellyfinAuth.normalizeAddress("jellyfin.example.com"))
        assertEquals("https://example.com/jf", JellyfinAuth.normalizeAddress("https://example.com/jf/"))
        assertEquals("http://nas:8096", JellyfinAuth.normalizeAddress("http://nas:8096/web/index.html#!/home.html"))
        assertEquals("", JellyfinAuth.normalizeAddress("  "))
        assertTrue(JellyfinAuth.isPrivateHost("http://192.168.0.4:8096"))
        assertTrue(JellyfinAuth.isPrivateHost("http://nas.local"))
        assertFalse(JellyfinAuth.isPrivateHost("http://jellyfin.example.com"))
    }

    // ---- deciding between direct stream and conversion ---------------------------------------------------------

    @Test
    fun playsWhatTheTvCanPlayAsItIs() {
        val media = JellyfinParser.item(JSONObject(movieJson), urls()).media
        assertEquals(PlayMethod.DIRECT, PlaybackPlanner.decideVideo(media))
        val hevc10 = JellyfinParser.item(JSONObject(mkvJson), urls()).media
        assertEquals(PlayMethod.TRANSCODE, PlaybackPlanner.decideVideo(hevc10))
        // One reason is enough: unsupported sound, a Matroska box, too big a picture, too high a bit rate.
        val ok = media!!
        assertEquals(PlayMethod.TRANSCODE, PlaybackPlanner.decideVideo(ok.copy(audioCodec = "dts")))
        assertEquals(PlayMethod.TRANSCODE, PlaybackPlanner.decideVideo(ok.copy(container = "matroska,webm"))) // an MKV with H.264
        assertEquals(PlayMethod.DIRECT, PlaybackPlanner.decideVideo(ok.copy(container = "matroska,webm", videoCodec = "vp9", audioCodec = "opus"))) // a WebM
        assertEquals(PlayMethod.DIRECT, PlaybackPlanner.decideVideo(ok.copy(container = "webm", videoCodec = "vp9", audioCodec = "opus")))
        assertEquals(PlayMethod.TRANSCODE, PlaybackPlanner.decideVideo(ok.copy(width = 3840, height = 2160)))
        assertEquals(PlayMethod.TRANSCODE, PlaybackPlanner.decideVideo(ok.copy(bitrate = 40_000_000)))
        assertEquals(PlayMethod.TRANSCODE, PlaybackPlanner.decideVideo(ok.copy(bitDepth = 10)))
        assertEquals(PlayMethod.DIRECT, PlaybackPlanner.decideVideo(null))
        assertEquals(PlayMethod.DIRECT, PlaybackPlanner.decideAudio(ok.copy(audioCodec = "flac")))
        assertEquals(PlayMethod.TRANSCODE, PlaybackPlanner.decideAudio(ok.copy(audioCodec = "wmav2")))
    }

    @Test
    fun entryUsesDirectStreamOrHls() {
        val client = JellyfinClient(account(), info, deviceLanguage = "de")
        val direct = client.entryFor(JellyfinParser.item(JSONObject(movieJson), urls()))
        assertTrue(direct.item.uri.contains("/Videos/m1/stream.mp4?Static=true"))
        assertEquals("video/mp4", direct.item.mimeType)
        assertEquals(1_380_000L, direct.resumeMs)
        assertEquals(6_300_000L, direct.durationMs)
        // The sound is English, the phone German: the German text subtitle goes along, as WebVTT.
        assertEquals("http://jf.local:8096/Videos/m1/src1/Subtitles/2/0/Stream.vtt?api_key=tok123", direct.item.subtitleUri)
        val ref = ItemRef.decode(direct.item.serverRef)!!
        assertEquals("m1", ref.itemId)
        assertEquals("src1", ref.sourceId)
        assertEquals(PlayMethod.DIRECT, ref.method)
        assertEquals("acc1", ref.accountId)

        val hls = client.entryFor(JellyfinParser.item(JSONObject(mkvJson), urls()))
        assertTrue(hls.item.uri.contains("/Videos/m2/master.m3u8?"))
        assertTrue(hls.item.isHls)
        assertEquals(PlayMethod.TRANSCODE, ItemRef.decode(hls.item.serverRef)!!.method)
    }

    // ---- talking to a server ----------------------------------------------------------------------------------

    @Test
    fun signsInWithUserNameAndPassword() {
        server = TestServer { r ->
            when (r.path) {
                "/System/Info/Public" -> TestServer.Resp(body = """{"LocalAddress":"http://x","ServerName":"Home server","Version":"10.9.11","ProductName":"Jellyfin Server","Id":"srvid"}""")
                "/Users/AuthenticateByName" ->
                    if (JSONObject(r.body).optString("Pw") == "secret") {
                        TestServer.Resp(body = """{"User":{"Name":"anna","Id":"user1"},"AccessToken":"tok-xyz","ServerId":"srvid"}""")
                    } else TestServer.Resp(401, "")
                else -> null
            }
        }
        val account = JellyfinAuth.authenticate(server!!.url, "anna", "secret", info, accountId = "abc")
        assertEquals(ServerKind.JELLYFIN, account.kind)
        assertEquals("Home server", account.name)
        assertEquals("tok-xyz", account.token)
        assertEquals("user1", account.userId)
        assertEquals("srvid", account.serverId)
        val login = server!!.requestsTo("/Users/AuthenticateByName").single()
        assertEquals("POST", login.method)
        assertEquals("anna", JSONObject(login.body).getString("Username"))
        assertEquals(JellyfinAuth.authorizationValue(info, null), login.header("X-Emby-Authorization"))

        try {
            JellyfinAuth.authenticate(server!!.url, "anna", "wrong", info, accountId = "abc")
            fail("wrong password")
        } catch (e: ServerHttpException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun tellsEmbyFromJellyfin() {
        server = TestServer { TestServer.Resp(body = """{"ServerName":"Emby box","Version":"4.8.3.0","Id":"e1"}""") }
        assertEquals(ServerKind.EMBY, JellyfinAuth.publicInfo(server!!.url).kind)
    }

    @Test
    fun readsLibrariesContinueWatchingAndNextUp() {
        server = TestServer { r ->
            when (r.path) {
                "/Users/user1/Views" -> TestServer.Resp(
                    body = """{"Items":[{"Name":"Movies","Id":"lib1","Type":"CollectionFolder","CollectionType":"movies"},
                        {"Name":"Shows","Id":"lib2","Type":"CollectionFolder","CollectionType":"tvshows"}]}""",
                )
                "/Users/user1/Items/Resume" -> TestServer.Resp(body = """{"Items":[$movieJson]}""")
                "/Shows/NextUp" -> TestServer.Resp(
                    body = """{"Items":[{"Name":"Ep 2","Id":"e2","Type":"Episode","SeriesName":"The Show","SeriesId":"s1","ParentIndexNumber":1,"IndexNumber":2}]}""",
                )
                else -> null
            }
        }
        val client = JellyfinClient(account(server!!.url), info)
        val libs = client.libraries()
        assertEquals(listOf("Movies", "Shows"), libs.map { it.title })
        assertTrue(libs.all { it.kind == ItemKind.LIBRARY })
        assertEquals("tvshows", libs[1].collectionType)
        assertEquals(1_380_000L, client.continueWatching().single().resumeMs)
        val next = client.nextUp().single()
        assertEquals("e2", next.id)
        assertEquals("user1", server!!.requestsTo("/Shows/NextUp").single().param("UserId"))
        // Requests carry the token.
        assertTrue(server!!.requestsTo("/Users/user1/Views").single().header("X-Emby-Authorization")!!.contains("Token=\"tok123\""))
        assertEquals("24", server!!.requestsTo("/Users/user1/Items/Resume").single().param("Limit"))
    }

    @Test
    fun browsesAFolderWithItsParentId() {
        server = TestServer { r ->
            if (r.path == "/Users/user1/Items") TestServer.Resp(body = """{"Items":[{"Name":"Season 1","Id":"se1","Type":"Season","IndexNumber":1}],"TotalRecordCount":1}""") else null
        }
        val client = JellyfinClient(account(server!!.url), info)
        val seasons = client.children(ServerItem("s1", ItemKind.SERIES, "The Show"))
        assertEquals("se1", seasons.single().id)
        val req = server!!.requestsTo("/Users/user1/Items").single()
        assertEquals("s1", req.param("ParentId"))
        assertEquals("SortName", req.param("SortBy"))
    }

    @Test
    fun queuesTheFollowingEpisodes() {
        fun ep(id: String, n: Int) =
            """{"Name":"Ep $n","Id":"$id","Type":"Episode","SeriesName":"The Show","SeriesId":"s1","ParentIndexNumber":1,"IndexNumber":$n,
                "RunTimeTicks":1000000000,"MediaSources":[{"Id":"src-$id","Container":"mp4","MediaStreams":[
                {"Type":"Video","Codec":"h264","Width":1280,"Height":720,"Index":0},{"Type":"Audio","Codec":"aac","Channels":2,"Index":1}]}]}"""
        server = TestServer { r ->
            when (r.path) {
                "/Users/user1/Items/e1" -> TestServer.Resp(body = ep("e1", 1).replace("\"IndexNumber\":1,", "\"IndexNumber\":1,\"UserData\":{\"PlaybackPositionTicks\":6000000000},"))
                "/Shows/s1/Episodes" -> TestServer.Resp(body = """{"Items":[${ep("e1", 1)},${ep("e2", 2)},${ep("e3", 3)}]}""")
                else -> null
            }
        }
        val client = JellyfinClient(account(server!!.url), info)
        val plan = client.plan(ServerItem("e1", ItemKind.EPISODE, "Ep 1", seriesId = "s1"))
        assertEquals(3, plan.entries.size)
        assertEquals(listOf("The Show · S1 E1 · Ep 1", "The Show · S1 E2 · Ep 2", "The Show · S1 E3 · Ep 3"), plan.items.map { it.title })
        assertEquals(600_000L, plan.resumeMs) // 10:00 from the server
        assertEquals("e1", server!!.requestsTo("/Shows/s1/Episodes").single().param("StartItemId"))
        assertTrue(plan.items[1].uri.contains("/Videos/e2/stream.mp4"))
        assertTrue(plan.items.all { it.serverRef != null })

        // Queueing off: only the episode itself.
        assertEquals(1, client.plan(ServerItem("e1", ItemKind.EPISODE, "Ep 1", seriesId = "s1"), queueFollowing = false).entries.size)
    }

    @Test
    fun marksWatchedAndUnwatched() {
        server = TestServer { TestServer.Resp(body = """{"PlayCount":1,"Played":true}""") }
        val client = JellyfinClient(account(server!!.url), info)
        client.setWatched(ServerItem("m1", ItemKind.MOVIE, "x"), true)
        client.setWatched(ServerItem("m1", ItemKind.MOVIE, "x"), false)
        val calls = server!!.requestsTo("/Users/user1/PlayedItems/m1")
        assertEquals(listOf("POST", "DELETE"), calls.map { it.method })
    }

    // ---- finding servers ------------------------------------------------------------------------------------------

    @Test
    fun readsTheAnswerOfTheUdpBroadcast() {
        val found = LanDiscovery.parseJellyfinReply("""{"Address":"http://192.168.1.2:8096","Id":"abc123","Name":"Living room","EndpointAddress":null}""", ServerKind.JELLYFIN)!!
        assertEquals("Living room", found.name)
        assertEquals("http://192.168.1.2:8096", found.address)
        assertEquals("abc123", found.id)
        assertNull(LanDiscovery.parseJellyfinReply("hello", ServerKind.JELLYFIN))
        assertNotNull(LanDiscovery.parseJellyfinReply("""{"Address":"http://nas:8096/"}""", ServerKind.EMBY))
        assertEquals("who is JellyfinServer?", LanDiscovery.JELLYFIN_QUERY)
        assertEquals(7359, LanDiscovery.JELLYFIN_PORT)
    }
}
