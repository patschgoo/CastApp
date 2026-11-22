package io.github.patschgoo.castapp.cast

import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.StreamServer
import io.github.patschgoo.castapp.stream.VideoConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs [CastClient] against a scripted fake receiver (plain TCP instead of TLS)
 * that behaves like the Default Media Receiver, and checks that the receiver can
 * actually fetch the stream URL it is given.
 */
class CastClientTest {
    private val server = ServerSocket(0)
    private val received = CopyOnWriteArrayList<CastMessage>()
    private val fetchedPrefix = CountDownLatch(1)
    @Volatile private var fetchedBytes = ByteArray(0)

    @After
    fun tearDown() {
        server.close()
    }

    private fun startFakeReceiver() {
        Thread {
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            try {
                while (true) {
                    val msg = CastMessage.readFramed(input)
                    received.add(msg)
                    val json = JSONObject(msg.payloadUtf8 ?: "{}")
                    when (json.optString("type")) {
                        "LAUNCH" -> {
                            // First an unrelated status, then the one with our app (like real devices).
                            reply(out, msg, CastClient.NS_RECEIVER, JSONObject().put("type", "RECEIVER_STATUS").put("requestId", 0)
                                .put("status", JSONObject().put("applications", JSONArray())))
                            val app = JSONObject().put("appId", json.getString("appId")).put("sessionId", "sess-1").put("transportId", "transport-1")
                            reply(out, msg, CastClient.NS_RECEIVER, JSONObject().put("type", "RECEIVER_STATUS").put("requestId", json.getInt("requestId"))
                                .put("status", JSONObject().put("applications", JSONArray().put(app)).put("volume", JSONObject().put("level", 0.4))))
                            // The receiver pings us; the client must answer with PONG.
                            reply(out, msg, CastClient.NS_HEARTBEAT, JSONObject().put("type", "PING"))
                        }
                        "LOAD" -> {
                            val url = json.getJSONObject("media").getString("contentId")
                            Thread { fetch(url) }.start()
                            val status = JSONObject().put("mediaSessionId", 1).put("playerState", "BUFFERING")
                            reply(out, msg, CastClient.NS_MEDIA, JSONObject().put("type", "MEDIA_STATUS").put("requestId", json.getInt("requestId"))
                                .put("status", JSONArray().put(status)), source = "transport-1")
                        }
                        "STOP" -> reply(out, msg, CastClient.NS_RECEIVER, JSONObject().put("type", "RECEIVER_STATUS").put("requestId", json.getInt("requestId"))
                            .put("status", JSONObject().put("applications", JSONArray())))
                    }
                }
            } catch (_: Exception) {
            } finally {
                socket.close()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun reply(out: OutputStream, to: CastMessage, ns: String, json: JSONObject, source: String = "receiver-0") {
        synchronized(out) { CastMessage.writeFramed(out, CastMessage(source, to.sourceId, ns, json.toString())) }
    }

    private fun fetch(url: String) {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { s ->
            s.getOutputStream().write("GET ${uri.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val buf = ByteArray(4096)
            val n = s.getInputStream().read(buf)
            fetchedBytes = buf.copyOf(maxOf(n, 0))
            fetchedPrefix.countDown()
        }
    }

    @Test
    fun launchLoadAndStop() = runBlocking {
        startFakeReceiver()
        val hub = StreamHub()
        hub.expectAudio(false)
        hub.setVideoConfig(VideoConfig(1280, 720, byteArrayOf(0x67, 0x64, 0x00, 0x1f, 0x01), byteArrayOf(0x68, 0x01)))
        val stream = StreamServer(hub, 0)
        stream.start()

        val statuses = CopyOnWriteArrayList<JSONObject>()
        val client = CastClient("127.0.0.1", server.localPort, useTls = false)
        client.listener = object : CastClient.Listener {
            override fun onReceiverStatus(status: JSONObject) { statuses.add(status) }
        }
        client.connect(2000)
        try {
            val app = client.launch(CastClient.DEFAULT_MEDIA_RECEIVER, 5000)
            assertEquals("sess-1", app.sessionId)
            assertEquals("transport-1", app.transportId)

            val url = stream.urlFor(client.localAddress!!, stream.mp4Path)
            val status = client.load(app, CastClient.liveMedia(url, hls = false, title = "t", subtitle = "s"), 5000)
            assertEquals(1, status.getInt("mediaSessionId"))

            // The fake receiver fetched our stream: HTTP 200 followed by an MP4 init segment.
            assertTrue(fetchedPrefix.await(5, TimeUnit.SECONDS))
            val text = String(fetchedBytes, Charsets.ISO_8859_1)
            assertTrue(text, text.startsWith("HTTP/1.1 200 OK"))
            assertTrue(text.contains("Content-Type: video/mp4"))
            assertTrue(text.contains("ftyp") && text.contains("moov"))

            client.stopApp(app)
        } finally {
            client.close()
            stream.stop()
        }

        val types = received.map { it.namespace to JSONObject(it.payloadUtf8!!).optString("type") }
        assertTrue(types.contains(CastClient.NS_CONNECTION to "CONNECT"))
        assertTrue(types.contains(CastClient.NS_RECEIVER to "LAUNCH"))
        assertTrue(types.contains(CastClient.NS_HEARTBEAT to "PONG"))
        assertTrue(types.contains(CastClient.NS_MEDIA to "LOAD"))
        assertTrue(types.contains(CastClient.NS_RECEIVER to "STOP"))
        // The app channel must be opened before LOAD is sent to it.
        val connectToApp = received.indexOfFirst { it.destinationId == "transport-1" && it.namespace == CastClient.NS_CONNECTION }
        val load = received.indexOfFirst { it.namespace == CastClient.NS_MEDIA }
        assertTrue(connectToApp in 0 until load)
        assertTrue(statuses.any { it.optJSONObject("volume")?.optDouble("level") == 0.4 })
    }

    @Test
    fun streamUrlRequiresToken() {
        val hub = StreamHub()
        val stream = StreamServer(hub, 0)
        stream.start()
        try {
            Socket("127.0.0.1", stream.port).use { s ->
                s.getOutputStream().write("GET /stream.mp4 HTTP/1.1\r\n\r\n".toByteArray())
                val reply = String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue(reply, reply.startsWith("HTTP/1.1 404"))
            }
        } finally {
            stream.stop()
        }
    }
}
