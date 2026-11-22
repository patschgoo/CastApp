package io.github.patschgoo.castapp.remote.net

import com.sun.net.httpserver.HttpServer
import io.github.patschgoo.castapp.remote.RemoteKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** Roku ECP key presses against a fake Roku. */
class RokuRemoteTest {
    private val requests = ArrayList<String>()
    @Volatile private var powerMode = "PowerOn"
    @Volatile private var blocked = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            synchronized(requests) { requests += ex.requestMethod + " " + ex.requestURI.rawPath }
            val body = if (ex.requestURI.path == "/query/device-info") {
                """<?xml version="1.0" encoding="UTF-8" ?><device-info><serial-number>YN00AB</serial-number><vendor-name>TCL</vendor-name>""" +
                    """<model-name>65S425</model-name><is-tv>true</is-tv><user-device-name>Living &amp; Room</user-device-name>""" +
                    """<power-mode>$powerMode</power-mode></device-info>"""
            } else {
                ""
            }.toByteArray()
            ex.sendResponseHeaders(if (blocked) 403 else 200, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        start()
    }
    private val client = RokuRemoteClient("127.0.0.1", server.address.port)

    @After
    fun tearDown() = server.stop(0)

    private fun posts() = synchronized(requests) { requests.filter { it.startsWith("POST") }.also { requests.clear() } }

    @Test
    fun keyNames() {
        val expected = mapOf(
            RemoteKey.UP to "Up", RemoteKey.DOWN to "Down", RemoteKey.LEFT to "Left", RemoteKey.RIGHT to "Right",
            RemoteKey.OK to "Select", RemoteKey.BACK to "Back", RemoteKey.HOME to "Home", RemoteKey.MENU to "Info",
            RemoteKey.PLAY_PAUSE to "Play", RemoteKey.REWIND to "Rev", RemoteKey.FAST_FORWARD to "Fwd",
            RemoteKey.VOLUME_UP to "VolumeUp", RemoteKey.VOLUME_DOWN to "VolumeDown", RemoteKey.MUTE to "VolumeMute",
            RemoteKey.CHANNEL_UP to "ChannelUp", RemoteKey.CHANNEL_DOWN to "ChannelDown",
        )
        for ((key, name) in expected) {
            client.press(key)
            assertEquals(listOf("POST /keypress/$name"), posts())
        }
        // Players can't change the TV's volume or channel, and have no power key.
        val player = RokuRemoteClient.keys(isTv = false)
        assertFalse(RemoteKey.VOLUME_UP in player)
        assertFalse(RemoteKey.POWER in player)
        assertTrue(RemoteKey.OK in player)
        val tv = RokuRemoteClient.keys(isTv = true)
        assertTrue(RemoteKey.POWER in tv && RemoteKey.MUTE in tv && RemoteKey.CHANNEL_DOWN in tv)
        assertFalse(RemoteKey.NUM_1 in tv)
        assertFalse(RemoteKey.INPUT in tv)
    }

    @Test
    fun powerFollowsState() {
        powerMode = "PowerOn"
        client.press(RemoteKey.POWER)
        assertEquals(listOf("POST /keypress/PowerOff"), posts())
        powerMode = "DisplayOff"
        client.press(RemoteKey.POWER)
        assertEquals(listOf("POST /keypress/PowerOn"), posts())
    }

    @Test
    fun typesLiterals() {
        client.type("a b&ü")
        assertEquals(
            listOf("POST /keypress/Lit_a", "POST /keypress/Lit_%20", "POST /keypress/Lit_b", "POST /keypress/Lit_%26", "POST /keypress/Lit_%C3%BC"),
            posts(),
        )
    }

    @Test
    fun deviceInfoAndBlocked() {
        val info = client.info()!!
        assertEquals("Living & Room", info.name)
        assertEquals("YN00AB", info.serial)
        assertTrue(info.isTv)
        assertEquals("TCL 65S425", info.model)
        blocked = true
        try {
            client.press(RemoteKey.HOME)
            throw AssertionError("not blocked")
        } catch (_: RokuBlockedException) {
        }
    }
}
