package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.remote.RemoteKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** LG webOS: registration message (checked against aiowebostv 1.1.1) and the flow against a fake TV. */
class LgRemoteTest {
    private fun same(expected: String, actual: String) =
        assertTrue("$expected\n!=\n$actual", jsonDeepEquals(JSONObject(expected), JSONObject(actual)))

    @Test
    fun registrationMatchesAiowebostv() {
        val lines = javaClass.getResourceAsStream("/remote/lg_register_reference.txt")!!.bufferedReader().readLines()
        val signed = lines.first { it.startsWith("LG_SIGNED ") }.removePrefix("LG_SIGNED ")
        val unsigned = lines.first { it.startsWith("LG_UNSIGNED ") }.removePrefix("LG_UNSIGNED ")
        same(signed, LgProtocol.register("abc123", signed = true))
        same(unsigned, LgProtocol.register("abc123", signed = false))
        // Without a key, there's no client-key field at all.
        assertTrue(!JSONObject(LgProtocol.register(null)).getJSONObject("payload").has("client-key"))
    }

    @Test
    fun messageFormats() {
        assertEquals("type:button\nname:UP\n\n", LgProtocol.button("UP"))
        same("""{"id":"c1","type":"request","uri":"ssap://audio/volumeUp","payload":{}}""", LgProtocol.request("c1", "ssap://audio/volumeUp"))
        same(
            """{"id":"t1","type":"request","uri":"ssap://com.webos.service.ime/insertText","payload":{"text":"hi","replace":0}}""",
            LgProtocol.insertText("t1", "hi"),
        )
        assertEquals("ssap://system/turnOff", LgProtocol.requests[RemoteKey.POWER])
        assertEquals("ENTER", LgProtocol.buttons[RemoteKey.OK])
        assertEquals("5", LgProtocol.buttons[RemoteKey.NUM_5])
    }

    @Test
    fun registersAndControls() = runBlocking {
        val main = LinkedBlockingQueue<JSONObject>()
        val pointer = LinkedBlockingQueue<String>()
        var serverPort = 0
        val server = FakeWsServer { peer ->
            if (peer.path.startsWith("/resources/")) {
                while (true) pointer.put(peer.readText() ?: break)
                return@FakeWsServer
            }
            while (true) {
                val msg = JSONObject(peer.readText() ?: break)
                val id = msg.optString("id")
                when (msg.optString("type")) {
                    "hello" -> peer.sendText("""{"type":"hello","id":"$id","payload":{"protocolVersion":1,"deviceType":"tv","deviceOS":"webOS"}}""")
                    "register" -> {
                        assertEquals("old-key", msg.getJSONObject("payload").optString("client-key"))
                        peer.sendText("""{"type":"response","id":"$id","payload":{"pairingType":"PROMPT","returnValue":true}}""")
                        Thread.sleep(300)
                        peer.sendText("""{"type":"registered","id":"$id","payload":{"client-key":"new-key"}}""")
                    }
                    "request" -> when (msg.optString("uri")) {
                        "ssap://system/getSystemInfo" -> peer.sendText("""{"type":"response","id":"$id","payload":{"returnValue":true,"modelName":"OLED55C1"}}""")
                        "ssap://com.webos.service.networkinput/getPointerInputSocket" -> peer.sendText(
                            """{"type":"response","id":"$id","payload":{"returnValue":true,"socketPath":"ws://127.0.0.1:$serverPort/resources/abc/netinput.pointer.sock"}}""",
                        )
                        else -> {
                            main.put(msg)
                            peer.sendText("""{"type":"response","id":"$id","payload":{"returnValue":true}}""")
                        }
                    }
                }
            }
        }
        serverPort = server.port
        server.use {
            val closed = ServerSocket(0).use { it.localPort }
            var prompted = false
            // The secure port isn't there (older firmware): falls back to ws://.
            val conn = LgRemoteClient("127.0.0.1", listOf("wss://127.0.0.1:$closed", "ws://127.0.0.1:${server.port}"))
                .connect("old-key", onPrompt = { prompted = true })
            assertTrue(prompted)
            assertEquals("new-key", conn.clientKey)

            conn.press(RemoteKey.UP)
            conn.press(RemoteKey.OK)
            conn.press(RemoteKey.PLAY_PAUSE)
            conn.press(RemoteKey.PLAY_PAUSE)
            assertEquals("type:button\nname:UP\n\n", pointer.poll(5, TimeUnit.SECONDS))
            assertEquals("type:button\nname:ENTER\n\n", pointer.poll(5, TimeUnit.SECONDS))
            assertEquals("type:button\nname:PAUSE\n\n", pointer.poll(5, TimeUnit.SECONDS))
            assertEquals("type:button\nname:PLAY\n\n", pointer.poll(5, TimeUnit.SECONDS))

            conn.press(RemoteKey.VOLUME_UP)
            assertEquals("ssap://audio/volumeUp", main.poll(5, TimeUnit.SECONDS)!!.getString("uri"))
            conn.type("news")
            val text = main.poll(5, TimeUnit.SECONDS)!!
            assertEquals("ssap://com.webos.service.ime/insertText", text.getString("uri"))
            assertEquals("news", text.getJSONObject("payload").getString("text"))
            conn.press(RemoteKey.POWER)
            assertEquals("ssap://system/turnOff", main.poll(5, TimeUnit.SECONDS)!!.getString("uri"))
            conn.close()
        }
        assertTrue(server.errors.toString(), server.errors.isEmpty())
    }

    @Test
    fun deniedRegistration() = runBlocking {
        FakeWsServer { peer ->
            while (true) {
                val msg = JSONObject(peer.readText() ?: break)
                when (msg.optString("type")) {
                    "hello" -> peer.sendText("""{"type":"hello","id":"hello","payload":{}}""")
                    "request" -> peer.sendText("""{"type":"response","id":"${msg.optString("id")}","payload":{"returnValue":true}}""")
                    "register" -> peer.sendText("""{"type":"error","id":"register_0","error":"403 User denied access","payload":{}}""")
                }
            }
        }.use { server ->
            try {
                LgRemoteClient("127.0.0.1", listOf("ws://127.0.0.1:${server.port}")).connect(null, onPrompt = {})
                throw AssertionError("registered")
            } catch (_: RemoteDeniedException) {
            }
        }
    }
}
