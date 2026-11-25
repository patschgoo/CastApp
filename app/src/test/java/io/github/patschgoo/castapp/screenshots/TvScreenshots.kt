package io.github.patschgoo.castapp.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.tv.ControlsLevel
import io.github.patschgoo.castapp.tv.PendingCast
import io.github.patschgoo.castapp.tv.PlayerButton
import io.github.patschgoo.castapp.tv.PlayerPanel
import io.github.patschgoo.castapp.tv.RemoteState
import io.github.patschgoo.castapp.tv.TvCastQuestion
import io.github.patschgoo.castapp.tv.TvHome
import io.github.patschgoo.castapp.tv.TvPlayerOverlay
import io.github.patschgoo.castapp.tv.TvPlayerUi
import io.github.patschgoo.castapp.tv.TvReceiver
import io.github.patschgoo.castapp.tv.TvReceiverService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** CastApp TV's screens (Fire TV, Android TV): 1920 x 1080, always dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TvScreenshots(private val variant: Variant) {
    @get:Rule val compose = createEmptyComposeRule()

    private fun file(screen: String) = File(Shots.outDir, "$screen/${variant.name}.png")

    @Test fun home() = compose.shootTvHome(variant, file("tv_home"), FakeData.of(variant))

    /** The "Who can cast here" panel, opened from the home screen's first row. */
    @Test fun panel() = compose.shootTvHome(variant, file("tv_panel"), FakeData.of(variant)) {
        val title = ApplicationProvider.getApplicationContext<Application>().getString(R.string.protect_title)
        onAllNodesWithText(title).onFirst().performClick()
    }

    @Test fun question() = compose.shoot(variant, file("tv_question"), theme = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().background(PICTURE)) {
            TvCastQuestion(PendingCast(1, "k", "Pixel 8", "192.168.1.23", 0), more = 0, focus = remember { FocusRequester() }) {}
        }
    }

    @Test fun player() = compose.shoot(variant, file("tv_player"), theme = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().background(PICTURE)) {
            TvPlayerOverlay(film.copy(remote = RemoteState(level = ControlsLevel.BUTTONS, button = PlayerButton.PLAY_PAUSE)))
        }
    }

    @Test fun playerList() = compose.shoot(variant, file("tv_player_list"), theme = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().background(PICTURE)) {
            TvPlayerOverlay(
                film.copy(
                    remote = RemoteState(level = ControlsLevel.PANEL, button = PlayerButton.SUBTITLES, panel = PlayerPanel.SUBTITLES, panelIndex = 1),
                    entries = listOf(if (variant.lang == "de") "Aus" else "Off", "Deutsch", "English · Signs & Songs"),
                    current = 1,
                ),
            )
        }
    }

    @Test fun playerBack() = compose.shoot(variant, file("tv_player_back"), theme = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().background(PICTURE)) { TvPlayerOverlay(film.copy(backHint = true)) }
    }

    private val film = TvPlayerUi(
        buttons = PlayerButton.entries,
        canSeek = true,
        title = "Holiday video 2026.mkv",
        detail = if (variant.lang == "de") "Untertitel: Deutsch · Ton: Japanisch" else "Subtitles: German · Sound: Japanese",
        positionMs = 892_000,
        durationMs = 2_320_000,
        playing = true,
    )

    companion object {
        /** Stands in for the video behind the controls. */
        private val PICTURE = Color(0xFF1B3540)

        /** What the Shield TV Pro (2019) answers: no Hi10P, AV1, DTS or TrueHD. */
        val SHIELD: Map<String, Any> = mapOf(
            "h264" to true, "h264_10bit" to false, "h264_10bit_sw" to false, "hevc" to true, "hevc_10bit" to true,
            "av1" to false, "av1_sw" to false, "vp9" to true, "vp8" to true, "mpeg2" to true, "mpeg4" to false, "mpeg4_sw" to true,
            "aac" to true, "mp3" to true, "ac3" to true, "eac3" to true, "dts" to false, "truehd" to false,
            "flac" to true, "opus" to true, "vorbis" to true,
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.parameters(
            listOf(
                Variant(Device.TV, dark = true, lang = "en"),
                Variant(Device.TV, dark = true, lang = "de"),
                Variant(Device.TV, dark = true, lang = "de", fontScale = 1.5f),
            ),
        )

        /** The TV home, ready, named after [data]'s living room TV. */
        fun ComposeTestRule.shootTvHome(variant: Variant, file: File, data: FakeData, interact: ComposeTestRule.() -> Unit = {}) {
            prepare(variant)
            TvReceiver.setDeviceName(ApplicationProvider.getApplicationContext<Application>(), data.livingRoom.name)
            setFlow(TvReceiverService::class.java, "_running", null, true)
            setFlow(TvReceiverService::class.java, "_address", null, "192.168.1.31:8098")
            try {
                shoot(variant, file, theme = ThemeMode.DARK, interact = interact) { TvHome(SHIELD) }
            } finally {
                // The receiver's state is process-wide: the phone screens must not show "receiving".
                setFlow(TvReceiverService::class.java, "_running", null, false)
                setFlow(TvReceiverService::class.java, "_address", null, null)
            }
        }
    }
}
