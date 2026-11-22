package io.github.patschgoo.castapp.mediaservers

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The plex.tv PIN sign-in ("Go to plex.tv/link and enter ABCD"): creating the code and polling for the token. */
class PlexPinTest {
    private val info = ClientInfo(deviceId = "dev-1", deviceName = "Pixel 8", version = "4.0.0")
    private var server: TestServer? = null

    @After
    fun stop() {
        server?.close()
    }

    /** plex.tv as it answers: the PIN stays unclaimed for [claimAfter] polls. */
    private fun plexTv(claimAfter: Int, polls: AtomicInteger = AtomicInteger(), status: (Int) -> Int = { 200 }): PlexTv {
        server = TestServer { r ->
            when {
                r.method == "POST" && r.path == "/api/v2/pins" ->
                    TestServer.Resp(201, """{"id":123456,"code":"ABCD","product":"CastApp","trusted":false,"expiresIn":900,"authToken":null}""")
                r.method == "GET" && r.path == "/api/v2/pins/123456" -> {
                    val n = polls.incrementAndGet()
                    val code = status(n)
                    when {
                        code != 200 -> TestServer.Resp(code, """{"errors":[{"code":$code,"message":"x"}]}""")
                        n > claimAfter -> TestServer.Resp(body = """{"id":123456,"code":"ABCD","authToken":"plextoken-xyz"}""")
                        else -> TestServer.Resp(body = """{"id":123456,"code":"ABCD","authToken":null}""")
                    }
                }
                else -> null
            }
        }
        return PlexTv(info, server!!.url)
    }

    /** A clock that only moves when the flow "sleeps", so no test waits for real. */
    private class FakeTime {
        var now = 0L
        val sleeps = ArrayList<Long>()
        val sleep: suspend (Long) -> Unit = { ms -> sleeps += ms; now += ms }
    }

    @Test
    fun createsACodeForPlexTvLink() {
        val tv = plexTv(claimAfter = 0)
        val pin = tv.createPin()
        assertEquals("ABCD", pin.code)
        assertEquals(123456L, pin.id)
        assertEquals(900, pin.expiresInSeconds)
        val req = server!!.requestsTo("/api/v2/pins").single()
        assertEquals("false", req.param("strong")) // the short code, not the long one
        assertEquals("dev-1", req.header("X-Plex-Client-Identifier"))
        assertEquals("CastApp", req.header("X-Plex-Product"))
        assertEquals("Pixel 8", req.header("X-Plex-Device-Name"))
        assertEquals("application/json", req.header("Accept"))
        assertNull(req.header("X-Plex-Token"))
    }

    @Test
    fun pollsUntilTheCodeWasEntered() = runBlocking {
        val tv = plexTv(claimAfter = 3)
        val time = FakeTime()
        val flow = PlexPinFlow(tv, intervalMs = 1500, sleep = time.sleep, clock = { time.now })
        var waits = 0
        val result = flow.waitForToken(tv.createPin(), onWaiting = { waits++ })
        assertEquals("plextoken-xyz", (result as PinResult.Token).token)
        assertEquals(3, waits)
        assertEquals(listOf(1500L, 1500L, 1500L), time.sleeps)
        val poll = server!!.requestsTo("/api/v2/pins/123456").first()
        assertEquals("ABCD", poll.param("code"))
        assertEquals("dev-1", poll.header("X-Plex-Client-Identifier"))
    }

    @Test
    fun givesUpWhenTheCodeRunsOut() = runBlocking {
        val tv = plexTv(claimAfter = Int.MAX_VALUE)
        val time = FakeTime()
        val flow = PlexPinFlow(tv, intervalMs = 60_000, sleep = time.sleep, clock = { time.now })
        val result = flow.waitForToken(PlexTv.Pin(123456, "ABCD", expiresInSeconds = 300))
        assertTrue(result is PinResult.Expired)
        assertEquals(5, time.sleeps.size) // 300 s at one poll a minute
    }

    @Test
    fun plexTvSayingTheCodeIsGoneEndsIt() = runBlocking {
        val tv = plexTv(claimAfter = Int.MAX_VALUE, status = { n -> if (n >= 2) 404 else 200 })
        val time = FakeTime()
        val result = PlexPinFlow(tv, sleep = time.sleep, clock = { time.now }).waitForToken(PlexTv.Pin(123456, "ABCD", 900))
        assertTrue(result is PinResult.Expired)
        assertEquals(1, time.sleeps.size)
    }

    @Test
    fun ridesOutAFewNetworkErrors() = runBlocking {
        val tv = plexTv(claimAfter = 0, status = { n -> if (n <= 2) 503 else 200 })
        val time = FakeTime()
        val result = PlexPinFlow(tv, sleep = time.sleep, clock = { time.now }).waitForToken(PlexTv.Pin(123456, "ABCD", 900))
        assertEquals("plextoken-xyz", (result as PinResult.Token).token)
    }

    @Test
    fun givesUpAfterManyNetworkErrorsInARow() {
        val tv = plexTv(claimAfter = 0, status = { 503 })
        val time = FakeTime()
        try {
            runBlocking { PlexPinFlow(tv, sleep = time.sleep, clock = { time.now }, maxFailures = 4).waitForToken(PlexTv.Pin(123456, "ABCD", 900)) }
            fail("should have thrown")
        } catch (e: ServerHttpException) {
            assertEquals(503, e.code)
        }
        assertEquals(4, server!!.requestsTo("/api/v2/pins/123456").size)
    }
}
