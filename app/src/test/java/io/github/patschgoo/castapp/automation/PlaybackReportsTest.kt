package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.automation.AutomationApi as Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an app that sent a queue with report_to hears about it. */
class PlaybackReportsTest {
    private val a = "https://media.example/a.mp4"
    private val b = "https://media.example/b.mp4"
    private val c = "https://media.example/c.mp4"
    private val target = ReportTarget("com.example.library", "req-1", listOf(a, b, c), listOf("ep-a", null, "ep-c"))

    private var now = 1_000_000L
    private val events = ArrayList<PlaybackEvent>()
    private val reporter = PlaybackReporter(target, { now }) { events += it }
    private val tv = "Living room TV"

    private val states get() = events.map { it.state }

    private fun take(): List<PlaybackEvent> = events.toList().also { events.clear() }

    // ---- the life of one item -------------------------------------------------------------------------

    @Test
    fun startedOnceThenProgressAtMostEveryThirtySeconds() {
        reporter.started(a, 0, 0, tv)
        assertEquals(listOf(PlaybackEvent(Api.STATE_STARTED, "ep-a", 0, 0, 0, tv)), take())
        // The session reports every 5 s; the app hears every 30 s.
        for (i in 1..12) {
            now += 5_000
            reporter.progress(a, i * 5_000L, 1_200_000, paused = false, device = tv)
        }
        assertEquals(listOf(Api.STATE_PROGRESS, Api.STATE_PROGRESS), states)
        assertEquals(listOf(30_000L, 60_000L), events.map { it.positionMs })
        assertTrue(events.all { it.durationMs == 1_200_000L && it.itemId == "ep-a" && it.device == tv })
    }

    @Test
    fun pauseAndResumeAreToldAtOnceAndNoProgressWhilePaused() {
        reporter.started(a, 0, 600_000, tv)
        take()
        now += 5_000
        reporter.progress(a, 5_000, 600_000, paused = true, device = tv)
        for (i in 1..20) {
            now += 5_000
            reporter.progress(a, 5_000, 600_000, paused = true, device = tv)
        }
        now += 5_000
        reporter.progress(a, 5_000, 600_000, paused = false, device = tv)
        assertEquals(listOf(Api.STATE_PAUSED, Api.STATE_RESUMED), states)
        take()
        // The next progress counts from the resume.
        now += 25_000
        reporter.progress(a, 30_000, 600_000, paused = false, device = tv)
        assertTrue(events.isEmpty())
        now += 5_000
        reporter.progress(a, 35_000, 600_000, paused = false, device = tv)
        assertEquals(listOf(Api.STATE_PROGRESS), states)
    }

    @Test
    fun playedToTheEndIsFinished() {
        reporter.started(a, 0, 600_000, tv)
        reporter.stopped(a, 600_000, 600_000, playedToEnd = true, device = tv)
        assertEquals(PlaybackEvent(Api.STATE_FINISHED, "ep-a", 0, 600_000, 600_000, tv), events.last())
    }

    @Test
    fun stoppedWithAtMostTenPercentLeftIsFinished() {
        // 24 min episode: 90 % is 21:36.
        reporter.started(a, 0, 1_440_000, tv)
        reporter.stopped(a, 1_296_000, 1_440_000, playedToEnd = false, device = tv)
        assertEquals(Api.STATE_FINISHED, events.last().state)
        assertEquals(1_296_000L, events.last().positionMs)

        reporter.started(b, 0, 1_440_000, tv)
        reporter.stopped(b, 1_295_000, 1_440_000, playedToEnd = false, device = tv)
        assertEquals(Api.STATE_STOPPED, events.last().state)
    }

    @Test
    fun theFinishedRuleMatchesContinueWatching() {
        // Short items: less than 30 s left is watched there too (ResumeRules), even below 90 %.
        assertTrue(PlaybackReporter.isFinished(171_000, 200_000))
        assertFalse(PlaybackReporter.isFinished(169_000, 200_000))
        assertTrue(PlaybackReporter.isFinished(5_400_000, 6_000_000)) // 90 %
        assertFalse(PlaybackReporter.isFinished(5_399_000, 6_000_000))
        assertFalse(PlaybackReporter.isFinished(1_000, 0)) // length unknown
    }

    @Test
    fun aStopBeforeTheTvSaidWhereItWasKeepsTheLastPosition() {
        reporter.started(a, 0, 600_000, tv)
        now += 30_000
        reporter.progress(a, 120_000, 600_000, paused = false, device = tv)
        reporter.stopped(a, 0, 0, playedToEnd = false, device = tv)
        assertEquals(PlaybackEvent(Api.STATE_STOPPED, "ep-a", 0, 120_000, 600_000, tv), events.last())
    }

    @Test
    fun lateCallsForAnItemThatEndedAreIgnored() {
        reporter.started(a, 0, 600_000, tv)
        reporter.stopped(a, 600_000, 600_000, playedToEnd = true, device = tv)
        take()
        // The session's position loop and its teardown still say something about it.
        now += 5_000
        reporter.progress(a, 600_000, 600_000, paused = false, device = tv)
        reporter.stopped(a, 600_000, 600_000, playedToEnd = false, device = tv)
        reporter.failed(a, Api.REASON_CONNECTION_LOST, tv)
        reporter.close()
        assertTrue(events.isEmpty())
    }

    @Test
    fun aReloadOfTheSameItemIsNoNewStart() {
        reporter.started(a, 0, 600_000, tv)
        reporter.started(a, 200_000, 600_000, tv) // another quality, converting from here
        assertEquals(listOf(Api.STATE_STARTED), states)
    }

    @Test
    fun goingOnToTheNextItemEndsTheLastOne() {
        reporter.started(a, 0, 600_000, tv)
        now += 30_000
        reporter.progress(a, 30_000, 600_000, paused = false, device = tv)
        take()
        reporter.started(b, 0, 0, tv)
        assertEquals(
            listOf(PlaybackEvent(Api.STATE_STOPPED, "ep-a", 0, 30_000, 600_000, tv), PlaybackEvent(Api.STATE_STARTED, null, 1, 0, 0, tv)),
            events,
        )
    }

    @Test
    fun playingAnItemAgainReportsAgain() {
        reporter.started(a, 0, 600_000, tv)
        reporter.stopped(a, 600_000, 600_000, playedToEnd = true, device = tv)
        reporter.started(a, 0, 600_000, tv) // "previous", or repeat
        assertEquals(listOf(Api.STATE_STARTED, Api.STATE_FINISHED, Api.STATE_STARTED), states)
    }

    // ---- failures ---------------------------------------------------------------------------------------

    @Test
    fun anItemThatNeverStartedFails() {
        reporter.failed(b, Api.REASON_CANNOT_PLAY, tv)
        assertEquals(
            listOf(PlaybackEvent(Api.STATE_FAILED, null, 1, 0, 0, tv, Api.REASON_CANNOT_PLAY, "The TV could not play this item")),
            events,
        )
        // ...once: the teardown's stop is not reported after it.
        reporter.stopped(b, 0, 0, playedToEnd = false, device = tv)
        reporter.failed(b, Api.REASON_CANNOT_PLAY, tv)
        assertEquals(1, events.size)
    }

    @Test
    fun aFailureMidWayKeepsWhereItWas() {
        reporter.started(c, 0, 900_000, tv)
        now += 30_000
        reporter.progress(c, 400_000, 900_000, paused = false, device = tv)
        take()
        reporter.failed(c, Api.REASON_CONNECTION_LOST, tv)
        reporter.stopped(c, 400_000, 900_000, playedToEnd = false, device = tv)
        reporter.close()
        assertEquals(
            listOf(PlaybackEvent(Api.STATE_FAILED, "ep-c", 2, 400_000, 900_000, tv, Api.REASON_CONNECTION_LOST, "The connection to the TV was lost")),
            events,
        )
    }

    @Test
    fun everyReasonHasAMessage() {
        for (reason in listOf(Api.REASON_CANNOT_PLAY, Api.REASON_CONNECTION_LOST, Api.REASON_CAST_FAILED)) {
            assertNotEquals("Playback failed", PlaybackReporter.messageFor(reason))
        }
    }

    private fun assertNotEquals(unexpected: String, actual: String) = assertFalse(actual == unexpected)

    // ---- items the app did not send --------------------------------------------------------------------

    @Test
    fun itemsTheAppDidNotSendAreNeverReported() {
        val other = "https://media.example/added-in-castapp.mp4"
        reporter.started(other, 0, 600_000, tv)
        reporter.progress(other, 1, 600_000, paused = true, device = tv)
        reporter.stopped(other, 600_000, 600_000, playedToEnd = true, device = tv)
        reporter.failed(other, Api.REASON_CANNOT_PLAY, tv)
        assertTrue(events.isEmpty())
    }

    @Test
    fun closingStopsThePlayingItemWhereItWasSeenLast() {
        reporter.started(a, 0, 600_000, tv)
        now += 30_000
        reporter.progress(a, 590_000, 600_000, paused = false, device = tv)
        take()
        reporter.close()
        assertEquals(listOf(PlaybackEvent(Api.STATE_FINISHED, "ep-a", 0, 590_000, 600_000, tv)), events)
    }

    @Test
    fun theTvNameFollowsTheCast() {
        reporter.started(a, 0, 600_000, tv)
        reporter.progress(a, 1_000, 600_000, paused = true, device = "Bedroom TV") // the first screen left, the next took over
        assertEquals("Bedroom TV", events.last().device)
    }

    // ---- the broadcast's extras ----------------------------------------------------------------------------

    @Test
    fun theBroadcastCarriesTheDocumentedExtras() {
        val failed = PlaybackEvent(Api.STATE_FAILED, "ep-a", 0, 12_000, 600_000, tv, Api.REASON_CANNOT_PLAY, "The TV could not play this item")
        assertEquals(
            mapOf(
                "state" to "failed", "item_id" to "ep-a", "item_index" to 0, "position_ms" to 12_000L, "duration_ms" to 600_000L,
                "device" to tv, "reason" to "cannot_play", "message" to "The TV could not play this item", "request_id" to "req-1",
            ),
            failed.extras("req-1"),
        )
        val noId = PlaybackEvent(Api.STATE_PROGRESS, null, 1, 30_000, 0, tv).extras(null)
        assertEquals(setOf("state", "item_index", "position_ms", "duration_ms", "device"), noId.keys)
        assertTrue(noId[Api.PLAYBACK_EXTRA_POSITION] is Long && noId[Api.PLAYBACK_EXTRA_ITEM_INDEX] is Int)
    }

    @Test
    fun aTargetIsMadeOnlyWhenReportToIsGiven() {
        val queue = CastQueueRequest(listOf(QueueItem(a, id = "x"), QueueItem(b)), requestId = "r")
        assertNull(ReportTarget.of(queue))
        val t = ReportTarget.of(queue.copy(reportTo = "com.example.library"))!!
        assertEquals("com.example.library", t.packageName)
        assertEquals("r", t.requestId)
        assertEquals(listOf(a, b), t.urls)
        assertEquals(ReportTarget.Item("x", 0), t.itemFor(a))
        assertEquals(ReportTarget.Item(null, 1), t.itemFor(b))
        assertNull(t.itemFor(c))
    }
}

/** Which cast session reports to the app: only the one that plays exactly the queue it sent. */
class ReportBindingTest {
    private val a = "https://media.example/a.mp4"
    private val b = "https://media.example/b.mp4"
    private var now = 0L
    private val events = ArrayList<Pair<String, PlaybackEvent>>()
    private val binding = ReportBinding({ now }, { t -> PlaybackReporter(t, { now }) { events += t.packageName to it } }, pendingForMs = 60_000)
    private val session = Any()
    private val otherSession = Any()

    private fun target(pkg: String = "com.example.library", urls: List<String> = listOf(a, b)) = ReportTarget(pkg, null, urls, urls.map { "id-$it" })

    @Test
    fun theSessionThatStartsWithTheQueueReports() {
        assertTrue(binding.idle)
        binding.expect(target())
        assertFalse(binding.idle)
        binding.onQueue(session, listOf(a, b))
        binding.reporterFor(session)!!.started(a, 0, 0, "TV")
        assertNull(binding.reporterFor(otherSession))
        assertEquals(listOf(Api.STATE_STARTED), events.map { it.second.state })
    }

    @Test
    fun aSessionWithOtherItemsDoesNotReport() {
        binding.expect(target())
        binding.onQueue(session, listOf(b, a)) // another order is another queue
        assertNull(binding.reporterFor(session))
        binding.onQueue(otherSession, listOf(a))
        assertNull(binding.reporterFor(otherSession))
        // The announced one still binds when its session comes.
        binding.onQueue(session, listOf(a, b))
        assertTrue(binding.reporterFor(session) != null)
    }

    @Test
    fun anAnnouncementThatNobodyPicksUpExpires() {
        binding.expect(target())
        now += 61_000
        binding.onQueue(session, listOf(a, b))
        assertNull(binding.reporterFor(session))
        assertTrue(binding.idle)
    }

    @Test
    fun aCancelledStartBindsNothing() {
        val t = target()
        binding.expect(t)
        binding.cancel(t)
        binding.onQueue(session, listOf(a, b))
        assertNull(binding.reporterFor(session))
    }

    @Test
    fun somethingElseCastToTheSameTvEndsTheReports() {
        binding.expect(target())
        binding.onQueue(session, listOf(a, b))
        binding.reporterFor(session)!!.started(a, 0, 600_000, "TV")
        // The user casts a video from the app to the same TV: the session keeps running with other items.
        binding.onQueue(session, listOf("https://elsewhere.example/v.mp4"))
        assertNull(binding.reporterFor(session))
        assertEquals(listOf(Api.STATE_STARTED, Api.STATE_STOPPED), events.map { it.second.state })
        assertTrue(binding.idle)
    }

    @Test
    fun aNewQueueFromAnotherAppTakesOver() {
        binding.expect(target("com.example.one"))
        binding.onQueue(session, listOf(a, b))
        binding.reporterFor(session)!!.started(a, 0, 600_000, "TV")
        binding.expect(target("com.example.two", listOf(b)))
        binding.onQueue(otherSession, listOf(b))
        binding.reporterFor(otherSession)!!.started(b, 0, 600_000, "TV")
        assertEquals(
            listOf("com.example.one" to Api.STATE_STARTED, "com.example.one" to Api.STATE_STOPPED, "com.example.two" to Api.STATE_STARTED),
            events.map { it.first to it.second.state },
        )
        // The old session's teardown says nothing any more.
        assertNull(binding.reporterFor(session))
        binding.ended(session)
        assertEquals(3, events.size)
    }

    @Test
    fun theEndOfTheCastEndsTheReports() {
        binding.expect(target())
        binding.onQueue(session, listOf(a, b))
        binding.reporterFor(session)!!.started(b, 0, 600_000, "TV")
        binding.ended(otherSession)
        assertTrue(binding.reporterFor(session) != null)
        binding.ended(session)
        assertNull(binding.reporterFor(session))
        assertEquals(listOf(Api.STATE_STARTED, Api.STATE_STOPPED), events.map { it.second.state })
        assertTrue(binding.idle)
    }
}
