package io.github.patschgoo.castapp.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** What the remote's buttons do in CastApp TV's player. */
class TvRemoteTest {
    private val film = RemoteContext(canSeek = true, canPause = true, subtitleChoices = 3, soundChoices = 2)
    private val plainFilm = RemoteContext(canSeek = true, canPause = true)
    private val mirror = RemoteContext(canSeek = false, canPause = false)

    private var state = RemoteState()
    private var now = 1_000L

    private fun press(key: TvKey, context: RemoteContext = film, repeat: Int = 0, selected: (PlayerPanel) -> Int = { 0 }): List<RemoteAction> {
        val (next, actions) = TvRemote.press(state, key, now, context, repeat, selected)
        state = next
        return actions
    }

    @Test
    fun okPausesAndShowsTheControls() {
        assertEquals(listOf(RemoteAction.TogglePlay), press(TvKey.OK))
        assertEquals(ControlsLevel.BAR, state.level)
        assertEquals(listOf(RemoteAction.TogglePlay), press(TvKey.OK))
    }

    @Test
    fun leftAndRightJumpThroughTheFile() {
        assertEquals(listOf(RemoteAction.SeekBy(-10_000)), press(TvKey.LEFT))
        assertEquals(ControlsLevel.BAR, state.level)
        assertEquals(listOf(RemoteAction.SeekBy(10_000)), press(TvKey.RIGHT))
        assertEquals(listOf(RemoteAction.SeekBy(30_000)), press(TvKey.RIGHT, repeat = 6))
        assertEquals(listOf(RemoteAction.SeekBy(-60_000)), press(TvKey.LEFT, repeat = 25))
    }

    @Test
    fun downGoesToTheButtonsAndUpBackToTheBar() {
        press(TvKey.DOWN)
        assertEquals(ControlsLevel.BUTTONS, state.level)
        assertEquals(PlayerButton.PLAY_PAUSE, state.button)
        // Left and Right now move between the buttons instead of through the file.
        assertEquals(emptyList<RemoteAction>(), press(TvKey.RIGHT))
        assertEquals(PlayerButton.SUBTITLES, state.button)
        press(TvKey.RIGHT)
        press(TvKey.RIGHT)
        assertEquals(PlayerButton.STOP, state.button)
        press(TvKey.RIGHT)
        assertEquals(PlayerButton.STOP, state.button)
        press(TvKey.UP)
        assertEquals(ControlsLevel.BAR, state.level)
        assertEquals(listOf(RemoteAction.SeekBy(10_000)), press(TvKey.RIGHT))
    }

    @Test
    fun buttonsWithNothingToChooseAreLeftOut() {
        assertEquals(listOf(PlayerButton.PLAY_PAUSE, PlayerButton.STOP), plainFilm.buttons)
        assertEquals(listOf(PlayerButton.STOP), mirror.buttons)
        assertEquals(listOf(PlayerButton.PLAY_PAUSE, PlayerButton.SUBTITLES, PlayerButton.SOUND, PlayerButton.STOP), film.buttons)
        // One sound track, or only "Off": no list.
        assertEquals(listOf(PlayerButton.PLAY_PAUSE, PlayerButton.STOP), RemoteContext(true, true, subtitleChoices = 1, soundChoices = 1).buttons)
    }

    @Test
    fun aListOpensOnTheTrackInUseAndPicksWithOk() {
        press(TvKey.DOWN)
        press(TvKey.RIGHT)
        assertEquals(emptyList<RemoteAction>(), press(TvKey.OK) { 2 })
        assertEquals(ControlsLevel.PANEL, state.level)
        assertEquals(PlayerPanel.SUBTITLES, state.panel)
        assertEquals(2, state.panelIndex)
        press(TvKey.DOWN)
        assertEquals("stops at the last entry", 2, state.panelIndex)
        press(TvKey.UP)
        press(TvKey.UP)
        press(TvKey.UP)
        assertEquals(0, state.panelIndex)
        assertEquals(listOf(RemoteAction.PickSubtitle(0)), press(TvKey.OK))
        assertEquals(ControlsLevel.BUTTONS, state.level)
        assertNull(state.panel)
    }

    @Test
    fun theSoundListPicksASoundTrack() {
        press(TvKey.DOWN)
        press(TvKey.RIGHT)
        press(TvKey.RIGHT)
        assertEquals(PlayerButton.SOUND, state.button)
        press(TvKey.OK)
        press(TvKey.DOWN)
        assertEquals(listOf(RemoteAction.PickSound(1)), press(TvKey.OK))
    }

    @Test
    fun backClosesOneStepAtATime() {
        press(TvKey.DOWN)
        press(TvKey.RIGHT)
        press(TvKey.OK)
        assertEquals(ControlsLevel.PANEL, state.level)
        assertEquals(emptyList<RemoteAction>(), press(TvKey.BACK))
        assertEquals(ControlsLevel.BUTTONS, state.level)
        assertEquals(emptyList<RemoteAction>(), press(TvKey.BACK))
        assertEquals(ControlsLevel.HIDDEN, state.level)
        assertFalse("closing the controls doesn't arm the second Back", state.backHint(now))
    }

    @Test
    fun backAsksOnceBeforeItEndsTheCast() {
        assertEquals(emptyList<RemoteAction>(), press(TvKey.BACK))
        assertTrue(state.backHint(now))
        now += TvRemote.BACK_AGAIN_MS - 1
        assertEquals(listOf(RemoteAction.Stop), press(TvKey.BACK))
    }

    @Test
    fun aLateSecondBackAsksAgain() {
        press(TvKey.BACK)
        now += TvRemote.BACK_AGAIN_MS + 1
        assertFalse(state.backHint(now))
        assertEquals(emptyList<RemoteAction>(), press(TvKey.BACK))
        assertTrue(state.backHint(now))
    }

    @Test
    fun anotherKeyCallsTheBackQuestionOff() {
        press(TvKey.BACK)
        press(TvKey.OK)
        press(TvKey.BACK) // closes the controls
        assertEquals(ControlsLevel.HIDDEN, state.level)
        assertEquals(emptyList<RemoteAction>(), press(TvKey.BACK))
    }

    @Test
    fun stopOnTheControlsEndsTheCast() {
        press(TvKey.DOWN, plainFilm)
        press(TvKey.RIGHT, plainFilm)
        assertEquals(listOf(RemoteAction.Stop), press(TvKey.OK, plainFilm))
    }

    @Test
    fun aMirroredScreenHasOnlyStop() {
        assertEquals(emptyList<RemoteAction>(), press(TvKey.OK, mirror))
        assertEquals("nothing to wind: straight to the buttons", ControlsLevel.BUTTONS, state.level)
        assertEquals(PlayerButton.STOP, state.button)
        assertEquals(emptyList<RemoteAction>(), press(TvKey.LEFT, mirror))
        assertEquals(emptyList<RemoteAction>(), press(TvKey.UP, mirror))
        assertEquals(ControlsLevel.BUTTONS, state.level)
        assertEquals(emptyList<RemoteAction>(), press(TvKey.PLAY_PAUSE, mirror))
        assertEquals(listOf(RemoteAction.Stop), press(TvKey.OK, mirror))
    }

    @Test
    fun theRemotesMediaButtonsWorkEverywhere() {
        assertEquals(listOf(RemoteAction.Pause), press(TvKey.PAUSE))
        assertEquals(ControlsLevel.BAR, state.level)
        press(TvKey.DOWN)
        assertEquals(listOf(RemoteAction.SeekBy(10_000)), press(TvKey.FORWARD))
        assertEquals("the focus stays where it was", ControlsLevel.BUTTONS, state.level)
        assertEquals(listOf(RemoteAction.SeekBy(-10_000)), press(TvKey.REWIND))
        assertEquals(listOf(RemoteAction.Play), press(TvKey.PLAY))
        assertEquals(listOf(RemoteAction.TogglePlay), press(TvKey.PLAY_PAUSE))
        assertEquals(listOf(RemoteAction.Stop), press(TvKey.STOP))
    }

    @Test
    fun theControlsGoAwayWhileItPlaysAndStayWhilePaused() {
        press(TvKey.OK)
        now += TvRemote.SHOW_MS - 1
        assertEquals(ControlsLevel.BAR, TvRemote.tick(state, now, playing = true).level)
        now += 2
        assertEquals(ControlsLevel.BAR, TvRemote.tick(state, now, playing = false).level)
        assertEquals(ControlsLevel.HIDDEN, TvRemote.tick(state, now, playing = true).level)
    }

    @Test
    fun anOpenListStaysUntilItIsClosed() {
        press(TvKey.DOWN)
        press(TvKey.RIGHT)
        press(TvKey.OK)
        now += 60_000
        assertEquals(ControlsLevel.PANEL, TvRemote.tick(state, now, playing = true).level)
    }

    @Test
    fun theStateFollowsMediaThatChangesUnderIt() {
        press(TvKey.DOWN)
        press(TvKey.RIGHT)
        press(TvKey.OK)
        // The next file has no subtitles: the list is gone, the button too.
        assertEquals(emptyList<RemoteAction>(), press(TvKey.DOWN, plainFilm))
        assertEquals(ControlsLevel.BUTTONS, state.level)
        assertEquals(PlayerButton.PLAY_PAUSE, state.button)
    }

    @Test
    fun trackNames() {
        val en = Locale.ENGLISH
        assertEquals("German", TrackNames.name("de", null, "Track 1", en))
        assertEquals("Japanese · Commentary", TrackNames.name("ja", "Commentary", "Track 2", en))
        assertEquals("German", TrackNames.name("de", "german", "Track 1", en))
        assertEquals("Signs", TrackNames.name("und", "Signs", "Track 3", en))
        assertEquals("Track 4", TrackNames.name(null, " ", "Track 4", en))
        assertEquals("Deutsch", TrackNames.name("de", null, "Spur 1", Locale.GERMAN))
    }

    @Test
    fun clock() {
        assertEquals("0:00", playerClock(-5))
        assertEquals("4:07", playerClock(247_900))
        assertEquals("1:02:45", playerClock(3_765_000))
    }

    @Test
    fun whatTheTvPlays() {
        // The user's Shield (2019), as its /castapp/capabilities answered.
        val shield = mapOf<String, Any>(
            "h264" to true, "h264_sw" to false, "h264_10bit" to false, "h264_10bit_sw" to false,
            "hevc" to true, "hevc_10bit" to true, "av1" to false, "av1_sw" to false,
            "vp9" to true, "mpeg4" to false, "mpeg4_sw" to true,
            "aac" to true, "ac3" to true, "dts" to false, "truehd" to false,
            "sub_srt" to true, "container_mkv" to true,
        )
        assertEquals(listOf("Hi10P", "AV1", "DTS", "TrueHD"), TvPlayable.missing(shield))
        val video = TvPlayable.video(shield)
        assertEquals(listOf("H.264", "HEVC (H.265)", "HEVC 10-bit", "VP9"), video.plays)
        assertEquals(listOf("MPEG-4 (DivX, Xvid)"), video.slow)
        assertEquals(listOf("H.264 10-bit (Hi10P)", "AV1"), video.not)
        assertEquals(listOf("AAC", "Dolby Digital (AC-3)"), TvPlayable.sound(shield).plays)
        assertEquals(emptyList<String>(), TvPlayable.missing(emptyMap()))
    }
}
