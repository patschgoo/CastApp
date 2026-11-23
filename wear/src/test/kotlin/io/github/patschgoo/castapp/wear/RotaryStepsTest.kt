package io.github.patschgoo.castapp.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class RotaryStepsTest {
    private val notch = 50f

    private fun bezel() = RotarySteps(discrete = true, notchPx = notch)
    private fun crown() = RotarySteps(discrete = false, notchPx = notch)

    /** Feeds [deltas], one every [everyMs] from [startMs]; returns the steps that came out. */
    private fun RotarySteps.feed(deltas: List<Float>, everyMs: Long, startMs: Long = 0): List<Int> =
        deltas.mapIndexed { i, d -> onDelta(d, startMs + i * everyMs) }.filter { it != 0 }

    // ---- bezel: one event per notch ---------------------------------------------------------------------------

    @Test
    fun eachBezelNotchIsOneStepWhateverItsSize() {
        val s = bezel()
        assertEquals(1, s.onDelta(96f, 0))
        assertEquals(1, s.onDelta(3f, 200))
        assertEquals(1, s.onDelta(700f, 400))
        assertEquals(-1, s.onDelta(-96f, 600))
        assertEquals(-1, s.onDelta(-1f, 800))
    }

    @Test
    fun clockwiseIsUp() {
        assertEquals(1, bezel().onDelta(1f, 0))
        assertEquals(-1, bezel().onDelta(-1f, 0))
        assertEquals(1, crown().onDelta(notch, 0))
        assertEquals(-1, crown().onDelta(-notch, 0))
    }

    @Test
    fun aFastSpinGivesAtMostEightStepsASecond() {
        val s = bezel()
        // 100 notches in one second
        val steps = s.feed(List(100) { 96f }, everyMs = 10)
        assertEquals(RotarySteps.MAX_STEPS_PER_SECOND, steps.size)
        assertEquals(steps.size, steps.sum())
    }

    @Test
    fun slowNotchesAreAllTaken() {
        val steps = bezel().feed(List(12) { 96f }, everyMs = 200)
        assertEquals(12, steps.size)
    }

    @Test
    fun turningBackIsTakenAtOnce() {
        val s = bezel()
        assertEquals(1, s.onDelta(96f, 0))
        assertEquals(0, s.onDelta(96f, 20)) // too fast in the same direction
        assertEquals(-1, s.onDelta(-96f, 40)) // but back is never held up
        assertEquals(1, s.onDelta(96f, 60))
    }

    // ---- crown: small deltas add up ----------------------------------------------------------------------------

    @Test
    fun smallCrownDeltasAddUpToSteps() {
        val s = crown()
        // 10 px every 30 ms: a step every 5 events
        assertEquals(listOf(1, 1), s.feed(List(10) { 10f }, everyMs = 30))
        assertEquals(listOf(-1), crown().feed(List(5) { -10f }, everyMs = 30))
    }

    @Test
    fun whatIsLeftOfANotchCountsForTheNext() {
        val s = crown()
        assertEquals(0, s.onDelta(30f, 0))
        assertEquals(1, s.onDelta(30f, 200)) // 60: one step, 10 left
        assertEquals(1, s.onDelta(40f, 400)) // 10 + 40
        assertEquals(0, s.onDelta(49f, 600))
    }

    @Test
    fun aPauseForgetsAHalfTurnedNotch() {
        val s = crown()
        assertEquals(0, s.onDelta(40f, 0))
        assertEquals(0, s.onDelta(40f, 1_000)) // the first 40 are forgotten
        val t = crown()
        assertEquals(0, t.onDelta(40f, 0))
        assertEquals(1, t.onDelta(40f, 100)) // no pause: 80
    }

    @Test
    fun turningBackStartsFromZero() {
        val s = crown()
        assertEquals(0, s.onDelta(45f, 0))
        // without the reset this would first have to undo the 45
        assertEquals(0, s.onDelta(-10f, 100))
        assertEquals(-1, s.onDelta(-45f, 200))
        // and back again
        assertEquals(0, s.onDelta(30f, 300))
        assertEquals(1, s.onDelta(25f, 400))
    }

    @Test
    fun aCrownFlickIsRateLimitedAndStopsWithTheHand() {
        val s = crown()
        // 20 big deltas in 200 ms: 80 notches worth
        val steps = s.feed(List(20) { 200f }, everyMs = 10)
        assertEquals(listOf(1, 1), steps)
        // At most one notch was kept; a long pause forgets it.
        assertEquals(0, s.onDelta(1f, 2_000))
        assertEquals(0, s.onDelta(1f, 2_100))
    }

    @Test
    fun aSteadyFastCrownTurnStepsEvenly() {
        val s = crown()
        // 1000 px/s for 2 s = 20 notches; the limit lets 8 a second through
        val steps = s.feed(List(200) { 10f }, everyMs = 10)
        assertEquals(16, steps.size)
    }

    @Test
    fun nothingAndNonsenseAreIgnored() {
        val s = crown()
        assertEquals(0, s.onDelta(0f, 0))
        assertEquals(0, s.onDelta(Float.NaN, 10))
        assertEquals(0, bezel().onDelta(0f, 0))
        assertEquals(1, s.onDelta(notch, 20))
    }
}
