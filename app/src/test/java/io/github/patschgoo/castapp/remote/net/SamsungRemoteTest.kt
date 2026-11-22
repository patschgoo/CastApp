package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.remote.RemoteKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Samsung messages (checked against what samsungtvws 3.0.7 sends) and the connect flow
 * against a fake TV.
 */
class SamsungRemoteTest {
    private fun same(expected: String, actual: String) = assertTrue("$expected\n!=\n$actual", jsonEquals(JSONObject(expected), JSONObject(actual)))

    private fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { jsonEquals(a.get(it), b.get(it)) }
        a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() && (0 until a.length()).all { jsonEquals(a.get(it), b.get(it)) }
        else -> a == b
    }

    @Test
    fun messagesMatchSamsungtvws() {
        same(
            """{"method": "ms.remote.control", "params": {"Cmd": "Click", "DataOfCmd": "KEY_VOLUP", "Option": "false", "TypeOfRemote": "SendRemoteKey"}}""",
            SamsungProtocol.click("KEY_VOLUP"),
        )
        same(
            """{"method": "ms.remote.control", "params": {"Cmd": "SMOkbGxvIFdlbHQ=", "DataOfCmd": "base64", "TypeOfRemote": "SendInputString"}}""",
            SamsungProtocol.inputString("Hällo Welt"),
        )
        same("""{"method": "ms.remote.control", "params": {"TypeOfRemote": "SendInputEnd"}}""", SamsungProtocol.inputEnd())
        assertEquals(
            "wss://192.168.1.30:8002/api/v2/channels/samsung.remote.control?name=Q2FzdEFwcA%3D%3D&token=12345678",
            SamsungProtocol.url("192.168.1.30", secure = true, appName = "CastApp", token = "12345678"),
        )
        assertEquals(
            "ws://192.168.1.30:8001/api/v2/channels/samsung.remote.control?name=Q2FzdEFwcA%3D%3D",
            SamsungProtocol.url("192.168.1.30", secure = false, appName = "CastApp", token = "12345678"),
        )
        assertEquals("KEY_RETURN", SamsungProtocol.keys[RemoteKey.BACK])
        assertEquals("KEY_SOURCE", SamsungProtocol.keys[RemoteKey.INPUT])
        assertEquals("KEY_7", SamsungProtocol.keys[RemoteKey.NUM_7])
        assertFalse(RemoteKey.SETTINGS in SamsungProtocol.keys)
    }

    @Test
    fun parsesDeviceInfo() {
        val info = SamsungProtocol.parseInfo(
            """{"device":{"FrameTVSupport":"false","TokenAuthSupport":"true","name":"[TV] Living Room","modelName":"QE55Q60TAU",
               "type":"Samsung SmartTV","id":"uuid:1234"},"id":"uuid:1234","name":"[TV] Living Room","type":"Samsung SmartTV",
               "uri":"http://192.168.1.30:8001/api/v2/","version":"2.0.25"}""",
        )!!
        assertEquals("[TV] Living Room", info.name)
        assertEquals("QE55Q60TAU", info.model)
        assertTrue(info.tokenAuth)
        assertNull(SamsungProtocol.parseInfo("""{"hello":"world"}"""))
        assertNull(SamsungProtocol.parseInfo("not json"))
    }

    @Test
    fun allowsThenSendsKeys() = runBlocking {
        val got = LinkedBlockingQueue<String>()
        var path = ""
        val server = FakeWsServer(secure = true) { peer ->
            path = peer.path
            // The TV shows "Allow?" and answers a moment later.
            Thread.sleep(3000)
            peer.sendText("""{"event":"ms.channel.connect","data":{"clients":[],"id":"x","token":"98765"}}""")
            peer.sendText("""{"event":"ms.remote.imeStart","data":""}""")
            while (true) got.put(peer.readText() ?: break)
        }
        server.use {
            var prompted = false
            val client = SamsungRemoteClient("127.0.0.1", "CastApp", urlFor = { secure, token ->
                assertTrue(secure)
                "wss://127.0.0.1:${server.port}/api/v2/channels/samsung.remote.control?name=x" + (token?.let { "&token=$it" } ?: "")
            })
            val conn = client.connect(token = "old", onPrompt = { prompted = true })
            assertTrue(prompted)
            assertEquals("98765", conn.token)
            assertTrue(path.endsWith("&token=old"))
            conn.press(RemoteKey.VOLUME_UP)
            conn.press(RemoteKey.PLAY_PAUSE)
            conn.press(RemoteKey.PLAY_PAUSE)
            conn.type("abc")
            same(SamsungProtocol.click("KEY_VOLUP"), got.poll(5, TimeUnit.SECONDS)!!)
            same(SamsungProtocol.click("KEY_PAUSE"), got.poll(5, TimeUnit.SECONDS)!!)
            same(SamsungProtocol.click("KEY_PLAY"), got.poll(5, TimeUnit.SECONDS)!!)
            same(SamsungProtocol.inputString("abc"), got.poll(5, TimeUnit.SECONDS)!!)
            same(SamsungProtocol.inputEnd(), got.poll(5, TimeUnit.SECONDS)!!)
            assertTrue(conn.keyboardOpen)
            conn.close()
        }
        assertTrue(server.errors.toString(), server.errors.isEmpty())
    }

    @Test
    fun deniedAndPlainFallback() = runBlocking {
        FakeWsServer { peer -> peer.sendText("""{"event":"ms.channel.unauthorized","data":{}}"""); peer.readText() }.use { server ->
            val client = SamsungRemoteClient("127.0.0.1", "CastApp", securePort = false, urlFor = { _, _ -> "ws://127.0.0.1:${server.port}/" })
            try {
                client.connect(null, onPrompt = {})
                throw AssertionError("connected")
            } catch (_: RemoteDeniedException) {
            }
        }
        // No secure port (an older Tizen TV): falls back to ws:// on 8001.
        val closedPort = ServerSocket(0).use { it.localPort }
        FakeWsServer { peer -> peer.sendText("""{"event":"ms.channel.connect","data":{}}"""); peer.readText() }.use { server ->
            val client = SamsungRemoteClient("127.0.0.1", "CastApp", urlFor = { secure, _ ->
                if (secure) "wss://127.0.0.1:$closedPort/" else "ws://127.0.0.1:${server.port}/"
            })
            val conn = client.connect("tok", onPrompt = {})
            assertEquals("tok", conn.token)
            conn.close()
        }
    }
}
