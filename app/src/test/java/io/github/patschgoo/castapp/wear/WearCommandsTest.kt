package io.github.patschgoo.castapp.wear

import io.github.patschgoo.castapp.automation.AutomationCommand
import io.github.patschgoo.castapp.automation.AutomationResult
import io.github.patschgoo.castapp.automation.ServiceMapping
import io.github.patschgoo.castapp.automation.ServiceStep
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.wearproto.WearCodec
import io.github.patschgoo.castapp.wearproto.WearCommand
import io.github.patschgoo.castapp.wearproto.WearMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCommandsTest {
    private val tv = CastDevice("tv1", "Living room TV", "192.168.1.5")
    private val playing = CastState.Active(tv, CastPhase.LIVE, kind = CastKind.FILE, title = "Movie", volume = 0.4f)
    private val mirroringUnknownVolume = CastState.Active(tv, CastPhase.LIVE, kind = CastKind.SCREEN, volume = null)

    private fun step(m: WearMessage, s: CastState) = ServiceMapping.stepFor(WearCommands.commandFor(m), s)

    private fun call(action: String, vararg extras: Pair<String, Any>) = ServiceStep.Call(action, mapOf(*extras))

    private fun level(step: ServiceStep?): Double {
        assertTrue("expected SET_VOLUME, got $step", step is ServiceStep.Call && step.action == CastService.ACTION_SET_VOLUME)
        return (step as ServiceStep.Call).extras[CastService.EXTRA_LEVEL] as Double
    }

    @Test
    fun buttonsMeanWhatTheyAlwaysMeant() {
        for (c in WearCommand.entries) assertEquals(ServiceMapping.commandFor(c), WearCommands.commandFor(WearMessage.Command(c)))
        // a repeat on another button is no reason to do it twice
        assertEquals(AutomationCommand.Stop, WearCommands.commandFor(WearMessage.Command(WearCommand.STOP, 3)))
    }

    @Test
    fun oneRotaryStepIsOneRemoteStep() {
        assertEquals(call(CastService.ACTION_VOLUME_UP), step(WearMessage.Command(WearCommand.VOLUME_UP, 1), playing))
        assertEquals(call(CastService.ACTION_VOLUME_DOWN), step(WearMessage.Command(WearCommand.VOLUME_DOWN, 1), playing))
    }

    @Test
    fun repeatedStepsBecomeOneVolumeChange() {
        assertEquals(AutomationCommand.ChangeVolume(up = true, amountPercent = 15), WearCommands.commandFor(WearMessage.Command(WearCommand.VOLUME_UP, 3)))
        assertEquals(AutomationCommand.ChangeVolume(up = false, amountPercent = 10), WearCommands.commandFor(WearMessage.Command(WearCommand.VOLUME_DOWN, 2)))
        // 40 % + 3 steps of 5 %, one request to the TV
        assertEquals(0.55, level(step(WearMessage.Command(WearCommand.VOLUME_UP, 3), playing)), 1e-6)
        assertEquals(0.30, level(step(WearMessage.Command(WearCommand.VOLUME_DOWN, 2), playing)), 1e-6)
        // the phone stays in range
        assertEquals(0.0, level(step(WearMessage.Command(WearCommand.VOLUME_DOWN, WearCodec.MAX_REPEAT), playing)), 1e-6)
    }

    @Test
    fun repeatedStepsAlsoWorkWhenTheTvDoesNotReportItsVolume() {
        // CastService changes the volume by the difference to the same 50 % guess, so this is +20 %.
        assertEquals(0.70, level(step(WearMessage.Command(WearCommand.VOLUME_UP, 4), mirroringUnknownVolume)), 1e-6)
    }

    @Test
    fun aLevelFromTheWatchSetsTheTvVolume() {
        assertEquals(AutomationCommand.SetVolume(45), WearCommands.commandFor(WearMessage.SetVolume(45)))
        assertEquals(call(CastService.ACTION_SET_VOLUME, CastService.EXTRA_LEVEL to 0.45), step(WearMessage.SetVolume(45), playing))
        assertEquals(call(CastService.ACTION_SET_VOLUME, CastService.EXTRA_LEVEL to 0.0), step(WearMessage.SetVolume(0), mirroringUnknownVolume))
        assertEquals(call(CastService.ACTION_SET_VOLUME, CastService.EXTRA_LEVEL to 1.0), step(WearMessage.SetVolume(100), playing))
        // never outside 0..100, even if a message got past the decoder
        assertEquals(AutomationCommand.SetVolume(100), WearCommands.commandFor(WearMessage.SetVolume(180)))
    }

    @Test
    fun withNothingCastTheWatchVolumeIsAnsweredNotSent() {
        for (m in listOf(WearMessage.SetVolume(30), WearMessage.Command(WearCommand.VOLUME_UP, 3))) {
            val s = step(m, CastState.Idle)
            assertTrue("$m: $s", s is ServiceStep.Answer)
            assertEquals(AutomationResult.NOT_CASTING, (s as ServiceStep.Answer).result)
        }
    }

    @Test
    fun whatTheWatchSendsArrivesAsTheSameCommand() {
        for (m in listOf(WearMessage.SetVolume(62), WearMessage.Command(WearCommand.VOLUME_DOWN, 4), WearMessage.Command(WearCommand.NEXT))) {
            val received = WearCodec.decodeMessage(WearCodec.encodeMessage(m))!!
            assertEquals(WearCommands.commandFor(m), WearCommands.commandFor(received))
        }
    }
}
