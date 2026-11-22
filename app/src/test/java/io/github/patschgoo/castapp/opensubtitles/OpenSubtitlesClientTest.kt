package io.github.patschgoo.castapp.opensubtitles

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections

/** Talks to a local server that answers like api.opensubtitles.com (shapes from the API documentation). */
class OpenSubtitlesClientTest {
    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(ArrayList<Recorded>())
    private var downloadStatus = 200

    data class Recorded(val method: String, val path: String, val query: String?, val headers: Map<String, String?>, val body: String)

    private val base get() = "http://127.0.0.1:${server.address.port}/api/v1"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val headers = listOf("Api-Key", "User-Agent", "Authorization", "Content-Type", "Accept").associateWith { ex.requestHeaders.getFirst(it) }
        requests += Recorded(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery, headers, body)
        // The download link itself is on another host and needs no key.
        if (!ex.requestURI.path.startsWith("/files/") && headers["Api-Key"] != "good-key") return reply(ex, 403, """{"message":"You cannot consume this service","status":403}""")
        val q = ex.requestURI.rawQuery.orEmpty()
        when {
            ex.requestURI.path == "/api/v1/login" -> {
                val creds = JSONObject(body)
                if (creds.optString("password") != "secret") reply(ex, 401, """{"message":"Error, invalid username/password","status":401}""")
                else reply(ex, 200, LOGIN.replace("HOST", "127.0.0.1:${server.address.port}"))
            }
            ex.requestURI.path == "/api/v1/subtitles" && q.contains("moviehash=") -> reply(ex, 200, BY_HASH)
            ex.requestURI.path == "/api/v1/subtitles" && q.contains("query=nothing") -> reply(ex, 200, """{"total_pages":0,"total_count":0,"per_page":50,"page":1,"data":[]}""")
            ex.requestURI.path == "/api/v1/subtitles" -> reply(ex, 200, BY_NAME)
            ex.requestURI.path == "/api/v1/download" && downloadStatus == 406 -> reply(ex, 406, LIMIT)
            ex.requestURI.path == "/api/v1/download" ->
                reply(ex, 200, DOWNLOAD.replace("LINK", "http://127.0.0.1:${server.address.port}/files/the.matrix.srt"))
            ex.requestURI.path == "/files/the.matrix.srt" -> reply(ex, 200, "1\n00:00:01,000 --> 00:00:02,500\nWake up, Neo.\n", "application/x-subrip")
            else -> reply(ex, 404, """{"message":"not found"}""")
        }
    }

    private fun reply(ex: HttpExchange, code: Int, text: String, type: String = "application/json") {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun client(key: String = "good-key") = OpenSubtitlesClient(key, "CastApp v3.1.0", base)

    @Test
    fun searchesByHashAndByNameAndSortsHashMatchesFirst() {
        val results = client().search("8e245d9679d31e12", SearchQuery("the matrix", 1999), listOf("en", "DE"))
        assertEquals(listOf(9001L, 9003L, 9002L), results.map { it.fileId })
        val first = results.first()
        assertTrue(first.hashMatch)
        assertEquals("en", first.language)
        assertEquals("The.Matrix.1999.1080p.BluRay.x264", first.label)
        assertEquals(15321, first.downloads)
        assertEquals("The Matrix", first.title)
        assertTrue(results[2].machineTranslated)
        assertTrue(results[1].hearingImpaired)

        val searches = requests.filter { it.path == "/api/v1/subtitles" }
        assertEquals(2, searches.size)
        // Parameters sorted and lower case, as the API asks.
        assertEquals("languages=de%2Cen&moviehash=8e245d9679d31e12", searches[0].query)
        assertEquals("languages=de%2Cen&query=the+matrix&year=1999", searches[1].query)
        for (r in searches) {
            assertEquals("good-key", r.headers["Api-Key"])
            assertEquals("CastApp v3.1.0", r.headers["User-Agent"])
            assertEquals("application/json", r.headers["Accept"])
            assertNull(r.headers["Authorization"])
        }
    }

    @Test
    fun episodesSendSeasonAndEpisode() {
        client().search(null, SearchQuery("breaking bad", season = 2, episode = 7), listOf("en"))
        assertEquals(listOf("episode_number=7&languages=en&query=breaking+bad&season_number=2"), requests.map { it.query })
    }

    @Test
    fun nothingFound() {
        try {
            client().search(null, SearchQuery("nothing here"), listOf("en"))
            fail()
        } catch (e: OpenSubtitlesClient.Failure) {
            assertEquals(OpenSubtitlesClient.Problem.NOTHING_FOUND, e.problem)
            assertEquals("No subtitles found for this video", e.message)
        }
    }

    @Test
    fun loginThenDownloadWithToken() {
        val c = client()
        val login = c.login("neo", "secret")
        assertEquals("eyJ0eXAiOiJKV1Q", login.token)
        assertEquals(100, login.allowedDownloads)
        // The server named in the login is used from then on.
        assertEquals(base, login.baseUrl)
        assertEquals(base, c.baseUrl)
        assertEquals(JSONObject().put("username", "neo").put("password", "secret").toString(), requests[0].body)
        assertEquals("application/json", requests[0].headers["Content-Type"])

        val link = c.requestDownload(9001, login.token)
        assertEquals(97, link.remaining)
        assertEquals("the.matrix.srt", link.fileName)
        val download = requests.last()
        assertEquals("POST", download.method)
        assertEquals("Bearer eyJ0eXAiOiJKV1Q", download.headers["Authorization"])
        assertEquals(9001L, JSONObject(download.body).getLong("file_id"))

        val text = String(c.fetch(link.url))
        assertTrue(text.contains("Wake up, Neo."))
    }

    @Test
    fun loginOnAnotherServer() {
        val c = OpenSubtitlesClient("good-key", "CastApp v3.1.0", base)
        server.removeContext("/")
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            reply(ex, 200, """{"user":{"allowed_downloads":1000,"vip":true},"base_url":"vip-api.opensubtitles.com","token":"t","status":200}""")
        }
        assertEquals("http://vip-api.opensubtitles.com/api/v1", c.login("neo", "secret").baseUrl)
    }

    @Test
    fun wrongPassword() {
        try {
            client().login("neo", "wrong")
            fail()
        } catch (e: OpenSubtitlesClient.Failure) {
            assertEquals(OpenSubtitlesClient.Problem.LOGIN, e.problem)
            assertEquals("Wrong OpenSubtitles username or password", e.message)
        }
    }

    @Test
    fun dailyLimit() {
        downloadStatus = 406
        try {
            client().requestDownload(9001, null)
            fail()
        } catch (e: OpenSubtitlesClient.Failure) {
            assertEquals(OpenSubtitlesClient.Problem.LIMIT, e.problem)
            assertTrue(e.message!!.startsWith("Daily download limit reached. You have downloaded your allowed 5 subtitles for 24h."))
            assertTrue(e.message!!.contains("renewed in 03 hours"))
        }
        assertNull(requests.last().headers["Authorization"])
    }

    @Test
    fun badKey() {
        try {
            client("wrong-key").search("8e245d9679d31e12", null, listOf("en"))
            fail()
        } catch (e: OpenSubtitlesClient.Failure) {
            assertEquals(OpenSubtitlesClient.Problem.BAD_KEY, e.problem)
        }
    }

    @Test
    fun noKeySendsNothing() {
        try {
            client("").search("8e245d9679d31e12", SearchQuery("the matrix"), listOf("en"))
            fail()
        } catch (e: OpenSubtitlesClient.Failure) {
            assertEquals(OpenSubtitlesClient.Problem.NO_KEY, e.problem)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun offline() {
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            OpenSubtitlesClient("good-key", "CastApp v3.1.0", "http://127.0.0.1:$closedPort/api/v1").search(null, SearchQuery("x"), listOf("en"))
            fail()
        } catch (e: OpenSubtitlesClient.Failure) {
            assertEquals(OpenSubtitlesClient.Problem.OFFLINE, e.problem)
            assertEquals(OpenSubtitlesClient.OFFLINE_MESSAGE, e.message)
        }
    }

    @Test
    fun languageCodes() {
        assertEquals(listOf("de", "en", "pt-br", "pt-pt"), OpenSubtitlesClient.normalizeLanguages(listOf("en, DE ,pt", "en")))
        assertEquals(listOf("zh-cn", "zh-tw"), OpenSubtitlesClient.normalizeLanguages(listOf("zh")))
        assertFalse(OpenSubtitlesClient.normalizeLanguages(listOf(" ")).isNotEmpty())
    }

    companion object {
        private const val LOGIN = """{"user":{"allowed_translations":1,"allowed_downloads":100,"level":"Sub leecher","user_id":66,"ext_installed":false,"vip":false},
            "base_url":"HOST","token":"eyJ0eXAiOiJKV1Q","status":200}"""

        private fun subtitle(id: Int, fileId: Int, lang: String, release: String, downloads: Int, hashMatch: Boolean, hi: Boolean = false, machine: Boolean = false) = """
            {"id":"$id","type":"subtitle","attributes":{"subtitle_id":"$id","language":"$lang","download_count":$downloads,
             "new_download_count":12,"hearing_impaired":$hi,"hd":true,"fps":23.976,"votes":3,"ratings":8.5,"from_trusted":true,
             "foreign_parts_only":false,"upload_date":"2019-07-26T20:51:56Z","ai_translated":false,"machine_translated":$machine,
             "release":"$release","comments":"","legacy_subtitle_id":1953113,
             "uploader":{"uploader_id":47170,"name":"someone","rank":"Trusted member"},
             "feature_details":{"feature_id":13579,"feature_type":"Movie","year":1999,"title":"The Matrix","movie_name":"1999 - The Matrix","imdb_id":133093,"tmdb_id":603},
             "url":"https://www.opensubtitles.com/en/subtitles/legacy/$id","related_links":[],
             "files":[{"file_id":$fileId,"cd_number":1,"file_name":"the.matrix.$lang.srt"}],
             "moviehash_match":$hashMatch}}"""

        private val BY_HASH = """{"total_pages":1,"total_count":1,"per_page":50,"page":1,"data":[
            ${subtitle(1, 9001, "en", "The.Matrix.1999.1080p.BluRay.x264", 15321, true)}]}"""

        private val BY_NAME = """{"total_pages":1,"total_count":3,"per_page":50,"page":1,"data":[
            ${subtitle(1, 9001, "en", "The.Matrix.1999.1080p.BluRay.x264", 15321, false)},
            ${subtitle(2, 9002, "de", "The Matrix DVDRip", 120, false, machine = true)},
            ${subtitle(3, 9003, "de", "The.Matrix.1999.720p", 4100, false, hi = true)}]}"""

        private const val DOWNLOAD = """{"link":"LINK","file_name":"the.matrix.srt","requests":3,"remaining":97,
            "message":"Your quota will be renewed in 07 hours and 30 minutes (2022-04-08 13:03:16 UTC) ",
            "reset_time":"07 hours and 30 minutes","reset_time_utc":"2022-04-08T13:03:16.000Z"}"""

        private const val LIMIT = """{"requests":6,"remaining":-1,
            "message":"You have downloaded your allowed 5 subtitles for 24h.Your quota will be renewed in 03 hours and 10 minutes (2022-04-08 13:03:16 UTC) ",
            "reset_time":"03 hours and 10 minutes","reset_time_utc":"2022-04-08T13:03:16.000Z"}"""
    }
}
