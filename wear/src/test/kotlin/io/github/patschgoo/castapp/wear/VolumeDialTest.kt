package io.github.patschgoo.castapp.wear

import io.github.patschgoo.castapp.wearproto.NowPlaying
import io.github.patschgoo.castapp.wearproto.WearCodec
import io.github.patschgoo.castapp.wearproto.WearCommand
import io.github.patschgoo.castapp.wearproto.WearMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeDialTest {
    private fun dial(playing: NowPlaying) = VolumeDial(stepPercent = 5, sendIntervalMs = 250, holdMs = 2_000).also { it.onPhone(playing) }

    /** CastApp 4.1+: takes levels and repeats. */
    private fun newPhone(volume: Int) = NowPlaying(active = true, volumePercent = volume, revision = WearCodec.REVISION)

    /** CastApp 4.0: sends no revision, so it is 1. */
    private fun oldPhone(volume: Int) = NowPlaying(active = true, volumePercent = volume)

    private fun set(level: Int) = WearMessage.SetVolume(level)
    private fun up(n: Int = 1) = WearMessage.Command(WearCommand.VOLUME_UP, n)
    private fun down(n: Int = 1) = WearMessage.Command(WearCommand.VOLUME_DOWN, n)

    // ---- a phone that takes levels ------------------------------------------------------------------------------

    @Test
    fun aPhoneThatKnowsTheVolumeGetsTheLevel() {
        val d = dial(newPhone(40))
        assertEquals(VolumeDial.Turn(45, up = true, atLimit = false, send = listOf(set(45))), d.turn(1, 0))
        assertEquals(45, d.level(0))
        assertNull(d.nextFlushAt())
    }

    @Test
    fun aFastTurnIsOneLevelMessagePerInterval() {
        val d = dial(newPhone(40))
        assertEquals(listOf(set(45)), d.turn(1, 0)!!.send)
        // the next steps of the turn wait for the interval, but the level follows at once
        assertEquals(VolumeDial.Turn(50, up = true, atLimit = false, send = emptyList()), d.turn(1, 100))
        assertEquals(55, d.turn(1, 200)!!.level)
        assertEquals(250L, d.nextFlushAt())
        assertEquals(emptyList<WearMessage>(), d.flush(249))
        assertEquals(listOf(set(55)), d.flush(250))
        assertNull(d.nextFlushAt())
        assertEquals(emptyList<WearMessage>(), d.flush(600))
    }

    @Test
    fun turningAwayAndBackWithinTheIntervalSendsNothingMore() {
        val d = dial(newPhone(40))
        assertEquals(listOf(set(45)), d.turn(1, 0)!!.send)
        d.turn(1, 100)
        assertEquals(45, d.turn(-1, 200)!!.level)
        assertEquals(emptyList<WearMessage>(), d.flush(250))
        assertNull(d.nextFlushAt())
    }

    @Test
    fun lateAnswersFromThePhoneDoNotPullTheLevelBack() {
        val d = dial(newPhone(40))
        d.turn(1, 0)
        d.turn(1, 100)
        d.turn(1, 200)
        d.flush(250)
        // the phone's answer to the first message comes after the others were dialed
        d.onPhone(newPhone(45))
        assertEquals(55, d.level(400))
        assertEquals(listOf(set(60)), d.turn(1, 500)!!.send)
    }

    @Test
    fun aMomentAfterTheTurnThePhoneCountsAgain() {
        val d = dial(newPhone(40))
        d.turn(1, 0)
        d.onPhone(newPhone(30)) // someone used the TV remote
        assertEquals(45, d.level(1_999))
        assertEquals(30, d.level(2_000))
        assertEquals(VolumeDial.Turn(35, up = true, atLimit = false, send = listOf(set(35))), d.turn(1, 2_500))
    }

    @Test
    fun theLevelStopsAt0And100() {
        val d = dial(newPhone(98))
        assertEquals(VolumeDial.Turn(100, up = true, atLimit = false, send = listOf(set(100))), d.turn(1, 0))
        assertEquals(VolumeDial.Turn(100, up = true, atLimit = true, send = emptyList()), d.turn(1, 300))
        assertNull(d.nextFlushAt())
        val low = dial(newPhone(0))
        assertEquals(VolumeDial.Turn(0, up = false, atLimit = true, send = emptyList()), low.turn(-1, 0))
        assertEquals(listOf(set(5)), low.turn(1, 100)!!.send)
    }

    @Test
    fun withoutAKnownVolumeTheStepsAreSentAsRepeats() {
        val d = dial(newPhone(-1))
        assertEquals(VolumeDial.Turn(-1, up = true, atLimit = false, send = listOf(up(1))), d.turn(1, 0))
        assertEquals(emptyList<WearMessage>(), d.turn(1, 100)!!.send)
        assertEquals(emptyList<WearMessage>(), d.turn(1, 200)!!.send)
        assertEquals(listOf(up(2)), d.flush(250))
        assertEquals(-1, d.level(300))
    }

    @Test
    fun repeatsInBothDirectionsCancelOut() {
        val d = dial(newPhone(-1))
        d.turn(-1, 0)
        d.turn(-1, 50)
        d.turn(-1, 100)
        d.turn(1, 150)
        assertEquals(listOf(down(1)), d.flush(250))
        val e = dial(newPhone(-1))
        e.turn(1, 0)
        e.turn(1, 100)
        e.turn(-1, 200)
        assertNull(e.nextFlushAt())
    }

    @Test
    fun aRepeatHasAtMostTenStepsAndTheRestFollows() {
        val d = dial(newPhone(-1))
        d.turn(1, 0)
        for (t in 1..15) d.turn(1, t.toLong())
        assertEquals(listOf(up(WearCodec.MAX_REPEAT)), d.flush(250))
        assertEquals(500L, d.nextFlushAt())
        assertEquals(listOf(up(5)), d.flush(500))
        assertNull(d.nextFlushAt())
    }

    @Test
    fun whenThePhoneLearnsTheVolumeMidTurnNoStepIsLost() {
        val d = dial(newPhone(-1))
        assertEquals(listOf(up(1)), d.turn(1, 0)!!.send)
        d.turn(1, 100) // waits
        d.onPhone(newPhone(55)) // the phone guessed 50 and did the first step
        assertEquals(65, d.turn(1, 200)!!.level)
        assertEquals(listOf(set(65)), d.flush(250))
        assertNull(d.nextFlushAt())
    }

    // ---- an older phone ------------------------------------------------------------------------------------------

    @Test
    fun anOlderPhoneGetsOneButtonPressPerStep() {
        val d = dial(oldPhone(40))
        assertEquals(VolumeDial.Turn(45, up = true, atLimit = false, send = listOf(up())), d.turn(1, 0))
        assertEquals(listOf(up()), d.turn(1, 10)!!.send)
        assertEquals(listOf(down()), d.turn(-1, 20)!!.send)
        assertEquals(45, d.level(30))
        assertNull(d.nextFlushAt())
    }

    @Test
    fun anOlderPhoneNeverGetsALevelOrARepeat() {
        for (start in listOf(-1, 0, 50, 100)) {
            val d = dial(oldPhone(start))
            for (t in 0 until 40) {
                val turn = d.turn(if (t % 7 == 6) -1 else 1, t * 30L)!!
                for (m in turn.send) assertTrue("$m", m is WearMessage.Command && m.repeat == 1)
                for (m in d.flush(t * 30L)) assertTrue("$m", m is WearMessage.Command && m.repeat == 1)
            }
        }
    }

    @Test
    fun anOlderPhoneWithoutAVolumeStillGetsTheSteps() {
        val d = dial(oldPhone(-1))
        assertEquals(VolumeDial.Turn(-1, up = false, atLimit = false, send = listOf(down())), d.turn(-1, 0))
    }

    @Test
    fun anOlderPhoneAtTheLimitIsNotBothered() {
        val d = dial(oldPhone(100))
        assertTrue(d.turn(1, 0)!!.atLimit)
        assertEquals(emptyList<WearMessage>(), d.turn(1, 200)!!.send)
    }

    // ---- other ---------------------------------------------------------------------------------------------------

    @Test
    fun noStepsNoTurn() {
        assertNull(dial(newPhone(40)).turn(0, 0))
    }

    @Test
    fun whenTheCastEndsEverythingIsForgotten() {
        val d = dial(newPhone(40))
        d.turn(1, 0)
        d.turn(1, 100)
        d.onPhone(NowPlaying(active = false, revision = WearCodec.REVISION))
        assertEquals(-1, d.level(200))
        assertNull(d.nextFlushAt())
        assertEquals(emptyList<WearMessage>(), d.flush(500))
    }

    @Test
    fun aTapOnAVolumeButtonDropsTheDialedLevel() {
        val d = dial(newPhone(40))
        d.turn(1, 0)
        d.turn(1, 100)
        d.onButton()
        assertEquals(40, d.level(150))
        assertNull(d.nextFlushAt())
        assertFalse(d.turn(1, 300)!!.atLimit)
    }
}
