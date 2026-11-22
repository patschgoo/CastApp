package io.github.patschgoo.castapp.multi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Clock offsets, the shared timeline and how a receiver keeps in sync. */
class SyncTest {
    /** A round trip to a host whose clock is [offset] ahead, [up]/[down] ms on the way there and back. */
    private fun sample(localSend: Long, offset: Long, up: Long, down: Long, processing: Long = 1): ClockSample {
        val t1 = localSend + up + offset
        val t2 = t1 + processing
        val t3 = t2 - offset + down
        return ClockSample(localSend, t1, t2, t3)
    }

    @Test
    fun offsetFromSymmetricRoundTrips() {
        val samples = (0 until 5).map { sample(1000L + it * 100, offset = 5_000, up = 4, down = 4) }
        val e = ClockSync.estimate(samples)!!
        assertEquals(5_000, e.offsetMs)
        assertEquals(8, e.roundTripMs)
        assertEquals(6_000, e.toHost(1_000))
        assertEquals(1_000, e.toLocal(6_000))
    }

    @Test
    fun slowLopsidedTripsDontSkewTheOffset() {
        val samples = listOf(
            sample(0, offset = -2_000, up = 3, down = 3),
            // Wi-Fi queuing: 200 ms on the way there only. Taken at face value it would be 100 ms off.
            sample(100, offset = -2_000, up = 200, down = 3),
            sample(400, offset = -2_000, up = 4, down = 2),
            sample(700, offset = -2_000, up = 90, down = 150),
        )
        val e = ClockSync.estimate(samples)!!
        assertTrue("offset ${e.offsetMs}", abs(e.offsetMs - -2_000) <= 1)
        assertEquals(6, e.roundTripMs)
    }

    @Test
    fun noUsableSampleNoEstimate() {
        assertNull(ClockSync.estimate(emptyList()))
        // Answer before the question: nonsense, ignored.
        assertNull(ClockSync.estimate(listOf(ClockSample(100, 50, 40, 90))))
    }

    @Test
    fun combineKeepsTheBetterMeasurement() {
        val good = ClockEstimate(1_000, 6)
        // A slower measurement that roughly agrees only nudges it.
        val slow = ClockEstimate(1_020, 60)
        assertEquals(1_005, ClockSync.combine(good, slow)!!.offsetMs)
        // An equally quick one replaces it.
        assertEquals(1_012, ClockSync.combine(good, ClockEstimate(1_012, 7))!!.offsetMs)
        // The clocks really jumped (way beyond what the round trips allow): take it.
        assertEquals(9_000, ClockSync.combine(good, ClockEstimate(9_000, 40))!!.offsetMs)
        assertEquals(good, ClockSync.combine(good, null))
        assertEquals(good, ClockSync.combine(null, good))
    }

    @Test
    fun timelineRunsWhilePlaying() {
        var now = 10_000L
        val host = HostTimeline({ now })
        host.start("item-1", positionMs = 30_000, playing = true, live = false)
        now += 2_500
        assertEquals(32_500, host.current!!.positionAt(now))
        host.jump(40_000, playing = false)
        now += 5_000
        assertEquals("paused: stays", 40_000, host.current!!.positionAt(now))
        val json = Timeline.fromJson(host.current!!.toJson())
        assertEquals(host.current, json)
    }

    @Test
    fun theFirstScreenMovesTheTimelineOnlyWhenItReallyDisagrees() {
        var now = 0L
        val host = HostTimeline({ now })
        host.observe("a", 0, playing = true, live = false)
        val first = host.current!!.generation
        now = 5_000
        // DLNA reports are a second coarse: 4.4 s instead of 5 s is no reason to jump.
        host.observe("a", 4_400, playing = true, live = false)
        assertEquals(first, host.current!!.generation)
        // The TV stalled for 3 s: follow it.
        host.observe("a", 2_000, playing = true, live = false)
        assertEquals(2_000, host.current!!.positionAt(now))
        assertTrue(host.current!!.generation > first)
        // Paused on the TV, and the next item.
        host.observe("a", 2_000, playing = false, live = false)
        assertFalse(host.current!!.playing)
        host.observe("b", 0, playing = true, live = false)
        assertEquals("b", host.current!!.itemKey)
    }

    @Test
    fun inSyncNothingToDo() {
        val c = DriftCorrector.decide(10_020, localPlaying = true, targetMs = 10_000, targetPlaying = true)
        assertEquals(Correction(rate = 1f), c)
    }

    @Test
    fun smallDifferencesByPlaybackSpeed() {
        // 200 ms ahead: play slower (at most 5 %).
        val ahead = DriftCorrector.decide(10_200, true, 10_000, true)
        assertNull(ahead.seekToMs)
        assertEquals(0.95f, ahead.rate)
        // 60 ms behind: a little faster.
        val behind = DriftCorrector.decide(9_940, true, 10_000, true)
        assertEquals(1.03f, behind.rate)
        assertNull(behind.seekToMs)
    }

    @Test
    fun keepsCorrectingUntilReallyClose() {
        // 30 ms off: within the dead band from rest, but not while already correcting.
        assertEquals(1f, DriftCorrector.decide(10_030, true, 10_000, true, currentRate = 1f).rate)
        val going = DriftCorrector.decide(10_030, true, 10_000, true, currentRate = 0.98f)
        assertTrue("still slower: ${going.rate}", going.rate < 1f)
        assertEquals(1f, DriftCorrector.decide(10_010, true, 10_000, true, currentRate = 0.98f).rate)
    }

    @Test
    fun bigDifferencesWithAJump() {
        val c = DriftCorrector.decide(3_000, true, 10_000, true)
        assertEquals(10_000L, c.seekToMs)
        assertEquals(1f, c.rate)
    }

    @Test
    fun followsPauseAndPlay() {
        val pause = DriftCorrector.decide(10_100, localPlaying = true, targetMs = 10_000, targetPlaying = false)
        assertEquals(false, pause.play)
        assertNull("close enough to stay", pause.seekToMs)
        val moved = DriftCorrector.decide(15_000, localPlaying = false, targetMs = 10_000, targetPlaying = false)
        assertEquals(10_000L, moved.seekToMs)
        assertNull(moved.play)
        val resume = DriftCorrector.decide(10_000, localPlaying = false, targetMs = 10_010, targetPlaying = true)
        assertEquals(true, resume.play)
    }

    @Test
    fun waitsForASharedStartAhead() {
        // The host starts at a moment that hasn't come yet: hold at the beginning.
        val c = DriftCorrector.decide(0, localPlaying = true, targetMs = -300, targetPlaying = true)
        assertEquals(false, c.play)
        assertNull(c.seekToMs)
    }

    @Test
    fun followerWithoutTimelineOrClockDoesNothing() {
        val f = SyncFollower("http://127.0.0.1:1/castapp/timeline", { 0L })
        assertNull(f.correction(0, true, 1f))
        assertNotNull(f.syncUrl)
    }
}
