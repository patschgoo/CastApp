package io.github.patschgoo.castapp.screenshots

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.ChannelsScreen
import io.github.patschgoo.castapp.NetworkScreen
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.RemoteActivity
import io.github.patschgoo.castapp.RemoteViewModel
import io.github.patschgoo.castapp.mediaservers.MediaServers
import io.github.patschgoo.castapp.mediaservers.ServerAccount
import io.github.patschgoo.castapp.mediaservers.ServerKind
import io.github.patschgoo.castapp.network.MediaServerInfo
import io.github.patschgoo.castapp.network.NetworkSource
import io.github.patschgoo.castapp.network.NetworkSources
import io.github.patschgoo.castapp.network.SourceKind
import io.github.patschgoo.castapp.ui.SettingsScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings, the remote, Network & cloud, Jellyfin and the TV guide. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreensScreenshots(private val variant: Variant) {
    @get:Rule val compose = createEmptyComposeRule()

    private var server: FakeServer? = null

    @After
    fun stop() {
        server?.close()
    }

    private fun file(screen: String) = File(Shots.outDir, "$screen/${variant.name}.png")
    private fun server(): FakeServer = FakeServer(app(), variant.lang == "de").also { server = it }

    @Test fun settings() = compose.shootSettings(variant, file("settings"))

    @Test fun settingsSearch() = compose.shootSettings(variant, file("settings_search"), search = if (variant.lang == "de") "Untertitel" else "subtitle")

    @Test fun remote() = compose.shootRemote(variant, file("remote"), FakeData.of(variant))

    @Test fun network() = compose.shootNetwork(variant, file("network"), server())

    @Test fun jellyfinHome() = compose.shootJellyfin(variant, file("jellyfin_home"), server(), posters = false)

    @Test fun jellyfinPosters() = compose.shootJellyfin(variant, file("jellyfin_posters"), server(), posters = true)

    @Test fun channelsGrid() = compose.shootChannels(variant, file("channels_grid"), server())

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.parameters()

        fun app(): CastApp = ApplicationProvider.getApplicationContext<Application>() as CastApp

        /** Settings, from the top; with [search] typed into the search field. */
        fun ComposeTestRule.shootSettings(variant: Variant, file: File, search: String? = null) = shoot(
            variant, file,
            interact = { if (search != null) onNode(hasSetTextAction()).performTextInput(search) },
        ) {
            SettingsScreen(app().settings, audioPermissionGranted = true, onRequestAudioPermission = {}, onBack = {})
        }

        /** The full remote, connected to the living room TV. */
        fun ComposeTestRule.shootRemote(variant: Variant, file: File, data: FakeData) = shootActivity(
            variant, file, RemoteActivity::class.java,
            setup = { ViewModelProvider(it)[RemoteViewModel::class.java].connect(FakeRemoteTarget(data.livingRoom.name)) },
        )

        /** Network & cloud with Plex and Jellyfin signed in, two servers found and two saved folders. */
        fun ComposeTestRule.shootNetwork(variant: Variant, file: File, server: FakeServer) {
            prepare(variant)
            fakeNetwork(app().network, server.url)
            shoot(variant, file) { NetworkScreen(app().network, onBack = {}) }
        }

        /** Jellyfin: Continue watching, Next up and the libraries, or ([posters]) the film library. */
        fun ComposeTestRule.shootJellyfin(variant: Variant, file: File, server: FakeServer, posters: Boolean) {
            prepare(variant)
            signInJellyfin(server.url)
            shoot(
                variant, file,
                interact = {
                    waitFor { onAllNodes(hasText(FakeServer.ACCOUNT_NAME)).fetchSemanticsNodes().isNotEmpty() }
                    onAllNodesWithText(FakeServer.ACCOUNT_NAME).onFirst().performClick()
                    val movies = server.libraries[0].name
                    waitFor { onAllNodes(hasText(movies)).fetchSemanticsNodes().isNotEmpty() }
                    waitForPictures(server, 5)
                    if (posters) {
                        onAllNodesWithText(movies).onFirst().performClick()
                        waitFor { onAllNodes(hasText(FakeServer.MOVIES[0].title)).fetchSemanticsNodes().isNotEmpty() }
                        waitForPictures(server, 5 + 9)
                    }
                },
            ) {
                NetworkScreen(app().network, onBack = {}, startServers = ServerKind.JELLYFIN.name)
            }
        }

        /** TV channels: the guide as a grid of programmes. */
        fun ComposeTestRule.shootChannels(variant: Variant, file: File, server: FakeServer) {
            prepare(variant)
            app().iptv.add("Demo TV", "${server.url}/iptv/list.m3u")
            shoot(
                variant, file,
                interact = {
                    val grid = app().getString(R.string.iptvx_view_grid)
                    waitFor(20_000) { onAllNodes(hasText(grid)).fetchSemanticsNodes().isNotEmpty() }
                    onAllNodesWithText(grid).onFirst().performClick()
                    waitForPictures(server, 6)
                },
            ) {
                ChannelsScreen(app().iptv, onBack = {})
            }
        }

        /** Waits until [count] pictures were downloaded, then a moment for them to be drawn. */
        fun ComposeTestRule.waitForPictures(server: FakeServer, count: Int) {
            runCatching { waitFor(8_000) { server.picturesServed.get() >= count } }
            Thread.sleep(400)
            settle()
        }

        private fun signInJellyfin(url: String) {
            MediaServers.get(app()).add(
                ServerAccount(
                    id = "jf-demo", kind = ServerKind.JELLYFIN, name = FakeServer.ACCOUNT_NAME, baseUrl = url,
                    token = "demo-token", userId = "u1", userName = "alex", serverId = "srv-demo",
                ),
            )
        }

        /** What Network & cloud lists: signed in to Plex and Jellyfin, servers found on the Wi-Fi, saved folders. */
        private fun fakeNetwork(network: NetworkSources, url: String) {
            MediaServers.get(app()).savePlexSignIn("demo-token", "alex")
            signInJellyfin(url)
            setFlow(
                NetworkSources::class.java, "_servers", network,
                listOf(
                    MediaServerInfo("dlna-nas", "Family NAS (MiniDLNA)", "$url/dlna/nas", null),
                    MediaServerInfo("dlna-pc", "Office PC · Windows Media", "$url/dlna/pc", null),
                ),
            )
            network.add(NetworkSource("", SourceKind.SMB, "Family NAS", "192.168.1.10", share = "Media", folder = "Films"))
            network.add(NetworkSource("", SourceKind.WEBDAV, "Nextcloud", "https://cloud.example.com/remote.php/dav/files/alex/Videos"))
        }
    }
}
