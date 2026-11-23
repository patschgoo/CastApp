package io.github.patschgoo.castapp.screenshots

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** A screen size, as Robolectric qualifiers (without language and night mode). */
enum class Device(val size: String, val uiMode: String?, val density: String) {
    /** The phone the layouts are checked on: 411 x 891 dp. */
    PHONE("w411dp-h891dp-port", null, "hdpi"),

    /** Tablet in landscape (1280 x 800 dp): two panes. */
    TABLET("w1280dp-h800dp-land", null, "hdpi"),

    /** CastApp TV: 1920 x 1080 px, 960 x 540 dp. */
    TV("w960dp-h540dp-land", "television", "xhdpi"),

    /** Store and README pictures: 1080 x 2400 px (360 x 800 dp at 3x). */
    STORE("w360dp-h800dp-port", null, "xxhdpi"),
}

/** One look of the app: size, light or dark, language, font size. */
data class Variant(val device: Device, val dark: Boolean, val lang: String, val fontScale: Float = 1f) {
    val name: String =
        listOf(device.name.lowercase(), if (dark) "dark" else "light", lang).joinToString("_") +
            if (fontScale != 1f) "_font" + fontScale.toString().removeSuffix(".0") else ""

    val qualifiers: String
        get() = listOfNotNull(
            if (lang == "de") "de-rDE" else "en-rUS",
            device.size,
            device.uiMode,
            if (dark) "night" else "notnight",
            device.density,
        ).joinToString("-")

    override fun toString() = name

    companion object {
        /**
         * The looks every screen is checked in. Not every combination: the ones that find
         * problems (German and a large font are the longest texts) plus both themes.
         */
        val checks = listOf(
            Variant(Device.PHONE, dark = false, lang = "en"),
            Variant(Device.PHONE, dark = true, lang = "en"),
            Variant(Device.PHONE, dark = false, lang = "de"),
            Variant(Device.PHONE, dark = false, lang = "en", fontScale = 2f),
            Variant(Device.PHONE, dark = true, lang = "de", fontScale = 2f),
            Variant(Device.TABLET, dark = false, lang = "en"),
            Variant(Device.TABLET, dark = true, lang = "de", fontScale = 2f),
        )

        fun parameters(list: List<Variant> = checks): List<Array<Any>> = list.map { arrayOf<Any>(it) }
    }
}

/** Where the pictures go and which ones are wanted (`-Pscreenshots`, `-Pscreenshots=store`). */
object Shots {
    private val repo: File = File(System.getProperty("castapp.repoDir") ?: File("").absoluteFile.parent)

    /** Layout checks: app/build/outputs/roborazzi/<screen>/<variant>.png */
    val outDir: File = File(repo, "app/build/outputs/roborazzi")

    /** True with `-Pscreenshots=store`: also write the README and store pictures into the repository. */
    val store: Boolean get() = System.getProperty("castapp.screenshots") == "store"

    fun repoFile(path: String): File = File(repo, path)
}

/**
 * Shows [content] in an activity with [variant]'s size, language, theme and font size, lets
 * [interact] tap through it, and saves the whole screen (dialogs and bottom sheets included).
 */
fun ComposeTestRule.shoot(
    variant: Variant,
    file: File,
    theme: ThemeMode? = null,
    /** Runs on the activity before the content is set (view models, fake state). */
    setup: (ComponentActivity) -> Unit = {},
    interact: ComposeTestRule.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    prepare(variant)
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            setup(activity)
            activity.setContent {
                CastAppTheme(theme ?: if (variant.dark) ThemeMode.DARK else ThemeMode.LIGHT) { content() }
            }
        }
        settle()
        interact()
        settle()
        file.parentFile?.mkdirs()
        captureScreenRoboImage(file.path)
        Audit.write(this, file)
    }
}

/** Like [shoot], for one of the app's own activities (its theme follows the night qualifier). */
fun <A : android.app.Activity> ComposeTestRule.shootActivity(
    variant: Variant,
    file: File,
    activity: Class<A>,
    setup: (A) -> Unit = {},
    interact: ComposeTestRule.() -> Unit = {},
) {
    prepare(variant)
    ActivityScenario.launch(activity).use { scenario ->
        scenario.onActivity { setup(it) }
        settle()
        interact()
        settle()
        file.parentFile?.mkdirs()
        captureScreenRoboImage(file.path)
        Audit.write(this, file)
    }
}

/** Sets a private `MutableStateFlow` field (fake discovery results, receiver state). */
@Suppress("UNCHECKED_CAST")
fun <T> setFlow(owner: Class<*>, field: String, target: Any?, value: T) {
    val f = owner.getDeclaredField(field)
    f.isAccessible = true
    (f.get(target) as kotlinx.coroutines.flow.MutableStateFlow<T>).value = value
}

/** Sets size, language and font size before an activity starts. */
fun prepare(variant: Variant) {
    FakeKeyStore.install()
    RuntimeEnvironment.setQualifiers(variant.qualifiers)
    RuntimeEnvironment.setFontScale(variant.fontScale)
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    // The plain activity the screens are shown in isn't in the app's manifest.
    shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
}

/** Lets coroutines, the main looper and Compose catch up (network fakes answer on other threads). */
fun ComposeTestRule.settle(rounds: Int = 3) {
    repeat(rounds) {
        waitForIdle()
        ShadowLooper.idleMainLooper()
        Thread.sleep(30)
    }
    waitForIdle()
}

/** Waits (real time) until [condition] holds, for network fakes and background loading. */
fun ComposeTestRule.waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
    waitUntil(timeoutMs) {
        ShadowLooper.idleMainLooper()
        condition()
    }
}
