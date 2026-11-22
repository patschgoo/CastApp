package io.github.patschgoo.castapp.iptv

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader
import java.net.InetSocketAddress
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

class XtreamTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val account = XtreamAccount("http://tv.example:8080", "us er", "p&ss/word")

    @Test
    fun recognisesXtreamPlaylistAddresses() {
        val a = XtreamAccount.fromPlaylistUrl("http://tv.example:8080/get.php?username=bob&password=s%26cret&type=m3u_plus&output=ts")
        assertEquals(XtreamAccount("http://tv.example:8080", "bob", "s&cret"), a)
        // A server under a sub-path, https, other parameter order.
        assertEquals(
            XtreamAccount("https://host.example/panel", "u", "p"),
            XtreamAccount.fromPlaylistUrl("https://host.example/panel/get.php?password=p&username=u&type=m3u"),
        )
        assertNull(XtreamAccount.fromPlaylistUrl("http://example.com/playlist.m3u"))
        assertNull(XtreamAccount.fromPlaylistUrl("http://example.com/get.php?type=m3u"))
        assertNull(XtreamAccount.fromPlaylistUrl("content://doc/1"))
        assertNull(XtreamAccount.fromPlaylistUrl("not a url"))
    }

    @Test
    fun buildsStreamAndApiUrls() {
        val simple = XtreamAccount("http://s:80", "bob", "secret")
        assertEquals("http://s:80/movie/bob/secret/123.mkv", simple.movieUrl("123", "mkv"))
        assertEquals("http://s:80/series/bob/secret/987.mp4", simple.episodeUrl("987", ".mp4"))
        assertEquals("http://s:80/movie/bob/secret/5.mp4", simple.movieUrl("5", ""))
        assertEquals("http://s:80/xmltv.php?username=bob&password=secret", simple.xmltvUrl)
        assertEquals(
            "http://s:80/player_api.php?username=bob&password=secret&action=get_series_info&series_id=42",
            simple.apiUrl("get_series_info", "series_id" to "42"),
        )
        assertEquals("http://s:80/player_api.php?username=bob&password=secret", simple.apiUrl())
        // Special characters are escaped.
        assertEquals("http://tv.example:8080/movie/us%20er/p%26ss%2Fword/7.mp4", account.movieUrl("7", "mp4"))
        assertTrue(account.xmltvUrl.endsWith("xmltv.php?username=us+er&password=p%26ss%2Fword"))
    }

    @Test
    fun buildsCatchUpUrls() {
        val simple = XtreamAccount("http://s:80", "bob", "secret")
        // 2026-11-22 19:30 UTC, shown in UTC and in Berlin (+1).
        val start = Xmltv.parseTime("20261122193000 +0000")!!
        assertEquals(
            "http://s:80/timeshift/bob/secret/60/2026-11-22:19-30/456.ts",
            simple.timeshiftUrl("456", start, 60, TimeZone.getTimeZone("UTC")),
        )
        assertEquals(
            "http://s:80/timeshift/bob/secret/45/2026-11-22:20-30/456.ts",
            simple.timeshiftUrl("456", start, 45, TimeZone.getTimeZone("Europe/Berlin")),
        )
        assertEquals("456", simple.liveStreamId("http://s:80/live/bob/secret/456.ts"))
        assertEquals("456", simple.liveStreamId("http://s:80/bob/secret/456"))
        assertNull(simple.liveStreamId("http://other/live/bob/secret/456.ts"))
        assertNull(simple.liveStreamId("http://s:80/live/bob/secret/channel.m3u8"))
    }

    @Test
    fun parsesCategories() {
        val json = """[{"category_id":"12","category_name":"Action","parent_id":0},{"category_id":7,"category_name":"Drama é"},{"category_id":"x"}]"""
        assertEquals(listOf(XtreamCategory("12", "Action"), XtreamCategory("7", "Drama é")), XtreamJson.categories(StringReader(json)))
        assertTrue(XtreamJson.categories(StringReader("[]")).isEmpty())
        assertTrue(XtreamJson.categories(StringReader("""{"user_info":{"auth":0}}""")).isEmpty())
        assertTrue(XtreamJson.categories(StringReader("false")).isEmpty())
    }

    @Test
    fun parsesMovies() {
        val json = """
            [{"num":1,"name":"Big Movie (2020)","stream_type":"movie","stream_id":1001,"stream_icon":"http://img/p1.jpg",
              "rating":"7.5","rating_5based":3.5,"added":"1600000000","category_id":"12","container_extension":"mkv",
              "custom_sid":"","direct_source":"","year":"2020","genre":["a","b"],"extra":{"deep":[1,2,{"x":null}]}},
             {"num":2,"name":"No Cover","stream_id":"1002","stream_icon":"","category_id":null,"container_extension":"","rating":"0"},
             {"name":"No id"}]"""
        val movies = XtreamJson.movies(StringReader(json))
        assertEquals(2, movies.size)
        assertEquals(Movie("1001", "Big Movie (2020)", "http://img/p1.jpg", "12", "mkv", "7.5", "2020"), movies[0])
        assertEquals("1002", movies[1].streamId)
        assertNull(movies[1].cover)
        assertNull(movies[1].categoryId)
        assertNull(movies[1].rating)
        assertEquals("mp4", movies[1].extension)
    }

    @Test
    fun parsesSeriesLists() {
        val json = """[{"series_id":55,"name":"Cool Show","cover":"https://img/c.jpg","plot":"About \"things\"","category_id":"3","releaseDate":"2019-05-01"}]"""
        val s = XtreamJson.series(StringReader(json)).single()
        assertEquals(SeriesShow("55", "Cool Show", "https://img/c.jpg", "3", "About \"things\"", "2019"), s)
    }

    @Test
    fun parsesLiveStreamsWithCatchUp() {
        val json = """[{"stream_id":1,"name":"A","tv_archive":1,"tv_archive_duration":"3"},{"stream_id":2,"name":"B","tv_archive":0},{"stream_id":3,"name":"C"}]"""
        assertEquals(listOf(LiveStream("1", "A", 3)), XtreamJson.liveStreams(StringReader(json)))
    }

    @Test
    fun parsesSeriesInfoBySeasonObject() {
        val json = """
            {"seasons":[{"season_number":1}],
             "info":{"name":"Cool Show","cover":"http://img/c.jpg","plot":"Plot"},
             "episodes":{
               "2":[{"id":"203","episode_num":1,"title":"S2 opener","container_extension":"mp4","season":2,"info":{"duration_secs":2700,"movie_image":"http://img/e.jpg"}}],
               "1":[{"id":"102","episode_num":"2","title":"Second","container_extension":"mkv","season":1,"info":[]},
                    {"id":"101","episode_num":1,"title":"Pilot","container_extension":"mkv","season":"1","info":{"plot":"First","duration_secs":"3000"}}]}}"""
        val info = XtreamJson.seriesInfo(json)
        assertEquals("Cool Show", info.name)
        assertEquals("http://img/c.jpg", info.cover)
        assertEquals(listOf("101", "102", "203"), info.episodes.map { it.id })
        assertEquals("S01E01", info.episodes[0].code)
        assertEquals("S02E01", info.episodes[2].code)
        assertEquals("First", info.episodes[0].plot)
        assertEquals(3000, info.episodes[0].durationSeconds)
        assertEquals("http://img/e.jpg", info.episodes[2].cover)
        assertEquals("mkv", info.episodes[1].extension)
    }

    @Test
    fun parsesSeriesInfoAsListOfSeasons() {
        val json = """{"info":{"name":"Listed"},"episodes":[[{"id":"1","episode_num":1,"title":"A"},{"id":"2","title":""}],[{"id":"3","episode_num":1,"title":"B"}]]}"""
        val info = XtreamJson.seriesInfo(json)
        assertEquals(listOf("S01E01", "S01E02", "S02E01"), info.episodes.map { it.code })
        assertEquals("Episode 2", info.episodes[1].title)
        assertEquals("mp4", info.episodes[0].extension)
    }

    @Test
    fun mapsContainerExtensionsToMimeTypes() {
        assertEquals("video/x-matroska", containerMime("mkv"))
        assertEquals("video/mp4", containerMime(".MP4"))
        assertEquals("video/x-msvideo", containerMime("avi"))
        assertEquals("video/mp4", containerMime("weird"))
    }

    @Test
    fun readsEscapesAndNestedValuesInJson() {
        val v = MiniJson.parse(StringReader("""{"a":"x\ny\t\"q\" \\ \/ A","b":[1,2.5,-3e2,true,false,null,{}],"c":{}}""")) as Map<*, *>
        assertEquals("x\ny\t\"q\" \\ / A", v["a"])
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null, emptyMap<String, Any?>()), v["b"])
    }

    @Test
    fun clientFetchesCachesAndFallsBackToTheOldCopy() {
        val calls = AtomicInteger()
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val seen = ArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/player_api.php") { ex ->
            calls.incrementAndGet()
            synchronized(seen) { seen += ex.requestURI.rawQuery }
            if (failing.get()) {
                ex.sendResponseHeaders(500, -1)
                ex.close()
                return@createContext
            }
            val action = ex.requestURI.query.split('&').first { it.startsWith("action=") }.removePrefix("action=")
            val body = when (action) {
                "get_vod_categories" -> """[{"category_id":"1","category_name":"Action"}]"""
                "get_vod_streams" -> """[{"stream_id":9,"name":"Film","container_extension":"mp4","category_id":"1"}]"""
                "get_series_info" -> """{"info":{"name":"S"},"episodes":{"1":[{"id":"5","episode_num":1,"title":"E","container_extension":"mkv"}]}}"""
                else -> "[]"
            }.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val dir = tmp.newFolder()
            val client = XtreamClient(XtreamAccount(base, "bob", "pw"), dir, "xt_1_")
            assertEquals(listOf(XtreamCategory("1", "Action")), client.vodCategories())
            assertEquals("Film", client.movies().single().name)
            assertTrue(seen.first().startsWith("username=bob&password=pw&action=get_vod_categories"))
            val afterFirst = calls.get()
            // Second read comes from the file on the phone.
            assertEquals("Film", client.movies().single().name)
            assertEquals(afterFirst, calls.get())
            // An old copy is used when the server is down...
            File(dir, "xt_1_get_vod_streams.json").setLastModified(System.currentTimeMillis() - 24 * 3600_000L)
            failing.set(true)
            assertEquals("Film", client.movies().single().name)
            // ...but with no copy at all the error shows.
            try {
                client.series()
                throw AssertionError("expected an error")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("500"))
            }
            // An empty answer is not kept for hours.
            failing.set(false)
            assertTrue(client.series().isEmpty())
            assertTrue(!File(dir, "xt_1_get_series.json").exists())
            // Series details are fetched on demand.
            val info = client.seriesInfo("77")
            assertEquals("E", info.episodes.single().title)
            assertTrue(seen.last().endsWith("action=get_series_info&series_id=77"))
            assertNotNull(client.account)
            client.clearCache()
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            server.stop(0)
        }
    }
}
