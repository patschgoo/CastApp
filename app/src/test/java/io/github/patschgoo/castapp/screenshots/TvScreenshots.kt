package io.github.patschgoo.castapp.screenshots

import android.app.Application
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.tv.TvHome
import io.github.patschgoo.castapp.tv.TvReceiver
import io.github.patschgoo.castapp.tv.TvReceiverService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** CastApp TV's home screen (Fire TV, Android TV): 1920 x 1080, always dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TvScreenshots(private val variant: Variant) {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun home() = compose.shootTvHome(variant, File(Shots.outDir, "tv_home/${variant.name}.png"), FakeData.of(variant))

    companion object {
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
        fun ComposeTestRule.shootTvHome(variant: Variant, file: File, data: FakeData) {
            prepare(variant)
            TvReceiver.setDeviceName(ApplicationProvider.getApplicationContext<Application>(), data.livingRoom.name)
            setFlow(TvReceiverService::class.java, "_running", null, true)
            setFlow(TvReceiverService::class.java, "_address", null, "192.168.1.31:8098")
            try {
                shoot(variant, file, theme = ThemeMode.DARK) { TvHome() }
            } finally {
                // The receiver's state is process-wide: the phone screens must not show "receiving".
                setFlow(TvReceiverService::class.java, "_running", null, false)
                setFlow(TvReceiverService::class.java, "_address", null, null)
            }
        }
    }
}
