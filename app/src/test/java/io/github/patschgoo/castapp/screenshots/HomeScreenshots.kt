package io.github.patschgoo.castapp.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import io.github.patschgoo.castapp.RemoteViewModel
import io.github.patschgoo.castapp.remote.RemoteKey
import io.github.patschgoo.castapp.remote.RemoteSession
import io.github.patschgoo.castapp.remote.RemoteTarget
import io.github.patschgoo.castapp.remote.RemoteUi
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.tv.TvReceiverService
import io.github.patschgoo.castapp.ui.TileHomeContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The main screen (ui/TileHome.kt) in its states. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class HomeScreenshots(private val variant: Variant) {
    @get:Rule val compose = createEmptyComposeRule()

    private val data = FakeData.of(variant)
    private val twoPane get() = variant.device == Device.TABLET

    @Test fun idle() = home("home_idle", active = null)

    @Test fun mirroring() = home("home_mirroring", active = data.mirroring)

    @Test fun castingFile() = home("home_file", active = data.playingFile())

    /** The sheet behind the cast bar: the casting card and the small remote (connected). */
    @Test fun playerSheet() = home("home_player", active = data.playingFile(), remote = true) {
        // On a tablet the player is a pane beside the tiles; on a phone the bar opens it.
        if (!twoPane) openPlayer(data)
    }

    /** "Cast to": the list of TVs. */
    @Test fun picker() = home("home_picker", active = null) {
        if (!twoPane) openPicker(data)
    }

    private fun home(screen: String, active: CastState.Active?, remote: Boolean = false, interact: ComposeTestRule.() -> Unit = {}) =
        compose.shootHome(variant, File(Shots.outDir, "$screen/${variant.name}.png"), data, active, remote = remote, interact = interact)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.parameters()

        fun ComposeTestRule.openPlayer(data: FakeData) = onAllNodesWithText(data.filmTitle).onFirst().performClick()

        /** Taps "Cast to" (it shows the TV in use, or in [audio] mode the speaker). */
        fun ComposeTestRule.openPicker(data: FakeData, audio: Boolean = false) =
            onAllNodesWithText(if (audio) data.kitchen.name else data.livingRoom.name).onFirst().performClick()

        /** The main screen with [data]'s TVs, "Continue watching" filled, and [active] as the running cast. */
        fun ComposeTestRule.shootHome(
            variant: Variant,
            file: File,
            data: FakeData,
            active: CastState.Active?,
            remote: Boolean = false,
            audioMode: Boolean = false,
            showRecent: Boolean = true,
            interact: ComposeTestRule.() -> Unit = {},
        ) = shoot(
            variant, file,
            setup = { activity ->
                // Process-wide state other screenshots may have left behind.
                setFlow(TvReceiverService::class.java, "_running", null, false)
                data.fillHistory(activity)
                if (remote) connectRemote(activity, data)
            },
            interact = interact,
        ) { HomeContent(data, active, audioMode, showRecent) }

        @Composable
        fun HomeContent(data: FakeData, active: CastState.Active?, audioMode: Boolean = false, showRecent: Boolean = true) {
            TileHomeContent(
                devices = data.devices,
                scanning = false,
                active = active,
                snackbar = remember { SnackbarHostState() },
                actions = FakeData.actions(),
                audioMode = audioMode,
                lastUsedId = if (audioMode) data.kitchen.id else data.livingRoom.id,
                showRecent = showRecent,
            )
        }

        /** The remote in the player sheet shows its keys when it is already connected to the TV. */
        fun connectRemote(activity: ComponentActivity, data: FakeData) {
            ViewModelProvider(activity)[RemoteViewModel::class.java].connect(FakeRemoteTarget(data.livingRoom.name))
        }
    }
}

/** The living room TV as the remote sees it: a Google TV that answers every key at once. */
class FakeRemoteTarget(override val name: String) : RemoteTarget {
    override val id = "atv:192.168.1.20"
    override val label = "Google TV"
    override val keys = RemoteKey.entries.toSet()
    override val supportsText = true
    override val supportsVoice = true
    override suspend fun connect(context: Context, ui: RemoteUi): RemoteSession = object : RemoteSession {
        override suspend fun press(key: RemoteKey) {}
        override fun close() {}
    }
}
