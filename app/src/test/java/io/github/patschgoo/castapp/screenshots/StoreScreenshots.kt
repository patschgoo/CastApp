package io.github.patschgoo.castapp.screenshots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.patschgoo.castapp.screenshots.HomeScreenshots.Companion.openPicker
import io.github.patschgoo.castapp.screenshots.HomeScreenshots.Companion.openPlayer
import io.github.patschgoo.castapp.screenshots.HomeScreenshots.Companion.shootHome
import io.github.patschgoo.castapp.screenshots.ScreensScreenshots.Companion.app
import io.github.patschgoo.castapp.screenshots.ScreensScreenshots.Companion.shootChannels
import io.github.patschgoo.castapp.screenshots.ScreensScreenshots.Companion.shootJellyfin
import io.github.patschgoo.castapp.screenshots.ScreensScreenshots.Companion.shootNetwork
import io.github.patschgoo.castapp.screenshots.ScreensScreenshots.Companion.shootRemote
import io.github.patschgoo.castapp.screenshots.TvScreenshots.Companion.shootTvHome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The pictures for the app stores (fastlane/metadata/android/<language>/images/phoneScreenshots)
 * and the README (docs/), 1080 x 2400, light and dark mixed, in English and German.
 * With `-Pscreenshots=store` they are written into the repository; otherwise to
 * app/build/outputs/roborazzi/store/ to look at first.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StoreScreenshots(private val lang: String) {
    @get:Rule val compose = createEmptyComposeRule()

    private val data = FakeData(lang == "de")
    private var server: FakeServer? = null

    @After
    fun stop() {
        server?.close()
    }

    private fun phone(dark: Boolean) = Variant(Device.STORE, dark, lang)
    private fun server() = FakeServer(app(), lang == "de").also { server = it }

    /** Where picture [number] goes; [readme]: the English one is also the README's docs/<readme>.png. */
    private fun target(number: Int, name: String, readme: String? = null, block: (File) -> Unit) {
        val folder = if (lang == "de") "de-DE" else "en-US"
        val file = if (Shots.store) Shots.repoFile("fastlane/metadata/android/$folder/images/phoneScreenshots/$number.png")
        else File(Shots.outDir, "store/$folder/${number}_$name.png")
        block(file)
        if (readme != null && lang == "en" && Shots.store) {
            Files.copy(file.toPath(), Shots.repoFile("docs/$readme.png").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Test fun home() = target(1, "home", readme = "home") {
        compose.shootHome(phone(dark = false), it, data, active = null, showRecent = false, history = false)
    }

    @Test fun player() = target(2, "player", readme = "quality_subtitles") {
        compose.shootHome(phone(dark = false), it, data, data.playingFile(subtitles = true), remote = true, showRecent = false) { openPlayer(data) }
    }

    @Test fun posters() = target(3, "jellyfin", readme = "jellyfin") {
        compose.shootJellyfin(phone(dark = true), it, server(), posters = true)
    }

    @Test fun guide() = target(4, "tv_channels", readme = "tv_channels") {
        compose.shootChannels(phone(dark = false), it, server())
    }

    @Test fun speakers() = target(5, "audio_mode", readme = "audio_mode") {
        compose.shootHome(phone(dark = true), it, data, active = null, audioMode = true, showRecent = false) { openPicker(data, audio = true) }
    }

    @Test fun network() = target(6, "network", readme = "network") {
        compose.shootNetwork(phone(dark = false), it, server())
    }

    @Test fun remote() = target(7, "remote") {
        compose.shootRemote(phone(dark = true), it, data)
    }

    @Test fun tv() = target(8, "tv_home", readme = "tv_home") {
        compose.shootTvHome(Variant(Device.TV, dark = true, lang = lang), it, data)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages() = listOf(arrayOf<Any>("en"), arrayOf<Any>("de"))
    }
}
