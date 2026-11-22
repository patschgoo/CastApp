package io.github.patschgoo.castapp.mediaservers

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The progress reports to Jellyfin and Plex, against local servers. */
class ProgressReporterTest {
    private val info = ClientInfo(deviceId = "dev-1", deviceName = "Pixel 8", version = "4.0.0")
    private var server: TestServer? = null
    private var now = 1_000_000L

    @After
    fun stop() {
        server?.close()
    }

    private fun jellyfin(handler: (TestServer.Req) -> TestServer.Resp? = { TestServer.Resp(204) }): ProgressReporter {
        server = TestServer(handler)
        val client = JellyfinClient(ServerAccount("acc1", ServerKind.JELLYFIN, "Home", server!!.url, "tok123", userId = "user1"), info)
        return ProgressReporter({ if (it == "acc1") client else null }, clock = { now }, minIntervalMs = 4000, retryDelayMs = 5)
    }

    private fun plex(): ProgressReporter {
        server = TestServer { TestServer.Resp(200, "") }
        val client = PlexClient(ServerAccount("plex-1", ServerKind.PLEX, "PC", server!!.url, "srvtok"), info)
        return ProgressReporter({ if (it == "plex-1") client else null }, clock = { now }, minIntervalMs = 4000, retryDelayMs = 5)
    }

    private val jfRef = ItemRef(ServerKind.JELLYFIN, "acc1", "m1", "src1", "sess1", PlayMethod.DIRECT)
    private val plexRef = ItemRef(ServerKind.PLEX, "plex-1", "101", "501", "sess1", PlayMethod.DIRECT)

    @Test
    fun jellyfinGetsPlayingProgressAndStopped() {
        val reporter = jellyfin()
        reporter.started(jfRef, 1_380_000, 6_300_000)
        reporter.progress(jfRef, 1_381_000, 6_300_000, paused = false) // too soon: nothing
        now += 5000
        reporter.progress(jfRef, 1_385_000, 6_300_000, paused = false)
        reporter.progress(jfRef, 1_385_500, 6_300_000, paused = true) // pausing is sent right away
        reporter.stopped(jfRef, 1_400_000, 6_300_000, finished = false)
        reporter.drain()

        val s = server!!
        val start = s.requestsTo("/Sessions/Playing").single()
        assertEquals("POST", start.method)
        val body = JSONObject(start.body)
        assertEquals("m1", body.getString("ItemId"))
        assertEquals("src1", body.getString("MediaSourceId"))
        assertEquals("sess1", body.getString("PlaySessionId"))
        assertEquals(13_800_000_000L, body.getLong("PositionTicks")) // 23:00 in 100 ns ticks
        assertEquals("DirectPlay", body.getString("PlayMethod"))
        assertEquals(false, body.getBoolean("IsPaused"))
        assertTrue(body.getBoolean("CanSeek"))

        val progress = s.requestsTo("/Sessions/Playing/Progress")
        assertEquals(2, progress.size)
        assertEquals(13_850_000_000L, JSONObject(progress[0].body).getLong("PositionTicks"))
        assertEquals("TimeUpdate", JSONObject(progress[0].body).getString("EventName"))
        assertEquals("Pause", JSONObject(progress[1].body).getString("EventName"))
        assertEquals(true, JSONObject(progress[1].body).getBoolean("IsPaused"))

        val stopBody = JSONObject(s.requestsTo("/Sessions/Playing/Stopped").single().body)
        assertEquals(14_000_000_000L, stopBody.getLong("PositionTicks"))
        assertEquals("m1", stopBody.getString("ItemId"))
        assertEquals("sess1", stopBody.getString("PlaySessionId"))

        // Every request is signed in, and in the order it happened.
        assertTrue(s.requests.all { it.header("X-Emby-Authorization")!!.contains("Token=\"tok123\"") })
        assertEquals(
            listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"),
            s.requests.map { it.path },
        )
    }

    @Test
    fun finishedReportsTheEndAsThePosition() {
        val reporter = jellyfin()
        reporter.started(jfRef, 0, 6_300_000)
        reporter.stopped(jfRef, 6_290_000, 6_300_000, finished = true)
        reporter.drain()
        assertEquals(63_000_000_000L, JSONObject(server!!.requestsTo("/Sessions/Playing/Stopped").single().body).getLong("PositionTicks"))
    }

    @Test
    fun nothingIsSentAfterStoppingOrForOtherItems() {
        val reporter = jellyfin()
        reporter.progress(jfRef, 1000, 5000, false) // never started
        reporter.stopped(jfRef, 1000, 5000, false)
        reporter.started(jfRef, 0, 5000)
        reporter.stopped(jfRef, 1000, 5000, finished = true)
        reporter.stopped(jfRef, 1000, 5000, false) // the session's second "stopped" for the same item
        now += 10_000
        reporter.progress(jfRef, 2000, 5000, false)
        reporter.progress(jfRef.copy(itemId = "other"), 2000, 5000, false)
        reporter.drain()
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Stopped"), server!!.requests.map { it.path })
    }

    @Test
    fun startingTheSameItemAgainOnlyUpdatesTheProgress() {
        val reporter = jellyfin()
        reporter.started(jfRef, 0, 5000)
        reporter.started(jfRef, 3000, 5000) // quality change: the TV loads it again
        reporter.drain()
        assertEquals(1, server!!.requestsTo("/Sessions/Playing").size)
        assertEquals(1, server!!.requestsTo("/Sessions/Playing/Progress").size)
    }

    @Test
    fun theStopReportIsRetriedWhenTheServerHiccups() {
        val calls = AtomicInteger()
        val reporter = jellyfin { r -> if (r.path.endsWith("/Stopped") && calls.incrementAndGet() == 1) TestServer.Resp(500, "") else TestServer.Resp(204) }
        reporter.started(jfRef, 0, 5000)
        reporter.stopped(jfRef, 4000, 5000, false)
        reporter.drain()
        assertEquals(2, server!!.requestsTo("/Sessions/Playing/Stopped").size)
    }

    @Test
    fun aRefusalIsNotRetried() {
        val reporter = jellyfin { r -> if (r.path.endsWith("/Stopped")) TestServer.Resp(401, "") else TestServer.Resp(204) }
        reporter.started(jfRef, 0, 5000)
        reporter.stopped(jfRef, 4000, 5000, false)
        reporter.drain()
        assertEquals(1, server!!.requestsTo("/Sessions/Playing/Stopped").size)
    }

    @Test
    fun aSignedOutAccountIsIgnored() {
        val reporter = jellyfin()
        val gone = jfRef.copy(accountId = "deleted")
        reporter.started(gone, 0, 5000)
        reporter.stopped(gone, 1000, 5000, false)
        reporter.drain()
        assertTrue(server!!.requests.isEmpty())
    }

    @Test
    fun plexGetsTimelineUpdates() {
        val reporter = plex()
        reporter.started(plexRef, 1_380_000, 6_300_000)
        now += 5000
        reporter.progress(plexRef, 1_385_000, 6_300_000, paused = false)
        reporter.progress(plexRef, 1_386_000, 6_300_000, paused = true)
        reporter.stopped(plexRef, 1_400_000, 6_300_000, finished = false)
        reporter.drain()

        val timeline = server!!.requestsTo("/:/timeline")
        assertEquals(listOf("playing", "playing", "paused", "stopped"), timeline.map { it.param("state") })
        assertEquals(listOf("1380000", "1385000", "1386000", "1400000"), timeline.map { it.param("time") })
        val first = timeline.first()
        assertEquals("GET", first.method)
        assertEquals("101", first.param("ratingKey"))
        assertEquals("/library/metadata/101", first.param("key"))
        assertEquals("6300000", first.param("duration"))
        assertEquals("com.plexapp.plugins.library", first.param("identifier"))
        assertEquals("srvtok", first.header("X-Plex-Token"))
        assertEquals("dev-1", first.header("X-Plex-Client-Identifier"))
        assertEquals("sess1", first.header("X-Plex-Session-Identifier"))
        // Stopped early: not marked as watched.
        assertTrue(server!!.requestsTo("/:/scrobble").isEmpty())
    }

    @Test
    fun plexIsToldWhenItWasWatchedToTheEnd() {
        val reporter = plex()
        reporter.started(plexRef, 0, 6_300_000)
        reporter.stopped(plexRef, 6_290_000, 6_300_000, finished = true)
        reporter.drain()
        assertEquals("6300000", server!!.requestsTo("/:/timeline").last().param("time"))
        assertEquals("101", server!!.requestsTo("/:/scrobble").single().param("key"))

        // Stopping at 95 % without the TV reporting the end counts as watched, too.
        val again = plexRef.copy(itemId = "102")
        reporter.started(again, 0, 1000)
        reporter.stopped(again, 950, 1000, finished = false)
        reporter.drain()
        assertEquals(listOf("101", "102"), server!!.requestsTo("/:/scrobble").map { it.param("key") })
    }
}
