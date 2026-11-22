package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.wearproto.WearCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceMappingTest {
    private val tv = CastDevice("tv1", "Living room TV", "192.168.1.5")
    private val idle = CastState.Idle
    private val mirroring = CastState.Active(tv, CastPhase.LIVE, kind = CastKind.SCREEN, volume = 0.4f, qualityLabel = "720p", qualityOptions = listOf("480p", "720p", "1080p"))
    private val playing = CastState.Active(tv, CastPhase.LIVE, kind = CastKind.FILE, title = "Movie", volume = 0.5f, qualityLabel = "Original", qualityOptions = listOf("Original", "1080p", "720p"))
    private val paused = playing.copy(paused = true)

    private fun step(c: AutomationCommand, s: CastState) = ServiceMapping.stepFor(c, s)

    private fun call(action: String, vararg extras: Pair<String, Any>) = ServiceStep.Call(action, mapOf(*extras))

    private fun answer(step: ServiceStep?): AutomationResult {
        assertTrue("expected an answer, got $step", step is ServiceStep.Answer)
        return (step as ServiceStep.Answer).result
    }

    // ---- the plain mappings ----------------------------------------------------------------------------------

    @Test
    fun controlsMapToTheServiceActionsOfTheNotification() {
        assertEquals(call(CastService.ACTION_STOP), step(AutomationCommand.Stop, playing))
        assertEquals(call(CastService.ACTION_STOP), step(AutomationCommand.Stop, mirroring))
        assertEquals(call(CastService.ACTION_PLAY_PAUSE), step(AutomationCommand.PlayPause, playing))
        assertEquals(call(CastService.ACTION_NEXT), step(AutomationCommand.Next, playing))
        assertEquals(call(CastService.ACTION_PREVIOUS), step(AutomationCommand.Previous, playing))
        assertEquals(call(CastService.ACTION_VOLUME_UP), step(AutomationCommand.ChangeVolume(up = true), mirroring))
        assertEquals(call(CastService.ACTION_VOLUME_DOWN), step(AutomationCommand.ChangeVolume(up = false), playing))
        assertEquals(call(CastService.ACTION_TOGGLE_MUTE), step(AutomationCommand.Mute, mirroring))
    }

    @Test
    fun playAndPauseOnlyActWhenTheStateDiffers() {
        assertEquals(call(CastService.ACTION_PLAY_PAUSE), step(AutomationCommand.Play, paused))
        assertEquals(AutomationResult.CODE_OK, answer(step(AutomationCommand.Play, playing)).code)
        assertEquals(call(CastService.ACTION_PLAY_PAUSE), step(AutomationCommand.Pause, playing))
        assertEquals(AutomationResult.CODE_OK, answer(step(AutomationCommand.Pause, paused)).code)
    }

    @Test
    fun setVolumeSendsALevelBetween0And1() {
        assertEquals(call(CastService.ACTION_SET_VOLUME, CastService.EXTRA_LEVEL to 0.3), step(AutomationCommand.SetVolume(30), mirroring))
        assertEquals(call(CastService.ACTION_SET_VOLUME, CastService.EXTRA_LEVEL to 0.0), step(AutomationCommand.SetVolume(0), playing))
        assertEquals(call(CastService.ACTION_SET_VOLUME, CastService.EXTRA_LEVEL to 1.0), step(AutomationCommand.SetVolume(100), playing))
    }

    @Test
    fun volumeByAnAmountIsRelativeToTheReportedVolumeAndStaysInRange() {
        fun target(c: AutomationCommand, s: CastState): Double =
            ((step(c, s) as ServiceStep.Call).extras[CastService.EXTRA_LEVEL] as Double)
        assertEquals(0.5, target(AutomationCommand.ChangeVolume(up = true, amountPercent = 10), mirroring), 1e-6) // 0.4 + 0.1
        assertEquals(0.3, target(AutomationCommand.ChangeVolume(up = false, amountPercent = 20), playing), 1e-6) // 0.5 - 0.2
        assertEquals(1.0, target(AutomationCommand.ChangeVolume(up = true, amountPercent = 90), playing), 1e-6)
        assertEquals(0.0, target(AutomationCommand.ChangeVolume(up = false, amountPercent = 90), mirroring), 1e-6)
        // volume unknown: starts from half
        val unknown = playing.copy(volume = null)
        assertEquals(0.6, target(AutomationCommand.ChangeVolume(up = true, amountPercent = 10), unknown), 1e-6)
    }

    // ---- quality ----------------------------------------------------------------------------------------------

    @Test
    fun qualityMustBeOneTheCastOffers() {
        assertEquals(call(CastService.ACTION_QUALITY, CastService.EXTRA_QUALITY to "1080p"), step(AutomationCommand.Quality("1080p"), mirroring))
        // case does not matter and the label sent on is the one the cast knows
        assertEquals(call(CastService.ACTION_QUALITY, CastService.EXTRA_QUALITY to "Original"), step(AutomationCommand.Quality("original"), playing))
        val unknown = answer(step(AutomationCommand.Quality("4k"), mirroring))
        assertEquals(AutomationResult.CODE_INVALID, unknown.code)
        assertTrue(unknown.message.contains("480p") && unknown.message.contains("1080p"))
    }

    @Test
    fun qualityOfACastWithoutLevelsIsUnsupported() {
        assertEquals(AutomationResult.CODE_UNSUPPORTED, answer(step(AutomationCommand.Quality("720p"), playing.copy(qualityOptions = emptyList()))).code)
    }

    @Test
    fun qualityMatchesTheFirstWordOfALongLabel() {
        val s = mirroring.copy(qualityOptions = listOf("720p HD", "1080p Full HD"))
        assertEquals(call(CastService.ACTION_QUALITY, CastService.EXTRA_QUALITY to "1080p Full HD"), step(AutomationCommand.Quality("1080p"), s))
    }

    // ---- when nothing (suitable) is cast ----------------------------------------------------------------------

    @Test
    fun controlsWithoutACastAreNotCasting() {
        for (c in listOf(
            AutomationCommand.PlayPause, AutomationCommand.Play, AutomationCommand.Pause, AutomationCommand.Next, AutomationCommand.Previous,
            AutomationCommand.SetVolume(10), AutomationCommand.ChangeVolume(true), AutomationCommand.Mute, AutomationCommand.Quality("720p"),
        )) {
            assertEquals("for $c", AutomationResult.CODE_NOT_CASTING, answer(step(c, idle)).code)
        }
    }

    @Test
    fun stoppingNothingIsFine() {
        val r = answer(step(AutomationCommand.Stop, idle))
        assertEquals(AutomationResult.CODE_OK, r.code)
    }

    @Test
    fun playbackControlsDoNotExistWhileMirroring() {
        for (c in listOf(AutomationCommand.PlayPause, AutomationCommand.Play, AutomationCommand.Pause, AutomationCommand.Next, AutomationCommand.Previous)) {
            assertEquals("for $c", AutomationResult.CODE_UNSUPPORTED, answer(step(c, mirroring)).code)
        }
    }

    @Test
    fun commandsThatStartThingsAreNotServiceControls() {
        assertNull(step(AutomationCommand.Mirror(DeviceRef()), idle))
        assertNull(step(AutomationCommand.CastUrl(DeviceRef(), "https://a.b/c.mp4", "c", "video/mp4"), playing))
        assertNull(step(AutomationCommand.CastFile(DeviceRef(), "content://x/1", null, null), idle))
        assertNull(step(AutomationCommand.Receive(true), idle))
        assertNull(step(AutomationCommand.OpenRemote(DeviceRef()), idle))
    }

    // ---- the watch --------------------------------------------------------------------------------------------

    @Test
    fun everyWatchButtonMapsToACommand() {
        assertEquals(AutomationCommand.PlayPause, ServiceMapping.commandFor(WearCommand.PLAY_PAUSE))
        assertEquals(AutomationCommand.Previous, ServiceMapping.commandFor(WearCommand.PREVIOUS))
        assertEquals(AutomationCommand.Next, ServiceMapping.commandFor(WearCommand.NEXT))
        assertEquals(AutomationCommand.ChangeVolume(up = true), ServiceMapping.commandFor(WearCommand.VOLUME_UP))
        assertEquals(AutomationCommand.ChangeVolume(up = false), ServiceMapping.commandFor(WearCommand.VOLUME_DOWN))
        assertEquals(AutomationCommand.Stop, ServiceMapping.commandFor(WearCommand.STOP))
        assertEquals(AutomationCommand.Mirror(DeviceRef()), ServiceMapping.commandFor(WearCommand.MIRROR_LAST))
        // none is forgotten
        for (c in WearCommand.entries) ServiceMapping.commandFor(c)
    }

    @Test
    fun theWatchVolumeButtonsUseTheRemoteStep() {
        assertEquals(call(CastService.ACTION_VOLUME_UP), step(ServiceMapping.commandFor(WearCommand.VOLUME_UP), playing))
        assertEquals(call(CastService.ACTION_STOP), step(ServiceMapping.commandFor(WearCommand.STOP), playing))
    }
}
