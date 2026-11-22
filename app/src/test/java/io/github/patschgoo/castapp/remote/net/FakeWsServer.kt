package io.github.patschgoo.castapp.remote.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext

/**
 * A tiny WebSocket server for tests (RFC 6455 server side, written independently of the
 * client: its own handshake parsing, unmasked frames out, masked frames required in).
 */
class FakeWsServer(
    secure: Boolean = false,
    /** Status line to answer instead of 101 (to test refusals); null for a normal upgrade. */
    private val refuse: String? = null,
    /** Send a wrong Sec-WebSocket-Accept. */
    private val badAccept: Boolean = false,
    private val handler: (Peer) -> Unit,
) : Closeable {
    private val server: ServerSocket = if (secure) {
        val id = ClientIdentity.generate("fake-tv")
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(id.keyManager), null, SecureRandom())
        ctx.serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress())
    } else {
        ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    }
    val port: Int get() = server.localPort
    val errors = CopyOnWriteArrayList<Throwable>()

    init {
        Thread {
            while (true) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread {
                    try {
                        s.use { handle(it) }
                    } catch (e: Throwable) {
                        errors += e
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
    }

    class Peer(val socket: Socket, private val input: InputStream, private val output: OutputStream, val path: String, val headers: Map<String, String>) {
        /** Frames the client sent, with whether they were masked. */
        class In(val fin: Boolean, val opcode: Int, val masked: Boolean, val payload: ByteArray) {
            val text: String get() = payload.toString(Charsets.UTF_8)
        }

        fun sendFrame(opcode: Int, payload: ByteArray, fin: Boolean = true) {
            output.write(WebSocket.encodeFrame(opcode, payload, mask = null, fin = fin))
            output.flush()
        }

        fun sendText(text: String) = sendFrame(WebSocket.OP_TEXT, text.toByteArray(Charsets.UTF_8))

        fun read(): In? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            val masked = b1 and 0x80 != 0
            val mask = if (masked) ByteArray(4) { input.read().toByte() } else null
            val payload = ByteArray(len.toInt())
            var off = 0
            while (off < payload.size) { val n = input.read(payload, off, payload.size - off); if (n < 0) return null; off += n }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return In(b0 and 0x80 != 0, b0 and 0x0F, masked, payload)
        }

        /** The next text frame (skipping pongs); null when the client went away. */
        fun readText(): String? {
            while (true) {
                val f = read() ?: return null
                if (!f.masked) throw AssertionError("client frame not masked")
                when (f.opcode) {
                    WebSocket.OP_TEXT -> return f.text
                    WebSocket.OP_CLOSE -> return null
                }
            }
        }
    }

    private fun handle(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val output = s.getOutputStream()
        val head = ByteArrayOutputStream()
        while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
        }
        val lines = head.toString("ISO-8859-1").trim().split("\r\n")
        val path = lines[0].split(' ')[1]
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        if (refuse != null) {
            output.write("$refuse\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        check(headers["upgrade"].equals("websocket", true)) { "no upgrade header" }
        check(headers["sec-websocket-version"] == "13") { "bad version" }
        val key = headers.getValue("sec-websocket-key")
        val accept = if (badAccept) "AAAA" else java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        output.flush()
        handler(Peer(s, input, output, path, headers))
    }

    override fun close() = server.close()
}

/** Structural JSON equality (key order doesn't matter). */
internal fun jsonDeepEquals(a: Any?, b: Any?): Boolean = when {
    a is org.json.JSONObject && b is org.json.JSONObject ->
        a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { jsonDeepEquals(a.get(it), b.get(it)) }
    a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() && (0 until a.length()).all { jsonDeepEquals(a.get(it), b.get(it)) }
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    else -> a == b
}
