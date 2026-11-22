package io.github.patschgoo.castapp.remote.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The RFC 6455 client against an in-test server. */
class WebSocketTest {

    @Test
    fun acceptKeyFromRfc() {
        // RFC 6455 section 1.3
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun frameEncodingAndLengths() {
        val mask = byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d)
        // RFC 6455 section 5.7: a masked "Hello".
        val hello = WebSocket.encodeFrame(WebSocket.OP_TEXT, "Hello".toByteArray(), mask)
        assertArrayEquals(
            byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xfa.toByte(), 0x21, 0x3d, 0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58),
            hello,
        )
        assertEquals("Hello", WebSocket.readFrame(ByteArrayInputStream(hello))!!.payload.toString(Charsets.UTF_8))
        for (size in listOf(0, 125, 126, 65535, 65536, 200_000)) {
            val data = ByteArray(size) { (it * 7).toByte() }
            val frame = WebSocket.encodeFrame(WebSocket.OP_BINARY, data, mask)
            val headerLength = when { size < 126 -> 2; size <= 65535 -> 4; else -> 10 } + 4
            assertEquals(size + headerLength, frame.size)
            val back = WebSocket.readFrame(ByteArrayInputStream(frame))!!
            assertEquals(WebSocket.OP_BINARY, back.opcode)
            assertArrayEquals(data, back.payload)
        }
        // A server frame (unmasked), not final.
        val part = WebSocket.encodeFrame(WebSocket.OP_TEXT, "ab".toByteArray(), null, fin = false)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 'a'.code.toByte(), 'b'.code.toByte()), part)
    }

    @Test
    fun talksToServer() {
        val fromClient = LinkedBlockingQueue<String>()
        val pong = LinkedBlockingQueue<ByteArray>()
        var path = ""
        val server = FakeWsServer { peer ->
            path = peer.path
            peer.sendText("welcome")
            // A fragmented message with a ping in the middle.
            peer.sendFrame(WebSocket.OP_TEXT, "frag".toByteArray(), fin = false)
            peer.sendFrame(WebSocket.OP_PING, "p1".toByteArray())
            peer.sendFrame(WebSocket.OP_CONTINUATION, "mented ".toByteArray(), fin = false)
            peer.sendFrame(WebSocket.OP_CONTINUATION, "ü".toByteArray(), fin = true)
            val big = "x".repeat(70_000)
            peer.sendText(big)
            while (true) {
                val f = peer.read() ?: break
                assertTrue("client frames must be masked", f.masked)
                when (f.opcode) {
                    WebSocket.OP_PONG -> pong.put(f.payload)
                    WebSocket.OP_TEXT -> { fromClient.put(f.text); if (f.text == "bye") peer.sendFrame(WebSocket.OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
                    WebSocket.OP_CLOSE -> break
                }
            }
        }
        server.use {
            val ws = WebSocket.connect("ws://127.0.0.1:${server.port}/api/v2/channels/x?name=abc%3D", readTimeoutMs = 5000)
            assertEquals("welcome", ws.receiveText())
            assertEquals("fragmented ü", ws.receiveText())
            assertEquals(70_000, ws.receiveText()!!.length)
            assertEquals("p1", pong.poll(5, TimeUnit.SECONDS)!!.toString(Charsets.UTF_8))
            assertEquals("/api/v2/channels/x?name=abc%3D", path)
            ws.sendText("hello server")
            assertEquals("hello server", fromClient.poll(5, TimeUnit.SECONDS))
            ws.sendText("bye")
            // The server closes; the client answers and reports the end.
            assertNull(ws.receive())
            assertEquals(1000, ws.closeCode)
            assertTrue(ws.isClosed)
            try {
                ws.sendText("too late")
                throw AssertionError("sent on a closed socket")
            } catch (_: IOException) {
            }
        }
        assertTrue(server.errors.toString(), server.errors.isEmpty())
    }

    @Test
    fun clientClose() {
        val closeFrame = LinkedBlockingQueue<ByteArray>()
        FakeWsServer { peer ->
            while (true) {
                val f = peer.read() ?: break
                if (f.opcode == WebSocket.OP_CLOSE) { closeFrame.put(f.payload); break }
            }
        }.use { server ->
            val ws = WebSocket.connect("ws://127.0.0.1:${server.port}/")
            ws.close()
            assertArrayEquals(byteArrayOf(0x03, 0xE8.toByte()), closeFrame.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun secureWebSocket() {
        FakeWsServer(secure = true) { peer -> peer.sendText(peer.readText() + "!") }.use { server ->
            val ws = WebSocket.connect("wss://127.0.0.1:${server.port}/channel")
            ws.sendText("tls")
            assertEquals("tls!", ws.receiveText())
            ws.close()
        }
    }

    @Test
    fun refusedUpgrades() {
        FakeWsServer(refuse = "HTTP/1.1 403 Forbidden") {}.use { server ->
            try {
                WebSocket.connect("ws://127.0.0.1:${server.port}/")
                throw AssertionError("connected")
            } catch (e: WebSocketHandshakeException) {
                assertEquals(403, e.status)
            }
        }
        FakeWsServer(badAccept = true) {}.use { server ->
            try {
                WebSocket.connect("ws://127.0.0.1:${server.port}/")
                throw AssertionError("connected")
            } catch (e: WebSocketHandshakeException) {
                assertEquals(101, e.status)
            }
        }
    }
}
